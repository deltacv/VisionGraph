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

package org.deltacv.visiongraph.editor

import imgui.ImGui
import imgui.ImVec2
import imgui.extension.imnodes.ImNodes
import imgui.extension.imnodes.flag.ImNodesMiniMapLocation
import imgui.flag.ImGuiMouseButton
import imgui.flag.ImGuiWindowFlags
import imgui.type.ImInt
import org.deltacv.visiongraph.VisionGraph
import org.deltacv.visiongraph.action.editor.CreateLinkAction
import org.deltacv.visiongraph.action.editor.CreateNodesAction
import org.deltacv.visiongraph.action.editor.DeleteLinksAction
import org.deltacv.visiongraph.action.editor.DeleteNodesAction
import org.deltacv.visiongraph.attribute.Attribute
import org.deltacv.visiongraph.attribute.AttributeMode
import org.deltacv.visiongraph.editor.buttons.NodeListButton
import org.deltacv.visiongraph.editor.buttons.OptionsButtonWindow
import org.deltacv.visiongraph.editor.buttons.PlayButtonWindow
import org.deltacv.visiongraph.editor.buttons.SourceCodeExportButtonWindow
import org.deltacv.visiongraph.gui.ConfirmationModalWindow
import org.deltacv.visiongraph.gui.LayoutDirection
import org.deltacv.visiongraph.gui.SizingMode
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons
import org.deltacv.visiongraph.gui.display.ImageDisplay
import org.deltacv.visiongraph.node.display.ImageDisplayNode
import org.deltacv.visiongraph.gui.display.ImageDisplayWindow
import org.deltacv.visiongraph.gui.TooltipPopup
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.WindowGroup
import org.deltacv.visiongraph.editor.modal.AboutModalWindow
import org.deltacv.visiongraph.editor.menu.ContextMenuPopup
import org.deltacv.visiongraph.editor.menu.EmptyStateWindow
import org.deltacv.visiongraph.editor.menu.GuidedTourWindow
import org.deltacv.visiongraph.editor.palette.NodeList
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.isModalWindowOpen
import org.deltacv.visiongraph.gui.util.openPaperVisionDocs
import org.deltacv.visiongraph.id.DrawableIdElement
import org.deltacv.visiongraph.io.KeyManager
import org.deltacv.visiongraph.node.DirectedNodeGraph
import org.deltacv.visiongraph.node.DrawNode
import org.deltacv.visiongraph.node.FlagsNode
import org.deltacv.visiongraph.node.Link
import org.deltacv.visiongraph.node.Node
import org.deltacv.visiongraph.node.PaperNodeRegistry
import org.deltacv.visiongraph.node.vision.InputMatNode
import org.deltacv.visiongraph.node.vision.OutputMatNode
import org.deltacv.visiongraph.serialization.v2.PaperVisionProject
import org.deltacv.visiongraph.serialization.v2.json.JsonCodec
import org.deltacv.visiongraph.util.ElapsedTime
import org.deltacv.visiongraph.util.event.PaperEventHandler
import org.deltacv.visiongraph.util.flags
import org.deltacv.visiongraph.util.loggerForThis
import kotlin.reflect.KClass

data class Option(
    val description: String,
    val action: () -> Unit
)

class NodeEditor(val visionGraph: VisionGraph, private val keyManager: KeyManager) : Window() {

    companion object {
        private const val KEY_PAN_CONSTANT = 5f
        private const val PAN_CONSTANT = 25f
        private const val PASTE_COUNT_OFFSET = 50f
        private const val RIGHT_CLICK_POPUP_THRESHOLD_MS = 200L
        private const val LINK_DELETE_COOLDOWN_MS = 200L
        private const val SCROLL_COOLDOWN_MS = 500L
        private const val MINIMAP_SCALE = 0.15f
    }

    // Core editor state
    var context = ImNodes.editorContextCreate()!!
        private set

    var isNodeFocused = false
        private set

    // Node references
    private val winSizeSupplier: () -> ImVec2 = { visionGraph.window.size }
    lateinit var flagsNode: FlagsNode

