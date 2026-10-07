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

package org.deltacv.visiongraph.editor.menu

import imgui.ImGui
import imgui.ImVec2
import imgui.flag.ImGuiWindowFlags
import org.deltacv.mai18n.tr
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.editor.NodeEditor
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons
import org.deltacv.visiongraph.node.vision.featuredet.FindContoursNode
import org.deltacv.visiongraph.node.vision.imageproc.ThresholdNode
import org.deltacv.visiongraph.node.vision.overlay.DrawContoursNode
import org.deltacv.visiongraph.util.event.PaperEventHandler
import org.deltacv.visiongraph.util.flags

val Next: GuidedTourWindow.() -> Boolean = {
    ImGui.button(tr("mis_next"))
}
val Close: GuidedTourStage = {
    if (ImGui.button(tr("mis_cancel"))) {
        delete()
    }
}

val IntroStage: GuidedTourStage = {
    centerWindow()

    ImGui.text(tr("mis_guidedtour_1"))
    ImGui.text(tr("mis_guidedtour_2"))

    if (Next()) {
        currentStage = Stage1
    }

    ImGui.sameLine()
    Close()
}

val Stage1: GuidedTourStage = {
    position = ImVec2(
        nodeEditor.inputNode.screenPosition.x,
        nodeEditor.inputNode.screenPosition.y + nodeEditor.inputNode.size.y
    )

    ImGui.text(tr("mis_guidedtour_3"))
    ImGui.text(tr("mis_guidedtour_4"))

    if (Next()) {
        currentStage = Stage2
    }

    ImGui.sameLine()
    Close()
}

val Stage2: GuidedTourStage = {
    position = ImVec2(
        nodeEditor.outputNode.screenPosition.x - size.x + nodeEditor.outputNode.size.x,
        nodeEditor.outputNode.screenPosition.y + (nodeEditor.outputNode.size.y)
    )

    ImGui.text(tr("mis_guidedtour_5"))
    ImGui.text(tr("mis_guidedtour_6"))

    if (Next()) {
        currentStage = Stage3
    }

    ImGui.sameLine()
    Close()
}

val Stage3: GuidedTourStage = {
    val floatingButton = nodeEditor.visionGraph.nodeEditor.nodeListButton

    position = ImVec2(
        floatingButton.position.x - size.x + floatingButton.size.x,
        floatingButton.position.y - size.y
    )

    ImGui.text(tr("mis_guidedtour_7"))

    ImGui.sameLine()

    ImGui.setCursorPosY(ImGui.getCursorPosY() + 5)
    Font.find("font-awesome").push()
    ImGui.text(FontAwesomeIcons.ArrowDown)
    ImGui.popFont()

    if (nodeEditor.visionGraph.nodeEditor.nodeList.isNodesListOpen) {
        currentStage = Stage4
    }
}

val Stage4: GuidedTourStage = {
    // bottom center
    position = ImVec2(
       (nodeEditor.visionGraph.nodeEditor.nodeList.size.x / 2) - (size.x / 2),
        nodeEditor.visionGraph.nodeEditor.nodeList.size.y - size.y - 50
    )

    focus = true

    if (!nodeEditor.visionGraph.nodeEditor.nodeList.isNodesListOpen) {
        if (nodeEditor.visionGraph.nodes.find { it is ThresholdNode } != null) {
            currentStage = Stage5
        } else {
            ImGui.text(tr("mis_guidedtour_9"))
            Close()
        }
    } else {
        ImGui.text(tr("mis_guidedtour_8_0"))
        ImGui.text(tr("mis_guidedtour_8_1"))
        ImGui.text("")
        ImGui.text(tr("mis_guidedtour_8_2"))

        Close()

        nodeEditor.nodeList.highlight(ThresholdNode::class)
    }
}

val Stage5: GuidedTourStage = {
    focus = false
    val node = nodeEditor.visionGraph.nodes.find { it is ThresholdNode } as ThresholdNode?

    if (node == null) {
        ImGui.text(tr("mis_guidedtour_9"))
        Close()
    } else {
        centerWindow()

        ImGui.text(tr("mis_guidedtour_10"))
        ImGui.text(tr("mis_guidedtour_11_0"))
        ImGui.text("")
        ImGui.text(tr("mis_guidedtour_11_1"))
        ImGui.text(tr("mis_guidedtour_11_2"))

        Close()

        position = ImVec2(
            node.input.position.x + node.size.x,
            node.input.position.y
        )

        if (node.input.hasLink) {
            currentStage = Stage6
        }
    }
}

val Stage6: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is ThresholdNode } as ThresholdNode?

    if (node == null) {
        ImGui.text(tr("mis_guidedtour_9"))
        Close()
    } else {
        position = ImVec2(
            node.input.position.x + node.size.x,
            node.input.position.y
        )

        ImGui.text(tr("mis_guidedtour_12"))

        Close()

        if (nodeEditor.visionGraph.nodeEditor.nodeList.isNodesListOpen) {
            currentStage = Stage7
        }
    }
}

val Stage7: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is FindContoursNode } as FindContoursNode?

    if (node == null) {
        ImGui.text(tr("mis_guidedtour_15"))
        Close()

        nodeEditor.visionGraph.nodeEditor.nodeList.highlight(FindContoursNode::class)
    } else {
        centerWindow()

        ImGui.text(tr("mis_guidedtour_13"))
        ImGui.text(tr("mis_guidedtour_14"))

        Close()

        position = ImVec2(
            node.inputMat.position.x + node.size.x,
            node.inputMat.position.y
        )

        if (node.inputMat.hasLink) {
            currentStage = Stage8
        }
    }
}

