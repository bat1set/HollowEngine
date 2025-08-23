package ru.hollowhorizon.hollowengine.common.fsm

import kotlinx.coroutines.*
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hc.common.events.Event
import ru.hollowhorizon.hc.common.events.EventBus
import ru.hollowhorizon.hc.common.events.EventListener
import ru.hollowhorizon.hc.common.events.tick.TickEvent
import ru.hollowhorizon.hollowengine.HollowEngine
import kotlin.coroutines.coroutineContext
import kotlin.reflect.KClass

class TransitionRequest(val path: String) : CancellationException()

open class StateNode(
    val name: String,
    val parent: StateNode? = null,
    var initializer: suspend StateNode.() -> Unit
) {
    val children = mutableMapOf<String, StateNode>()
    var tag = CompoundTag()
    var initialState = "main"

    var onEnter: suspend StateNode.() -> Unit = {}
    var onExit: (String) -> Boolean = { true }
    var onSave: suspend StateNode.() -> Unit = {}
    var onLoad: suspend StateNode.() -> Unit = {}
    var commonBody: (suspend StateNode.() -> Unit)? = null

    private val eventHandlers = mutableListOf<EventHandler<*>>()
    private val boundGraphs = mutableMapOf<String, StateNode>()
    private var transitionWaiter: CompletableDeferred<TransitionRequest>? = null

    fun pathToRoot(): List<String> = parent?.pathToRoot().orEmpty() + name
    private fun root(): StateNode = parent?.root() ?: this

    private fun resolvePath(relativePath: String): StateNode {
        val parts = relativePath.split("/")
        val base = if (relativePath.startsWith("/")) root() else this
        return parts.fold(base) { node, part ->
            when (part) {
                ".", "" -> node
                ".." -> node.parent ?: error("StateNode '${node.name}' has no parent.")
                else -> node.children[part] ?: error("Child state '$part' not found in '${node.name}'.")
            }
        }
    }

    suspend fun activate() {
        withContext(StateStorage(tag) + coroutineContext) { // Populate states
            initializer()
        }
        var current: StateNode = children[initialState] ?: error("Initial state '$initialState' not found.")

        while (true) {
            try {
                current.execute()
                break // FSM finished
            } catch (e: TransitionRequest) {
                if (!current.onExit(e.path)) continue

                val nextState = current.resolvePath(e.path)
                current = nextState
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                HollowEngine.LOGGER.error("Error in state machine '$name' at state '${current.name}'", e)
                break
            }
        }
    }

    private suspend fun execute() {
        withContext(StateStorage(tag) + coroutineContext) {
            onLoad()
            boundGraphs.forEach { (key, graph) ->
                graph.tag = tag.getCompound(key)
                graph.onLoad()
            }
            eventHandlers.forEach { it.subscribe() }

            try {
                transitionWaiter = CompletableDeferred()
                coroutineScope {
                    val onEnterJob = launch { onEnter() }
                    val commonJob = commonBody?.let { launch { it(this@StateNode) } }

                    val request = transitionWaiter?.await() ?: return@coroutineScope
                    onEnterJob.cancel()
                    commonJob?.cancel()
                    throw request
                }
            } finally {
                eventHandlers.forEach { it.unsubscribe() }
                onSave()
                boundGraphs.forEach { (key, graph) ->
                    graph.onSave()
                    tag.put(key, graph.tag)
                }
            }
        }
    }

    fun transition(path: String): Nothing {
        val request = TransitionRequest(path)
        if (transitionWaiter?.complete(request) == true) {
            throw request
        } else {
            throw IllegalStateException("Cannot transition when state is not active.")
        }
    }

    suspend fun state(path: String, initializer: suspend StateNode.() -> Unit): StateNode {
        require(!path.contains("/")) { "State path cannot contain '/'." }
        val child = StateNode(path, this, initializer)
        if (!tag.contains(path)) tag.put(path, CompoundTag())
        child.tag = tag.getCompound(path)
        children[path] = child

        withContext(StateStorage(child.tag) + coroutineContext) {
            child.initializer()
        }
        return child
    }

    fun common(body: suspend StateNode.() -> Unit) {
        this.commonBody = body
    }

    fun <T : Event> on(eventClass: KClass<T>, handler: (T) -> Unit): EventHandler<T> {
        val eventHandler = EventHandler(eventClass) {
            try {
                handler(it)
            } catch (e: TransitionRequest) {
                transitionWaiter?.complete(e)
            }
        }
        eventHandlers.add(eventHandler)
        return eventHandler
    }

    inline fun <reified T : Event> on(noinline handler: (T) -> Unit) = on(T::class, handler)

    fun onUpdate(handler: (TickEvent.Server) -> Unit) = on(TickEvent.Server::class, handler)

    fun transitionTo(other: StateNode) {
        onEnter = { transition(other.name) }
    }

    fun bind(key: String, graph: StateNode) {
        boundGraphs[key] = graph
    }

    class EventHandler<T : Event>(
        private val eventClass: KClass<T>,
        private val handler: (T) -> Unit
    ) {
        private var listener: EventListener<T>? = null

        fun subscribe() {
            if (listener == null) {
                listener = EventListener(handler)
                @Suppress("UNCHECKED_CAST")
                EventBus.registerNoInline(eventClass.java as Class<Event>, listener as EventListener<Event>)
            }
        }

        fun unsubscribe() {
            listener?.let { EventBus.unregisterNoInline(eventClass.java as Class<Event>, it as EventListener<Event>) }
            listener = null
        }
    }
}

suspend fun graphnet(initializer: suspend StateNode.() -> Unit): StateNode {
    val root = StateNode("root", initializer = initializer)
    return root
}