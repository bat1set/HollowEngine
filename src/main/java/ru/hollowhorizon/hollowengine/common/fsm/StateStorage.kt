package ru.hollowhorizon.hollowengine.common.fsm

import kotlinx.coroutines.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.serializer
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.LivingEntity
import ru.hollowhorizon.hc.common.capabilities.SyncableListImpl
import ru.hollowhorizon.hc.common.coroutines.coroutineScope
import ru.hollowhorizon.hc.common.utils.currentServer
import ru.hollowhorizon.hc.common.utils.nbt.NBTFormat
import ru.hollowhorizon.hc.common.utils.nbt.deserialize
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.getLevel
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class StateStorage(val tag: CompoundTag) : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<StateStorage>

    override val key: CoroutineContext.Key<*> get() = Key
}

suspend inline fun <reified T : Any> remember(name: String, noinline initializer: () -> T): ReadWriteProperty<Any?, T> {
    val tag = coroutineContext[StateStorage]?.tag ?: error("StateStorage not found!")
    val variable =
        if (name in tag) NBTFormat.deserialize<T>(tag.get(name)!!)
        else initializer()

    return RememberValue(name, variable, serializer<T>(), tag)
}

class RememberValue<T : Any>(
    val name: String,
    var variable: T,
    private val type: KSerializer<T>,
    private val tag: CompoundTag,
) : ReadWriteProperty<Any?, T> {
    fun save() {
        tag.put(name, NBTFormat.serialize(type, variable))
    }

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = variable

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        variable = value
        save()
    }
}

suspend inline fun <reified T : Any> rememberList(
    name: String,
    noinline initializer: () -> MutableList<T>,
): ReadWriteProperty<Any?, MutableList<T>> {
    val tag = coroutineContext[StateStorage]?.tag ?: error("StateStorage not found!")
    val variable: MutableList<T> =
        if (name in tag) NBTFormat.deserialize<List<T>>(tag.get(name)!!).toMutableList()
        else initializer()

    @Suppress("UNCHECKED_CAST")
    val serializer = ListSerializer(serializer<T>()) as KSerializer<MutableList<T>>
    val delegate = RememberValue(name, variable, serializer, tag)
    val syncList = SyncableListImpl(variable, T::class.java, delegate::save)
    delegate.variable = syncList

    return delegate
}

suspend fun <T : LivingEntity> rememberEntity(name: String, initializer: suspend () -> T): T {
    val tag = coroutineContext[StateStorage]?.tag ?: error("StateStorage not found!")
    val server = currentServer

    return if (name in tag) {
        val context = tag.getCompound(name)
        val uuid = context.getUUID("uuid")
        val level = server.getLevel(context.getString("level"))
        await { level.getEntity(uuid) != null }
        @Suppress("UNCHECKED_CAST")
        level.getEntity(uuid) as T
    } else {
        initializer().apply {
            tag.put(name, CompoundTag().apply {
                putString("level", level().dimension().location().toString())
                putUUID("uuid", uuid)
            })
        }
    }
}

suspend fun await(condition: () -> Boolean) {
    if (condition()) return
    suspendCancellableCoroutine { continuation ->
        val job = currentServer.coroutineScope.launch {
            while (continuation.isActive) {
                if (condition()) {
                    continuation.resume(Unit)
                    break
                }
                delay(50L)
            }
        }
        continuation.invokeOnCancellation { job.cancel() }
    }
}