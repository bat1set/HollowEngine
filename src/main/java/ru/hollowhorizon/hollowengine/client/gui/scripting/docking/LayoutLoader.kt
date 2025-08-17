package ru.hollowhorizon.hollowengine.client.gui.scripting.docking

import de.fabmax.kool.modules.ui2.Grow
import de.fabmax.kool.modules.ui2.docking.*
import ru.hollowhorizon.hc.common.events.post
import ru.hollowhorizon.hollowengine.client.gui.scripting.IdeContent
import ru.hollowhorizon.hollowengine.client.gui.scripting.files.TextFileData
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import ru.hollowhorizon.hollowengine.mixins.kool.DockNodeInvoker

object LayoutLoader {
    val IDE_LAYOUT = "hollowengine.ide.layout"
    val TOOL_LAYOUT = "hollowengine.tool.layout"

    val layoutOrder = LinkedHashSet<String>()
    val LAYOUTS = HashMap<String, Layout>()

    fun loadIdeLayout(dock: Dock) {
        LoadLayoutEvent({ name, layout ->
            LAYOUTS[name] = layout
            layoutOrder.add(name)
        }, dock).post()
        val layoutLoader: (String) -> Dockable? = layout@{ name ->
            if (name.startsWith("scripts/")) IdeContent.openFile(
                name,
                name.fromReadablePath().readBytes(),
                ::TextFileData
            )?.dockable
            else LAYOUTS[name]?.dockable
        }

        val layoutLoaded = DockLayout.loadLayout(IDE_LAYOUT, dock, layoutLoader)

        if (!layoutLoaded) {
            dock.createNodeLayout(listOf(
                "0:col",
                "0:col/0:row",
                "0:col/1:leaf",
                "0:col/0:row/0:leaf",
                "0:col/0:row/1:leaf"
            ))

            val rootCol = dock.root as DockNodeInter
            rootCol.childNodes[0].height.set(Grow(0.7f))
            rootCol.childNodes[1].height.set(Grow(0.3f))

            val topRow = rootCol.childNodes[0] as DockNodeInter
            topRow.childNodes[0].width.set(Grow(0.2f))
            topRow.childNodes[1].width.set(Grow(0.8f))

            val projectView = dock.getLeafAtPath("0:col/0:row/0:leaf")
            layoutLoader("hollowengine.gui.ide.docs")?.let { projectView?.dock(it) }
            layoutLoader("hollowengine.gui.ide.project_tree")?.let { projectView?.dock(it) }
            layoutLoader("hollowengine.gui.ide.recipes")?.let { projectView?.dock(it) }

            val assetView = dock.getLeafAtPath("0:col/1:leaf")
            layoutLoader("hollowengine.gui.ide.assets")?.let { assetView?.dock(it) }
        }
    }
}

fun DockNode.insertItem(item: Dockable, slot: DockNode.SlotPosition) =
    (this as DockNodeInvoker).callInsertItem(item, slot)