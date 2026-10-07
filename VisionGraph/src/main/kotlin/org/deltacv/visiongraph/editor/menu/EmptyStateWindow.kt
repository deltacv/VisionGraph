package org.deltacv.visiongraph.editor.menu

import imgui.ImGui
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiWindowFlags
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.compose.dsl.immediateCompose
import org.deltacv.visiongraph.gui.compose.property.type.Text
import org.deltacv.visiongraph.editor.NodeEditor
import org.deltacv.visiongraph.gui.style.opacity
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.util.flags

class EmptyStateWindow(val editor: NodeEditor) : Window() {
    override var title = "empty state"

    override val windowFlags = flags(
        ImGuiWindowFlags.NoTitleBar,
        ImGuiWindowFlags.NoDecoration,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.AlwaysAutoResize,
        ImGuiWindowFlags.NoSavedSettings,
        ImGuiWindowFlags.NoFocusOnAppearing,
        ImGuiWindowFlags.NoNav
    )

    override fun preDrawContents() {
        ImGui.pushStyleColor(ImGuiCol.WindowBg, ImGui.getStyle().getColor(ImGuiCol.WindowBg).opacity(0.3f))
        super.preDrawContents()
    }

    override fun drawContents() {
        immediateCompose {
            val bigFont = Font.find("calcutta-big")

            alignedText(Text("mis_nodeeditor_emptystate1", bigFont), 0.5)
            alignedText(Text("mis_nodeeditor_emptystate2", bigFont), 0.5)

            newLine()

            alignedRow(0.5) {
                button("mis_guidedtour") {
                    GuidedTourWindow(editor).enable()
                    delete()
                }

                text("mis_lowercase_or")

                button("mis_addnode") {
                    editor.nodeList.showList()
                    delete()
                }
            }
        }

        centerWindow(editor.editorPanning)

        ImGui.popStyleColor()
    }
}



