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

package org.deltacv.visiongraph.editor.modal

import imgui.ImGui
import imgui.ImVec2
import imgui.extension.texteditor.TextEditor
import imgui.extension.texteditor.TextEditorLanguage
import imgui.flag.ImGuiWindowFlags
import org.deltacv.mai18n.tr
import org.deltacv.visiongraph.codegen.language.Language
import org.deltacv.visiongraph.gui.TooltipPopup
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.platform.PlatformFileFilter
import org.deltacv.visiongraph.platform.PlatformWindow
import org.deltacv.visiongraph.util.flags
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CodeDisplayWindow(
    val code: String,
    val name: String,
    val codeGenLanguage: Language,
    val editorLanguage: TextEditorLanguage,
    val platformWindow: PlatformWindow
) : Window() {
    override var title = "Code"

    val codeFont = Font.find("jetbrains-mono-big")
    val buttonsFont = Font.find("calcutta-big")

    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.NoCollapse,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoScrollbar,
        ImGuiWindowFlags.NoScrollWithMouse
    )

    override val modal = ModalMode.Modal(closeOnOutsideClick = false)

    val EDITOR = TextEditor()

    override fun onEnable() {
        focus = true

        EDITOR.language = editorLanguage
        EDITOR.text = code
        EDITOR.isReadOnlyEnabled = true
    }

    override fun preDrawContents() {
        size = ImVec2(ImGui.getMainViewport().size.x * 0.7f, ImGui.getMainViewport().size.y * 0.8f)
    }

    override fun drawContents() {
        codeFont.push()

        ImGui.beginChild(
            "${titleId}Child###$id", ImVec2(size.x, size.y * 0.85f),
            false, flags(ImGuiWindowFlags.HorizontalScrollbar)
        )

        EDITOR.render("${titleId}Code###$id")

        ImGui.endChild()

        codeFont.let {
            ImGui.popFont()
        }

        buttonsFont.push()

        if(ImGui.button("Copy Code")) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(
                StringSelection(EDITOR.text), null
            )

            TooltipPopup(
                tr("mis_codecopied"),
                3.0,
                font = Font.find("default-20")
            ).enable()
        }

        ImGui.sameLine()

        if(ImGui.button("Export to File")) {
            platformWindow.saveFileDialog(
                code.toByteArray(),
                "${name}.${codeGenLanguage.sourceFileExtension}",
                PlatformFileFilter("Source File", listOf(codeGenLanguage.sourceFileExtension))
            )
        }

        buttonsFont.let {
            ImGui.popFont()
        }
    }
}