    val flags get() = flagsNode.flags
    val numFlags get() = flagsNode.numFlags

    var inputNode = InputMatNode(winSizeSupplier)
        set(value) {
            value.windowSizeSupplier = winSizeSupplier
            field = value
        }

    var outputNode = OutputMatNode(winSizeSupplier)
        set(value) {
            value.windowSizeSupplier = winSizeSupplier
            value.streamId = outputImageDisplay.id
            field = value
        }

    val hasUserNodes
        get() = nodes.inmutable.any { it.isDeletable }
    val hasUserLinks
        get() = links.inmutable.any { it.isDeletable }

    val nodeList by lazy { NodeList(visionGraph, keyManager, PaperNodeRegistry.nodes) }

    val options = mutableMapOf<String, Option>()

    lateinit var nodeListButton: NodeListButton
        private set
    lateinit var optionsButton: OptionsButtonWindow
        private set
    lateinit var playButton: PlayButtonWindow
        private set
    lateinit var sourceCodeExportButton: SourceCodeExportButtonWindow
        private set

    private val emptyStateWindow = EmptyStateWindow(this)

    val onLink = PaperEventHandler("NodeEditor-OnLink")
    val onNodeInsert = PaperEventHandler("NodeEditor-OnNodeInsert")

    // Panning state
    val editorPanning = ImVec2(0f, 0f)
    val editorPanningDelta = ImVec2(0f, 0f)
    private val prevEditorPanning = ImVec2(0f, 0f)
    private var prevMouseX = 0f
    private var prevMouseY = 0f

    // Interaction state
    private var rightClickedWhileHoveringNode = false
    private val scrollTimer = ElapsedTime()
    private val rightClickMenuPopupTimer = ElapsedTime()
    private val justDeletedLinkTimer = ElapsedTime()

    val graph = DirectedNodeGraph()

    // Clipboard state
    private var pasteCount = 0
    private var pasteInitialMousePos = ImVec2()
    private var cutting = false
    var clipboard: String? = null

    // Events
    val onEditorChange = PaperEventHandler("NodeEditor-OnChange")
    val onEditorPan = PaperEventHandler("NodeEditor-OnPan")

    val logger by loggerForThis()

