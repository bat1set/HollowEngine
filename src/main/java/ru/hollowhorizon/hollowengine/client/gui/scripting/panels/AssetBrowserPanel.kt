package ru.hollowhorizon.hollowengine.client.gui.scripting.panels

import de.fabmax.kool.Assets
import de.fabmax.kool.loadImage2d
import de.fabmax.kool.input.CursorShape
import de.fabmax.kool.input.PointerInput
import de.fabmax.kool.math.Easing
import de.fabmax.kool.math.clamp
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Font
import de.fabmax.kool.util.MutableColor
import net.minecraft.client.Minecraft
import ru.hollowhorizon.hollowengine.client.gui.scripting.FileNode
import ru.hollowhorizon.hollowengine.client.gui.scripting.IdeContent
import ru.hollowhorizon.hollowengine.client.gui.scripting.ScriptingEnvironmentScreen
import ru.hollowhorizon.hollowengine.client.gui.scripting.files.IconHelper
import ru.hollowhorizon.hollowengine.client.gui.scripting.files.ImageFileData
import ru.hollowhorizon.hollowengine.client.gui.scripting.files.TextFileData
import ru.hollowhorizon.hollowengine.client.gui.scripting.theme.IdeTheme
import ru.hollowhorizon.hollowengine.client.gui.scripting.tools.hoverListener
import ru.hollowhorizon.hollowengine.common.files.DirectoryManager.fromReadablePath
import kotlin.math.max

private fun UiScope.hoverColor(
    duration: Float = 0.5f,
    color: Color,
    hoverColor: Color,
    condition: () -> Boolean = { true },
): MutableColor {
    val (isHovered, anim) = hoverListener(duration, condition)
    var factor = Easing.quadRev(anim.progressAndUse())
    if (!isHovered.use()) factor = 1f - factor
    return color.mix(hoverColor, factor)
}

class AssetBrowserPanel(dock: de.fabmax.kool.modules.ui2.docking.Dock) : DockPanel("hollowengine.gui.ide.assets", dock) {
    override val icon = "hollowengine:textures/gui/icons/assets.png"

    private val rootDir = FileNode("assets", "assets").apply { isFolder = true; update() }
    private val currentDir = mutableStateOf(rootDir)
    private val navPanelWidth = mutableStateOf(Dp(200f))
    private val iconCache = mutableMapOf<String, Texture2d>()

    private fun getIcon(path: String): Texture2d {
        return iconCache.getOrPut(path) {
            Texture2d(name = path) { Assets.loadImage2d(path).getOrThrow() }
        }
    }

    override fun UiScope.compose() {
        Row(width = Grow.Std, height = Grow.Std) {
            modifier.backgroundColor(colors.background)
            NavigationPanel()
            Splitter()
            ContentPanel()
        }
    }

    private fun UiScope.NavigationPanel() {
        val navItems = mutableListOf<FileNode>()
        currentDir.use().parent?.let { parent ->
            navItems.add(FileNode("..", parent.treePath).apply { isFolder = true; this.parent = parent })
        }
        navItems.addAll(currentDir.use().children.sortedBy { it.treeName })

        Column(width = navPanelWidth.use(), height = Grow.Std) {
            LazyColumn(
                containerModifier = { it.padding(sizes.smallGap) }
            ) {
                items(navItems) { item ->
                    val isSelected = item.isFolder && item.treePath == currentDir.value.treePath
                    Row(width = Grow.Std, height = sizes.largeGap) {
                        modifier
                            .padding(horizontal = sizes.smallGap)
                            .onClick {
                                if (item.isFolder) {
                                    if (item.treeName == "..") {
                                        item.parent?.let { currentDir.set(it) }
                                    } else {
                                        item.update()
                                        currentDir.set(item)
                                    }
                                }
                            }
                            .background(
                                RoundRectBackground(
                                    hoverColor(
                                        color = if (isSelected) colors.primary.withAlpha(0.2f) else Color.BLACK.withAlpha(0f),
                                        hoverColor = colors.primary.withAlpha(0.4f)
                                    ),
                                    sizes.smallGap
                                )
                            )

                        Image(getIcon(IconHelper.forPath(item.treePath, item.isFolder))) {
                            modifier
                                .size(sizes.gap * 1.5f, sizes.gap * 1.5f)
                                .alignY(AlignmentY.Center)
                                .margin(end = sizes.smallGap)
                        }

                        Text(item.treeName) {
                            modifier.alignY(AlignmentY.Center)
                                .textColor(if (item.isFolder) colors.primary else colors.onBackground)
                        }
                    }
                }
            }
        }
    }

