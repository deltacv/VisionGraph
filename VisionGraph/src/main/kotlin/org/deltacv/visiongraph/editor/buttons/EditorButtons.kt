package org.deltacv.visiongraph.editor.buttons

import imgui.ImVec2
import org.deltacv.visiongraph.VisionGraph
import org.deltacv.visiongraph.editor.Option
import org.deltacv.visiongraph.engine.bridge.NoOpPaperVisionEngineBridge
import org.deltacv.visiongraph.gui.ButtonWindow
import org.deltacv.visiongraph.gui.ToastWindow
import org.deltacv.visiongraph.editor.palette.NodeList
import org.deltacv.visiongraph.gui.style.hexColor
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons

class NodeListButton(
    val nodeList: NodeList,
) : ButtonWindow(
    buttonText = FontAwesomeIcons.Plus,
    buttonFont = Font.find("font-awesome-big"),
    buttonTooltip = "mis_nodeslist_open",
    buttonTooltipFont = Font.find("calcutta-big")
) {
    init {
        onClick {
            if(!nodeList.isNodesListOpen) {
                nodeList.showList()
            } else {
                nodeList.closeList()
            }
        }
    }

    override fun preDrawContents() {
        buttonText = if (nodeList.isNodesListOpen) "X" else FontAwesomeIcons.Plus
        buttonTooltip = if (nodeList.isNodesListOpen) "mis_nodeslist_close" else "mis_nodeslist_open"

        super.preDrawContents()
    }
}

class OptionsButtonWindow(private val options: Map<String, Option>) : ButtonWindow(
    FontAwesomeIcons.Gear,
    Font.find("font-awesome-big")
) {
    init {
        onClick {
            OptionsWindow(options).enable()
        }
    }
}

class SourceCodeExportButtonWindow(
    private val nodeEditorSizeSupplier: () -> ImVec2,
    private val visionGraph: VisionGraph
) : ButtonWindow(
    FontAwesomeIcons.Code,
    Font.find("font-awesome-big")
) {
    init {
        onClick {
            SourceCodeLanguageWindow(visionGraph, nodeEditorSizeSupplier).enable()
        }
    }
}

class PlayButtonWindow(
    private val visionGraph: VisionGraph,
) : ButtonWindow(
    FontAwesomeIcons.Play,
    Font.find("font-awesome-big")
) {
    init {
        onClick {
            if(visionGraph.engineClient.bridge is NoOpPaperVisionEngineBridge) {
                ToastWindow("err_noop_engine", font = Font.find("calcutta-big")).enable()
            } else {
                if (!visionGraph.previzManager.previzRunning) {
                    visionGraph.startPrevizWithEngine()
                } else {
                    visionGraph.previzManager.stopPreviz()
                }
            }
        }
    }

    override var buttonText = FontAwesomeIcons.Play
        get() =
            if (visionGraph.previzManager.previzRunning) {
                FontAwesomeIcons.Stop
            } else FontAwesomeIcons.Play

    override val buttonColors get() = if (visionGraph.previzManager.previzRunning) {
        Colors(
            base = hexColor("#B94A48"),
            hover = hexColor("#D96C6A"),
            active = hexColor("#7A2F2D")
        )
    } else {
        Colors(
            base = hexColor("#4C8C6B"),   // muted green
            hover = hexColor("#66A885"),  // softer highlight
            active = hexColor("#3A6F55")  // darker press
        )
    }
}