    // Window configuration
    override var title = "editor"
    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize, ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoCollapse, ImGuiWindowFlags.NoBringToFrontOnFocus,
        ImGuiWindowFlags.NoTitleBar, ImGuiWindowFlags.NoDecoration
    )

    var editorHovered = false
        private set

    // Popup state
    private val popupSelection = mutableListOf<DrawableIdElement>()
    private var currentContextMenuPopup: ContextMenuPopup? = null

    // Display
    val outputImageDisplay by lazy { ImageDisplay(visionGraph.previzManager.stream) }
    val streamWindow by lazy { ImageDisplayWindow(outputImageDisplay, isCloseable = false) }

    val streamWindowGroup by lazy {
        WindowGroup(
            streamWindow,
            direction = LayoutDirection.TOP_TO_BOTTOM,
            spacing = 30f
        )
    }

    // Shortcuts to VisionGraph collections
    val nodes get() = visionGraph.nodes
    val attributes get() = visionGraph.attributes
    val links get() = visionGraph.links
    val keys get() = keyManager.keys

    override fun onEnable() {
        ImNodes.createContext()

        initializeNodes()
        initializeButtons()
        registerOptions()
        registerShortcuts()
        setupPrevizHandlers()
        restoreEditorPanning()

        nodeList.enable()

        emptyStateWindow.enable()

        PaperEventHandler.batchOnce(GuidedTourWindow.onStart, onNodeInsert, onLink, onEditorPan) {
            emptyStateWindow.delete()
        }

        visionGraph.onDeserialization.once {
            if(hasUserNodes || hasUserLinks) {
                emptyStateWindow.delete()
            } else {
                emptyStateWindow.enable()
            }
        }
    }

    override fun delete() {
        nodeList.delete()
        super.delete()
    }

    private fun initializeNodes() {
        if (!::flagsNode.isInitialized) {
            flagsNode = FlagsNode()
        }
        flagsNode.enable()

        inputNode.enable()

        outputNode.streamId = outputImageDisplay.id
        outputNode.enable()
    }

    private fun initializeButtons() {
        nodeListButton = NodeListButton(nodeList)

        sourceCodeExportButton = SourceCodeExportButtonWindow(
            { size },
            visionGraph
        )

        playButton = PlayButtonWindow(visionGraph)

        optionsButton = OptionsButtonWindow(options)

        val group = WindowGroup(
            nodeListButton, optionsButton, sourceCodeExportButton, playButton,
            direction = LayoutDirection.RIGHT_TO_LEFT,
            spacing = 25f,
            sizingMode = SizingMode.GridFixed(ImVec2(90f, 95f))
        )

        group.enable()

        onDraw {
            group.position = ImVec2(
                size.x - 40f, size.y - nodeListButton.size.y - 40f
            )
        }
    }

    private fun registerOptions() {
        options[FontAwesomeIcons.InfoCircle] = Option("mis_about") {
            AboutModalWindow().enable()
        }

        options[FontAwesomeIcons.EarthAmericas] = Option("mis_changelanguage") {
            visionGraph.showWelcome(askLanguage = true)
        }

        options[FontAwesomeIcons.Book] = Option("mis_docs") {
            ConfirmationModalWindow("mis_opendocs", font = Font.find("calcutta-big")).apply {
                onConfirm {
                    openPaperVisionDocs()
                }
            }.enable()
        }
    }

    private fun registerShortcuts() {
        with(keyManager) {
            addShortcut(keys.Spacebar) {
                if (!isNodeFocused && !Window.isModalWindowOpen) {
                    nodeList.showList() // open the list when the spacebar is pressed
                }
            }
            addShortcut(keys.Escape) {
                if (nodeList.isOpen) {
                    nodeList.closeList() // close the list when the escape key is pressed
                }
            }

            addShortcut(keys.NativeLeftSuper, keys.Z, ::undo)
            addShortcut(keys.NativeRightSuper, keys.Z, ::undo)
            addShortcut(keys.NativeLeftSuper, keys.Y, ::redo)
            addShortcut(keys.NativeRightSuper, keys.Y, ::redo)
            addShortcut(keys.NativeLeftSuper, keys.X, ::cut)
            addShortcut(keys.NativeRightSuper, keys.X, ::cut)
            addShortcut(keys.NativeLeftSuper, keys.C, ::copy)
            addShortcut(keys.NativeRightSuper, keys.C, ::copy)
            addShortcut(keys.NativeLeftSuper, keys.V, ::paste)
            addShortcut(keys.NativeRightSuper, keys.V, ::paste)
        }
    }

    private fun setupPrevizHandlers() {
        visionGraph.previzManager.onStreamChange {
            outputImageDisplay.clientPrevizStream = visionGraph.previzManager.stream
        }

        visionGraph.previzManager.onPrevizStart {
            if(!streamWindowGroup.hasEnabled) {
                streamWindowGroup.enable()
            } else {
                streamWindowGroup.restore()
            }

            visionGraph.previzManager.onPrevizStop.once {
                streamWindowGroup.delete()
            }
        }
    }

    private fun restoreEditorPanning() {
        if (numFlags.containsKey("editorPanningX") && numFlags.containsKey("editorPanningY")) {
            editorPanning.x = numFlags["editorPanningX"]!!.toFloat()
            editorPanning.y = numFlags["editorPanningY"]!!.toFloat()
            logger.info("Restored editor panning from flags to $editorPanning")
        }
    }

    override fun drawContents() {
        ImNodes.editorContextSet(context)

        if (!flagsNode.isEnabled) {
            flagsNode.enable()
        }

        ImNodes.beginNodeEditor()
        ImNodes.miniMap(MINIMAP_SCALE, ImNodesMiniMapLocation.BottomLeft)

        for (node in nodes.inmutable) {
            node.editor = this
            node.draw()

            if (node.hasChanged()) {
                onEditorChange.run()
            }
        }

        for (link in links.inmutable) {
            link.draw()
        }

        editorHovered = ImNodes.isEditorHovered()

        ImNodes.endNodeEditor()

        updateEditorState()

        if (Window.isModalWindowOpen || nodeList.isNodesListOpen) {
            ImNodes.clearLinkSelection()
            ImNodes.clearNodeSelection()
        } else {
            handleInteractions()
        }

        ImNodes.getIO()

        updatePanning()
        updateRightClickMenuSelection()
        handleCreateLink()
        handleDeleteSelection()
    }

    private fun updateEditorState() {
        isNodeFocused = ImNodes.getHoveredNode() >= 0
    }

    private fun handleInteractions() {
        handleRightClickState()
        handleMouseClickPanning()
        handleKeyboardPanning()
        handleRightClickMenu()
    }

    private fun handleRightClickState() {
        val isFreeToMove = (!isNodeFocused || scrollTimer.millis <= SCROLL_COOLDOWN_MS) && editorHovered

        if (rightClickedWhileHoveringNode) {
            if (ImGui.isMouseReleased(ImGuiMouseButton.Right)) {
                rightClickedWhileHoveringNode = false
            }
        } else {
            rightClickedWhileHoveringNode =
                ImGui.isMouseClicked(ImGuiMouseButton.Right) && !isFreeToMove
        }

        if (!ImGui.isMouseDown(ImGuiMouseButton.Right) &&
            !ImGui.isMouseReleased(ImGuiMouseButton.Right)
        ) {
            rightClickMenuPopupTimer.reset()
        }
    }

    private fun handleRightClickMenu() {
        if (ImGui.isMouseReleased(ImGuiMouseButton.Right) &&
            rightClickMenuPopupTimer.millis <= RIGHT_CLICK_POPUP_THRESHOLD_MS &&
            justDeletedLinkTimer.millis >= LINK_DELETE_COOLDOWN_MS &&
            editorHovered
        ) {
            currentContextMenuPopup = ContextMenuPopup(
                nodeList,
                ::undo, ::redo, ::cut, ::copy, ::paste,
                popupSelection
            ).apply { enable() }

            logger.debug("Opening right click menu popup")
        }
    }

    private fun handleMouseClickPanning() {
        val shouldPan = (ImGui.isMouseDown(ImGuiMouseButton.Middle) ||
                (ImGui.isMouseDown(ImGuiMouseButton.Right) &&
                        rightClickMenuPopupTimer.millis >= 100 &&
                        (!rightClickedWhileHoveringNode || keyManager.pressing(keys.LeftControl)))) && editorHovered

        if (shouldPan) {
            editorPanning.x += (ImGui.getMousePosX() - prevMouseX)
            editorPanning.y += (ImGui.getMousePosY() - prevMouseY)
        }

        prevMouseX = ImGui.getMousePosX()
        prevMouseY = ImGui.getMousePosY()
    }

    private fun handleKeyboardPanning() {
        val isFreeToMove = (!isNodeFocused || scrollTimer.millis <= SCROLL_COOLDOWN_MS) && editorHovered
        if (!isFreeToMove) return

        var doingKeys = false

        // Arrow key panning
        if (keyManager.pressing(keys.ArrowLeft) && focus) {
            editorPanning.x += KEY_PAN_CONSTANT
            doingKeys = true
        } else if (keyManager.pressing(keys.ArrowRight) && focus) {
            editorPanning.x -= KEY_PAN_CONSTANT
            doingKeys = true
        }

        if (keyManager.pressing(keys.ArrowUp) && focus) {
            editorPanning.y += KEY_PAN_CONSTANT
            doingKeys = true
        } else if (keyManager.pressing(keys.ArrowDown) && focus) {
            editorPanning.y -= KEY_PAN_CONSTANT
            doingKeys = true
        }

        if (doingKeys) {
            scrollTimer.reset()
        } else {
            handleMouseWheelPanning()
        }
    }

    private fun handleMouseWheelPanning() {
        val plusPan = ImGui.getIO().mouseWheel * PAN_CONSTANT
        val plusPanX = ImGui.getIO().mouseWheelH * PAN_CONSTANT

        if (plusPan != 0f || plusPanX != 0f) {
            scrollTimer.reset()
        }

        if (keyManager.pressing(keys.LeftShift) || keyManager.pressing(keys.RightShift)) {
            editorPanning.x += plusPan
            editorPanning.y += plusPanX
        } else {
            editorPanning.y += plusPan
            editorPanning.x += plusPanX
        }
    }

    private fun updatePanning() {
        if (editorPanning.x != prevEditorPanning.x || editorPanning.y != prevEditorPanning.y) {
            ImNodes.editorContextResetPanning(editorPanning.x, editorPanning.y)
        } else {
            ImNodes.editorContextGetPanning(editorPanning)
        }

        // Store panning values in flags for serialization
        numFlags["editorPanningX"] = editorPanning.x.toDouble()
        numFlags["editorPanningY"] = editorPanning.y.toDouble()

        editorPanningDelta.x = editorPanning.x - prevEditorPanning.x
        editorPanningDelta.y = editorPanning.y - prevEditorPanning.y

        if (editorPanningDelta.x != 0f || editorPanningDelta.y != 0f) {
            onEditorPan.run()
        }

        prevEditorPanning.x = editorPanning.x
        prevEditorPanning.y = editorPanning.y
    }

    fun undo() {
        logger.info("undo | stack; size: ${visionGraph.actions.size}, pointer: ${visionGraph.actions.stackPointer}, peek: ${visionGraph.actions.peek()}")
        visionGraph.actions.peekAndPushback()?.undo()
        pasteCount = 0
    }

    fun redo() {
        logger.info("redo | stack; size: ${visionGraph.actions.size}, pointer: ${visionGraph.actions.stackPointer}, peek: ${visionGraph.actions.peek()}")
        visionGraph.actions.pushforwardIfNonNull()
        visionGraph.actions.peek()?.execute()
    }

    fun cut(overrideSelection: List<Node<*>>? = null) {
        val selectedNodesList = getSelectedNodesList(overrideSelection) ?: return

        cutting = true
        copy(overrideSelection)

        DeleteNodesAction(selectedNodesList).enable()
    }

    fun copy(overrideSelection: List<Node<*>>? = null) {
        val selectedNodesList = getSelectedNodesList(overrideSelection) ?: return

        pasteCount = 0
        clipboard = JsonCodec().encode(PaperVisionProject(selectedNodesList.toMutableList(), mutableListOf()))

        logger.debug("Clipboard content: $clipboard")
    }

    private fun getSelectedNodesList(overrideSelection: List<Node<*>>?): List<Node<*>>? {
        if (overrideSelection != null) {
            if (overrideSelection.isEmpty()) {
                clipboard = null
                return null
            }
            return overrideSelection
        }

        val selectedNodes = IntArray(ImNodes.numSelectedNodes())
        ImNodes.getSelectedNodes(selectedNodes)

        if (selectedNodes.isEmpty()) {
            clipboard = null
            return null
        }

        return try {
            selectedNodes.map { nodes[it]!! }.filter { it.isDeletable }
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    fun paste() {
        val clipboardContent = clipboard ?: return

        val nodes = JsonCodec().decode(clipboardContent, PaperVisionProject()).nodes

        updatePasteState()
        positionPastedNodes(nodes)
        CreateNodesAction(nodes).enable()

        if (cutting) {
            clipboard = null
            cutting = false
            pasteCount = 0
        } else {
            pasteCount++
        }
    }

    private fun updatePasteState() {
        if (pasteCount == 0) {
            pasteInitialMousePos = ImGui.getMousePos()
        }

        // Reset on mouse movement
        val currentMousePos = ImGui.getMousePos()
        if (pasteInitialMousePos.x != currentMousePos.x ||
            pasteInitialMousePos.y != currentMousePos.y
        ) {
            pasteCount = 0
            pasteInitialMousePos = currentMousePos
        }
    }

    private fun positionPastedNodes(nodes: List<Node<*>>) {
        nodes.forEach { it.forgetSerializedId() }

        if (nodes.size == 1) {
            positionSingleNode(nodes.first())
        } else {
            positionMultipleNodes(nodes)
        }
    }

    private fun positionSingleNode(node: Node<*>) {
        if (node is DrawNode<*>) {
            node.nextNodePosition = ImVec2(
                ImGui.getMousePosX() + pasteCount * PASTE_COUNT_OFFSET,
                ImGui.getMousePosY() + pasteCount * PASTE_COUNT_OFFSET
            )
        }
    }

    private fun positionMultipleNodes(nodes: List<Node<*>>) {
        val centerPos = calculateNodesCenter(nodes) ?: return
        val mousePos = ImGui.getMousePos()

        for (node in nodes) {
            if (node is DrawNode<*>) {
                val offset = node.nextNodePosition?.let {
                    ImVec2(centerPos.x - it.x, centerPos.y - it.y)
                } ?: ImVec2(0f, 0f)

                node.nextNodePosition = ImVec2(
                    offset.x + mousePos.x + pasteCount * PASTE_COUNT_OFFSET,
                    offset.y + mousePos.y + pasteCount * PASTE_COUNT_OFFSET
                )
            }
        }
    }

    private fun calculateNodesCenter(nodes: List<Node<*>>): ImVec2? {
        var totalX = 0f
        var totalY = 0f
        var count = 0

        for (node in nodes) {
            node.position.let {
                totalX += it.x
                totalY += it.y
                count++
            }
        }

        return if (count > 0) ImVec2(totalX / count, totalY / count) else null
    }

    fun addNode(nodeClazz: KClass<out Node<*>>): Node<*> {
        val instance = PaperNodeRegistry.instantiate(nodeClazz)
            ?: throw IllegalArgumentException(
                "Node $nodeClazz could not be instantiated, is it a valid Node subclass?"
            )

        return addNode(instance)
    }

    fun addNode(node: Node<*>): Node<*> {
        val action = CreateNodesAction(node)

        if (node.joinActionStack) {
            action.enable()
        } else {
            action.execute()
        }

        onNodeInsert.run()

        return node
    }

    fun startImageDisplayFor(attribute: Attribute): ImageDisplayNode {
        val window = ImageDisplayNode(ImageDisplay(visionGraph.previzManager.stream))

        visionGraph.previzManager.onStreamChange {
            window.imageDisplay.clientPrevizStream = visionGraph.previzManager.stream
        }

        window.pinToMouse = true
        window.enable()

        attribute.onDelete.once {
            window.delete()
        }

        visionGraph.onUpdate.once {
            val link = Link(attribute.id, window.input.id, isDeletable = false, shouldSerialize = false)
            link.enable()
        }

        return window
    }

    private fun updateRightClickMenuSelection() {
        if (currentContextMenuPopup?.isVisible == true) return

        popupSelection.clear()

        val nodeSelection = IntArray(ImNodes.numSelectedNodes())
        ImNodes.getSelectedNodes(nodeSelection)

        for (nodeId in nodeSelection) {
            if (nodeId >= 0) {
                nodes[nodeId]?.let { if(it.isDeletable) popupSelection.add(it) }
            }
        }

        val linkSelection = IntArray(ImNodes.numSelectedLinks())
        ImNodes.getSelectedLinks(linkSelection)

        for (linkId in linkSelection) {
            if (linkId >= 0) {
                links[linkId]?.let { if(it.isDeletable) popupSelection.add(it) }
            }
        }

        val hoveredNodeId = ImNodes.getHoveredNode()
        if (hoveredNodeId >= 0) {
            nodes[hoveredNodeId]?.let { if(it.isDeletable) popupSelection.add(it) }
        }

        val hoveredLinkId = ImNodes.getHoveredLink()
        if (hoveredLinkId >= 0) {
            links[hoveredLinkId]?.let { if(it.isDeletable) popupSelection.add(it) }
        }
    }

    private val startAttr = ImInt()
    private val endAttr = ImInt()

    private fun handleCreateLink() {
        if (!ImNodes.isLinkCreated(startAttr, endAttr)) return

        val start = startAttr.get()
        val end = endAttr.get()

        val startAttrib = attributes[start]
        val endAttrib = attributes[end]

        // If attributes are null, create link without validation
        if (startAttrib == null || endAttrib == null) {
            CreateLinkAction(Link(start, end)).enable()
            onLink.run()
            return
        }

        if (!validateLinkCreation(startAttrib, endAttrib)) return

        val input = if (startAttrib.mode == AttributeMode.INPUT) start else end
        val output = if (startAttrib.mode == AttributeMode.OUTPUT) start else end

        createValidatedLink(input, output)
    }

    private fun validateLinkCreation(startAttrib: Attribute, endAttrib: Attribute): Boolean {
        if (startAttrib.mode == endAttrib.mode) {
            return false // Same mode not allowed
        }

        val startAttribAcceptance = startAttrib.acceptLink(endAttrib)
        if(!startAttribAcceptance.accepted) {
            if(startAttribAcceptance is Attribute.LinkAcceptance.Reject) {
                TooltipPopup.showWarning(startAttribAcceptance.reason)
            }
            return false
        }

        val endAttribAcceptance = endAttrib.acceptLink(startAttrib)
        if(!endAttribAcceptance.accepted) {
            if(endAttribAcceptance is Attribute.LinkAcceptance.Reject) {
                TooltipPopup.showWarning(endAttribAcceptance.reason)
            }
            return false
        }

        if (startAttrib.parentNode == endAttrib.parentNode) {
            return false // No self-linking
        }

        return true
    }

    private fun createValidatedLink(input: Int, output: Int) {
        val inputAttrib = attributes[input]!!
        val outputAttrib = attributes[output]!!

        // Delete existing input links
        inputAttrib.links.toTypedArray().forEach { it.delete() }

        val link = Link(input, output)
        CreateLinkAction(link).enable()

        if (checkCycle(outputAttrib.parentNode, inputAttrib.parentNode)) {
            TooltipPopup.showWarning("err_couldntlink_recursion")
            link.delete()
        } else {
            visionGraph.onUpdate.once {
                link.triggerOnChange()
            }
            onLink.run()
        }
    }

    private fun checkCycle(from: Node<*>, to: Node<*>): Boolean {
        graph.clear()
        for(link in links) {
            val aNode = link.aAttrib?.parentNode ?: continue
            val bNode = link.bAttrib?.parentNode ?: continue

            val outputNode = if(link.aAttrib?.mode == AttributeMode.OUTPUT) aNode else bNode
            val inputNode = if(link.aAttrib?.mode == AttributeMode.INPUT) aNode else bNode

            graph.addEdge(outputNode.id, inputNode.id)
        }

        return graph.hasCycleIfAdded(from.id, to.id)
    }

    private fun handleDeleteSelection() {
        if (!keyManager.released(keys.Delete)) return

        deleteSelectedNodes()
        deleteSelectedLinks()
    }

    private fun deleteSelectedNodes() {
        if (ImNodes.numSelectedNodes() <= 0) return

        val selectedNodes = IntArray(ImNodes.numSelectedNodes())
        ImNodes.getSelectedNodes(selectedNodes)

        val nodesToDelete = mutableListOf<Node<*>>()

        for (nodeId in selectedNodes) {
            try {
                val node = nodes[nodeId]

                if (node?.joinActionStack == true) {
                    nodesToDelete.add(node)
                } else node?.delete()
            } catch (_: Exception) {
            }
        }

        if (nodesToDelete.isNotEmpty()) {
            DeleteNodesAction(nodesToDelete).enable()
        }
    }

    private fun deleteSelectedLinks() {
        if (ImNodes.numSelectedLinks() <= 0) return

        val selectedLinks = IntArray(ImNodes.numSelectedLinks())
          ImNodes.getSelectedLinks(selectedLinks)

        val linksToDelete = mutableListOf<Link>()

        for (linkId in selectedLinks) {
            links[linkId]?.run {
                if (isDeletable) {
                    linksToDelete.add(this)
                }
            }
        }

        if (linksToDelete.isNotEmpty()) {
            DeleteLinksAction(linksToDelete).enable()
        }
    }

}