val Stage8: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is FindContoursNode } as FindContoursNode?

    if (node == null) {
        currentStage = Stage6
    } else {
        position = ImVec2(
            node.inputMat.position.x + node.size.x,
            node.inputMat.position.y
        )

        ImGui.text(tr("mis_guidedtour_16"))
        ImGui.text(tr("mis_guidedtour_17"))

        Close()

        if (nodeEditor.visionGraph.nodeEditor.nodeList.isNodesListOpen) {
            currentStage = Stage9
        }
    }
}

val Stage9: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is DrawContoursNode } as DrawContoursNode?

    if (node == null) {
        ImGui.text(tr("mis_guidedtour_18"))
        Close()

        nodeEditor.visionGraph.nodeEditor.nodeList.highlight(DrawContoursNode::class)
    } else {
        centerWindow()

        ImGui.text(tr("mis_guidedtour_19"))
        ImGui.text(tr("mis_guidedtour_20"))
        ImGui.text(tr("mis_guidedtour_21"))

        Close()

        position = ImVec2(
            node.inputMat.position.x + node.size.x,
            node.inputMat.position.y
        )

        if (node.contours.hasLink) {
            currentStage = Stage10
        }
    }
}

val Stage10: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is DrawContoursNode } as DrawContoursNode?

    nodeEditor.visionGraph.nodeEditor.nodeList.clearHighlight()

    if (node == null) {
        currentStage = Stage6
    } else {
        centerWindow()

        ImGui.text(tr("mis_guidedtour_22"))
        ImGui.text(tr("mis_guidedtour_23"))

        Close()

        position = ImVec2(
            node.inputMat.position.x + node.size.x,
            node.inputMat.position.y
        )

        if (node.inputMat.hasLink) {
            currentStage = Stage11
        }
    }
}

val Stage11: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is DrawContoursNode } as DrawContoursNode?

    if (node == null) {
        currentStage = Stage6
    } else {
        centerWindow()

        position = ImVec2(
            node.inputMat.position.x - size.x,
            node.contours.position.y
        )

        ImGui.text(tr("mis_guidedtour_24"))
        ImGui.text(tr("mis_guidedtour_25"))

        if(node.outputMat.hasLink) {
            currentStage = Stage12
        }

        Close()
    }
}

val Stage12: GuidedTourStage = {
    val floatingButton = nodeEditor.playButton

    position = ImVec2(
        floatingButton.position.x - size.x + floatingButton.size.x,
        floatingButton.position.y - size.y
    )

    ImGui.text(tr("mis_guidedtour_26"))

    ImGui.text(tr("mis_guidedtour_27"))
    ImGui.sameLine()
    ImGui.setCursorPosY(ImGui.getCursorPosY() + 5)
    Font.find("font-awesome").push()
    ImGui.text(FontAwesomeIcons.ArrowDown)
    ImGui.popFont()

    if(floatingButton.isPressed) {
        currentStage = Stage13
    }
}

val Stage13: GuidedTourStage = {
    val node = nodeEditor.visionGraph.nodes.find { it is ThresholdNode } as ThresholdNode?

    if(node == null) {
        currentStage = Stage14
    } else {
        position = ImVec2(
            node.output.position.x + node.size.x + 5,
            node.output.position.y
        )

        ImGui.text(tr("mis_guidedtour_28"))
        ImGui.text(tr("mis_guidedtour_29"))
        ImGui.text(tr("mis_guidedtour_30"))

        if(Next() || node.output.isPrevizEnabled) {
            currentStage = Stage14
        }
    }
}

val Stage14: GuidedTourStage = {
    val floatingButton = nodeEditor.sourceCodeExportButton

    position = ImVec2(
        floatingButton.position.x - size.x + floatingButton.size.x,
        floatingButton.position.y - size.y
    )

    ImGui.text(tr("mis_guidedtour_31"))
    ImGui.text(tr("mis_guidedtour_32"))

    ImGui.text(tr("mis_guidedtour_33"))
    ImGui.sameLine()
    ImGui.setCursorPosY(ImGui.getCursorPosY() + 5)
    Font.find("font-awesome").push()
    ImGui.text(FontAwesomeIcons.ArrowDown)
    ImGui.popFont()

    if(floatingButton.isPressed) {
        delete()
    }
}

class GuidedTourWindow(
    val nodeEditor: NodeEditor
) : Window() {

    companion object {
        val onStart = PaperEventHandler("GuidedTourWindow-OnStart")
    }

    val font = Font.find("calcutta-big")

    override var title = "$[mis_guidedtour]"

    var currentStage: GuidedTourStage = IntroStage

    private var previousStage: GuidedTourStage? = null

    var firstStageDraw: Boolean = true
        private set

    override val windowFlags = flags(
        ImGuiWindowFlags.NoCollapse,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.AlwaysAutoResize,
        ImGuiWindowFlags.NoResize
    )

    override fun onEnable() {
        onStart.run()
    }

    override fun drawContents() {
        if(nodeEditor.visionGraph.nodes.size > 4 && currentStage == IntroStage) {
            centerWindow()
            ImGui.text(tr("mis_guidedtour_notavailable_1"))
            ImGui.text(tr("mis_guidedtour_notavailable_2"))

            Close()
            return
        }

        previousStage = currentStage

        font.push()
        currentStage()
        ImGui.popFont()

        firstStageDraw = currentStage != previousStage
    }

    override fun delete() {
        super.delete()
        nodeEditor.visionGraph.nodeEditor.nodeList.clearHighlight()
    }
}

typealias GuidedTourStage = GuidedTourWindow.() -> Unit