    private fun UiScope.Splitter() {
        val isSplitterHovered = remember(false)
        val dragStartWidth = remember(0f)
        Box(width = sizes.borderWidth * 2f, height = Grow.Std) {
            modifier
                .onEnter { isSplitterHovered.value = true }
                .onExit { isSplitterHovered.value = false }
                .onHover { PointerInput.cursorShape = CursorShape.RESIZE_EW }
                .onDragStart {
                    dragStartWidth.value = navPanelWidth.value.px
                }
                .onDrag {
                    val newWidthPx = dragStartWidth.value + it.pointer.dragMovement.x
                    val clampedPx = newWidthPx.clamp(150.dp.px, 500.dp.px)
                    navPanelWidth.set(Dp.fromPx(clampedPx))
                }
                .backgroundColor(Color.BLACK.withAlpha(0.0001f))

            Box(width = sizes.borderWidth, height = Grow.Std) {
                modifier
                    .alignX(AlignmentX.Center)
                    .backgroundColor(if (isSplitterHovered.use()) colors.primary else IdeTheme.colors.secondaryVariant)
            }
        }
    }

    private fun UiScope.ContentPanel() {
        Column(width = Grow.Std, height = Grow.Std) {
            val scrollState = rememberScrollState()
            ScrollArea(
                state = scrollState,
                containerModifier = { it.background(null) },
                vScrollbarModifier = { it.colors(colors.secondaryVariant, colors.secondary) }
            ) {
                Column(width = Grow.Std) {
                    modifier.padding(sizes.gap)

                    val items = currentDir.use().children.sortedWith(compareBy({ !it.isFolder }, { it.treeName }))
                    val itemSize = Dp(120f)
                    val gap = sizes.gap

                    val availableW = scrollState.viewWidthDp.use().dp.px
                    var itemsPerRow = 1
                    if (availableW > 0f) {
                        itemsPerRow = max(1, ((availableW + gap.px) / (itemSize.px + gap.px)).toInt())
                    }

                    items.chunked(itemsPerRow).forEach { rowItems ->
                        Row(width = Grow.Std) {
                            modifier.margin(bottom = gap)
                            rowItems.forEach { item ->
                                AssetView(item, itemSize)
                            }
                            Box(width = Grow.Std) { }
                        }
                    }
                }
            }
        }
    }

    private fun truncateText(text: String, font: Font, availableWidth: Dp, isTruncated: MutableStateValue<Boolean>): String {
        val ellipsis = "..."
        val fullWidth = font.textDimensions(text).width
        val ellipsisWidth = font.textDimensions(ellipsis).width

        if (fullWidth <= availableWidth.px) {
            isTruncated.set(false)
            return text
        }

        isTruncated.set(true)
        var newLen = 0
        var currentWidth = 0f
        while (newLen < text.length && currentWidth + font.charWidth(text[newLen]) + ellipsisWidth < availableWidth.px) {
            currentWidth += font.charWidth(text[newLen])
            newLen++
        }
        return text.substring(0, newLen) + ellipsis
    }

    private fun UiScope.AssetView(item: FileNode, itemSize: Dp) {
        val isTruncated = remember(false)

        Column(width = itemSize, height = itemSize) {
            modifier
                .margin(horizontal = sizes.gap * 0.5f)
                .background(
                    RoundRectBackground(
                        hoverColor(color = Color.BLACK.withAlpha(0f), hoverColor = IdeTheme.hoveredColors.background),
                        sizes.smallGap
                    )
                )
                .onClick {
                    if (item.isFolder) {
                        item.update()
                        currentDir.set(item)
                    } else {
                        val screen = Minecraft.getInstance().screen as? ScriptingEnvironmentScreen ?: return@onClick
                        val path = item.treePath
                        val extension = path.substringAfterLast('.')

                        val file = IdeContent.files[path]
                        if (file != null) {
                            screen.dock.getLeafAtPath("0:col/0:row/1:leaf")?.bringToTop(file.dockable)
                            return@onClick
                        }

                        val bytes = path.fromReadablePath().readBytes()
                        when (extension) {
                            "png", "jpg", "jpeg", "gif" -> IdeContent.openFile(path, bytes, ::ImageFileData)
                            else -> IdeContent.openFile(path, bytes, ::TextFileData)
                        }
                    }
                }

            if (isTruncated.use()) {
                Tooltip(item.treeName, backgroundColor = colors.backgroundVariant, borderColor = colors.primaryVariantAlpha(0.5f))
            }

            Box(width = Grow.Std, height = Grow.Std) {
                modifier.padding(sizes.largeGap)
                Image(getIcon(IconHelper.forPath(item.treePath, item.isFolder))) {
                    modifier
                        .align(AlignmentX.Center, AlignmentY.Center)
                        .imageSize(ImageSize.FitContent)
                        .size(itemSize * 0.5f, itemSize * 0.5f)
                }
            }

            val availableTextWidth = itemSize - sizes.gap * 2
            val truncatedText = truncateText(item.treeName, sizes.normalText, availableTextWidth, isTruncated)
            Text(truncatedText) {
                modifier
                    .width(Grow.Std)
                    .textAlignX(AlignmentX.Center)
                    .padding(bottom = sizes.gap)
            }
        }
    }
}