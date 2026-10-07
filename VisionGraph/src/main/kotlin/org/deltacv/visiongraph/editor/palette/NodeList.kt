/*
 * VisionGraph
 * Copyright (C) 2026 Sebastian Erives, deltacv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.deltacv.visiongraph.editor.palette

import imgui.ImColor
import imgui.ImGui
import imgui.ImVec2
import imgui.extension.imnodes.ImNodes
import imgui.extension.imnodes.ImNodesEditorContext
import imgui.extension.imnodes.flag.ImNodesCol
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiCond
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags
import org.deltacv.mai18n.tr
import org.deltacv.visiongraph.VisionGraph
import org.deltacv.visiongraph.attribute.Attribute
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.isModalWindowOpen
import org.deltacv.visiongraph.gui.style.opacity
import org.deltacv.visiongraph.id.container.DenseIdContainer
import org.deltacv.visiongraph.id.container.IdContext
import org.deltacv.visiongraph.io.KeyManager
import org.deltacv.visiongraph.node.*
import org.deltacv.visiongraph.util.ElapsedTime
import org.deltacv.visiongraph.util.flags
import org.deltacv.visiongraph.util.loggerForThis
import kotlin.collections.iterator
import kotlin.reflect.KClass

class NodeList(
    val visionGraph: VisionGraph,
    val keyManager: KeyManager,
    val nodeClasses: CategorizedNodes
) : Window() {

    companion object {
        const val PLUS_FONT_SIZE = 60f
    }

    val listNodes = DenseIdContainer<Node<*>>()
    val listAttributes = DenseIdContainer<Attribute>()

    private val logger by loggerForThis()

    private val keys = keyManager.keys

    val closeElapsedTime = ElapsedTime()
    var isNodesListOpen = false
        private set

    private val openButtonTimeout = ElapsedTime()

    lateinit var headers: Headers
        private set

    private lateinit var listContext: ImNodesEditorContext

    private var highlightedNodeClass: KClass<out Node<*>>? = null
    private var highlightedNode: Int? = null

    private var highlightTimer = ElapsedTime()
    private var highlightStatus = false

    var hoveredNode = -1
        private set

    var hoveredNodePos = ImVec2()
        private set

    var isHoverManuallyDetected = false
        private set
    var isHoveringScrollBar = false
        private set
    var isHoldingScrollBar = false
        private set

    private val drawnNodes = mutableListOf<Int>()

    override var title = "list"
    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize, ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoCollapse, ImGuiWindowFlags.NoTitleBar,
        ImGuiWindowFlags.NoDecoration
    )

    private val defaultFontBig by Font.findLazy("calcutta-big")

    override fun onEnable() {
        // use different id stacks for the node list, we dont want these nodes on the actual editor
        IdContext.local.push(listNodes)
        IdContext.local.push(listAttributes)

        headers = Headers(keyManager) { nodes }

        IdContext.local.pop<Node<*>>()
        IdContext.local.pop<Attribute>()
    }

    override fun preDrawContents() {
        if (!isNodesListOpen || Window.isModalWindowOpen) {
            closeList()
            return
        }

        val size = visionGraph.window.size

        ImGui.setNextWindowPos(0f, 0f)
        ImGui.setNextWindowSize(size.x, size.y, ImGuiCond.Always)

        ImGui.pushStyleColor(ImGuiCol.WindowBg, 0f, 0f, 0f, 0.70f) // transparent dark window background
    }

    override fun drawContents() {
        if (!isNodesListOpen) {
            return
        }

        IdContext.local.push(listNodes)
        IdContext.local.push(listAttributes)

        val size = visionGraph.window.size

        ImNodes.editorContextSet(listContext)

        // NODES WINDOW

        ImNodes.getStyle().gridSpacing = 99999f // lol
        ImNodes.pushColorStyle(ImNodesCol.GridBackground, ImColor.rgba(0f, 0f, 0f, 0f))

        ImNodes.clearNodeSelection()
        ImNodes.clearLinkSelection()

        ImNodes.beginNodeEditor()

        if (highlightTimer.millis > 500) {
            highlightStatus = !highlightStatus
            highlightTimer.reset()
        }

        for (category in NodeCategory.entries) {
            if (nodes.containsKey(category)) {
                val table = headers.categoryTables[category] ?: continue

                if (headers.categoryStates[category] == true) {
                    for (node in nodes[category]!!) {
                        var imNodesPushColorCount = 0
                        var imGuiPushColorCount = 0

                        if (drawnNodes.contains(node.id)) {
                            if (!table.contains(node.id)) {
                                val nodeSize = ImVec2()
                                ImNodes.getNodeDimensions(nodeSize, node.id)

                                table.add(node.id, nodeSize)
                            } else {
                                val pos = table.getPos(node.id)!!
                                ImNodes.setNodeScreenSpacePos(node.id, pos.x, pos.y)
                            }
                        }

                        val titleColor = if (node is DrawNode<*>) {
                            node.titleColor
                        } else 0

                        val highlighted =
                            (isHoverManuallyDetected && hoveredNode == node.id) || (highlightedNode == node.id && highlightStatus)

                        if (highlighted) {
                            if (node.description != null && hoveredNode == node.id) {
                                defaultFontBig.push()

                                ImGui.beginTooltip()
                                ImGui.textUnformatted(tr(node.description!!))
                                ImGui.endTooltip()

                                ImGui.popFont()
                            }

                            if (node is DrawNode<*>) {
                                node.titleColor = node.titleHoverColor
                            } else {
                                ImNodes.pushColorStyle(ImNodesCol.TitleBar, VisionGraph.imnodesStyle.titleBarHovered)
                                imNodesPushColorCount++
                            }

                            ImNodes.pushColorStyle(
                                ImNodesCol.NodeBackground,
                                VisionGraph.imnodesStyle.nodeBackgroundHovered
                            )

                            imNodesPushColorCount++
                        } else if (highlightedNode != null && highlightedNode != node.id) {
                            if (node is DrawNode<*>) {
                                node.titleColor = node.titleColor.opacity(0.5f)
                            } else {
                                ImNodes.pushColorStyle(
                                    ImNodesCol.TitleBar,
                                    VisionGraph.imnodesStyle.titleBar.opacity(0.5f)
                                )
                                imNodesPushColorCount++
                            }

                            ImNodes.pushColorStyle(
                                ImNodesCol.NodeBackground,
                                VisionGraph.imnodesStyle.nodeBackground.opacity(0.5f)
                            )
                            imNodesPushColorCount++

                            ImGui.pushStyleColor(
                                ImGuiCol.Text,
                                ImColor.rgba(ImGui.getStyleColorVec4(ImGuiCol.Text)).opacity(0.5f)
                            )
                            imGuiPushColorCount++
                        }

                        node.draw()

                        if (!drawnNodes.contains(node.id)) {
                            drawnNodes.add(node.id)
                        }

                        if (highlighted || (highlightedNode != null && highlightedNode != node.id)) {
                            if (node is DrawNode<*>) {
                                node.titleColor = titleColor
                            }
                        }

                        repeat(imGuiPushColorCount) {
                            ImGui.popStyleColor()
                        }
                        repeat(imNodesPushColorCount) {
                            ImNodes.popColorStyle()
                        }
                    }
                }
            }
        }

        ImNodes.endNodeEditor()
        ImGui.popStyleColor()

        ImNodes.editorContextResetPanning(0f, 0f)

        val mousePos = ImGui.getMousePos()

        isHoveringScrollBar = mousePos.x >= (size.x - 15f)

        if (!isHoldingScrollBar) {
            isHoldingScrollBar = ImGui.isMouseDown(ImGuiMouseButton.Left) && isHoveringScrollBar
        } else {
            if (!ImGui.isMouseDown(ImGuiMouseButton.Left)) {
                isHoldingScrollBar = false
            }
        }

        hoveredNode = ImNodes.getHoveredNode()
        if (hoveredNode >= 0) {
            hoveredNodePos = ImNodes.getNodeScreenSpacePos(hoveredNode)
        } else {
            hoveredNodePos = ImVec2(0f, 0f)
        }

        isHoverManuallyDetected = false

        if (highlightedNodeClass != null) {
            for (node in listNodes) {
                if (node::class == highlightedNodeClass) {
                    if (node.id != highlightedNode) {
                        highlightedNode = node.id

                        val nodePos = hoveredNodePos.y - ImNodes.getNodeDimensionsY(highlightedNode!!) * 1.3f

                        val scrollPos = if (nodePos >= size.y * 0.9) {
                            99999f // trigger max scroll
                        } else nodePos

                        headers.nextScroll = scrollPos
                    }
                    break
                }
            }
            highlightedNodeClass = null
        }

        if (hoveredNode < 0) {
            tableLoop@ for ((category, table) in headers.categoryTables) {
                if (headers.categoryStates[category] != true) {
                    continue@tableLoop // skip closed categories
                }

                for ((id, rect) in table.currentRects) {
                    // AABB collision check with any node
                    if (mousePos.x > rect.min.x && mousePos.x < rect.max.x + 6 &&
                        mousePos.y > rect.min.y && mousePos.y < rect.max.y
                    ) {
                        hoveredNode = id
                        hoveredNodePos = ImNodes.getNodeScreenSpacePos(id)

                        isHoverManuallyDetected = true

                        break@tableLoop
                    }
                }
            }
        }

        ImNodes.getStyle().gridSpacing = 32f // back to normal
        ImNodes.popColorStyle()

        visionGraph.nodeEditor.nodeListButton.focus = isNodesListOpen && !isHoveringScrollBar && !isHoldingScrollBar

        headers.size = size

        IdContext.local.pop<Node<*>>()
        IdContext.local.pop<Attribute>()

        handleClick(!headers.isHeaderHovered)
    }

    private fun handleClick(closeOnClick: Boolean) {
        if (ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            if (hoveredNode >= 0 && !headers.isHeaderHovered) {
                val instance = visionGraph.nodeEditor.addNode(
                    listNodes[hoveredNode]!!::class
                ) // add node with the class by using reflection

                if (instance is DrawNode<*>) {
                    val mousePos = ImGui.getMousePos()

                    val newPosX = mousePos.x - hoveredNodePos.x
                    val newPosY = mousePos.y - hoveredNodePos.y

                    instance.nextNodePosition = ImVec2(newPosX, newPosY)
                    instance.pinToMouse = true
                }

                closeList()
            } else if (closeOnClick && !isHoveringScrollBar && !isHoldingScrollBar && !visionGraph.nodeEditor.nodeListButton.hovered) { // don't close when the scroll bar is clicked
                closeList()
            }
        }
    }

    fun highlight(nodeClass: KClass<out Node<*>>) {
        highlightedNodeClass = nodeClass
    }

    fun clearHighlight() {
        highlightedNode = null
    }

    override fun delete() {
        super.delete()
        headers.delete()
    }

    override fun restore() {
        super.restore()
        headers.restore()
    }

    fun showList() {
        if (!isNodesListOpen) {
            if (::listContext.isInitialized) {
                ImNodes.editorContextFree(listContext)
            }
            listContext = ImNodes.editorContextCreate()

            isNodesListOpen = true
            restore()
        }
    }

    fun closeList() {
        isNodesListOpen = false
        openButtonTimeout.reset()
        visionGraph.nodeEditor.nodeListButton.focus = false

        delete()
        closeElapsedTime.reset()
    }

    val nodes by lazy {
        val map = mutableMapOf<NodeCategory, MutableList<Node<*>>>()

        for ((category, nodeClasses) in nodeClasses) {
            val list = mutableListOf<Node<*>>()

            for (nodeClass in nodeClasses) {
                if (nodeClass.paperNodeAnnotation?.showInList == false) {
                    continue
                }

                val instance = PaperNodeRegistry.instantiate(nodeClass)

                if (instance == null) {
                    logger.warn("Skipping node ${nodeClass.simpleName}")
                    continue
                }

                // this looks ugly
                //instance.drawAttributesCircles = false

                instance.enable()

                list.add(instance)
            }

            if (list.isNotEmpty()) {
                // sort alphabetically
                list.sortBy { if (it is DrawNode<*>) it.annotationData.name else it::class.simpleName }
                map[category] = list
            }
        }

        map
    }
}