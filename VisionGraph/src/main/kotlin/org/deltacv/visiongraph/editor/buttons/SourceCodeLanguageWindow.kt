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

package org.deltacv.visiongraph.editor.buttons

import imgui.ImGui
import imgui.ImVec2
import imgui.extension.texteditor.TextEditorLanguage
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiWindowFlags
import org.deltacv.visiongraph.VisionGraph
import org.deltacv.visiongraph.codegen.language.Language
import org.deltacv.visiongraph.codegen.language.interpreted.CPythonLanguage
import org.deltacv.visiongraph.codegen.language.jvm.JavaLanguage
import org.deltacv.visiongraph.editor.modal.CodeDisplayWindow
import org.deltacv.visiongraph.engine.client.message.AskProjectGenClassNameMessage
import org.deltacv.visiongraph.engine.client.response.StringResponse
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons
import org.deltacv.visiongraph.util.flags
import org.deltacv.visiongraph.util.loggerForThis

class SourceCodeLanguageWindow(
    val visionGraph: VisionGraph,
    val nodeEditorSizeSupplier: () -> ImVec2
) : Window() {

    companion object {
        const val SEPARATION_MULTIPLIER = 1.5f
    }

    override var title = "$[win_selectlanguage]"
    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoCollapse
    )

    val fontAwesomeBrandsBig = Font.find("font-awesome-brands-big")

    override val modal = ModalMode.Modal()

    val logger by loggerForThis()

    override fun drawContents() {
        fontAwesomeBrandsBig.push()
        ImGui.pushStyleColor(ImGuiCol.Button, 0)

        if (ImGui.button(FontAwesomeIcons.Brands.Java)) {
            openSourceCodeWindow(JavaLanguage)
            delete()
        }

        ImGui.sameLine()
        ImGui.indent(ImGui.getItemRectSizeX() * SEPARATION_MULTIPLIER)

        if (ImGui.button(FontAwesomeIcons.Brands.Python)) {
            openSourceCodeWindow(CPythonLanguage)
            delete()
        }

        ImGui.popFont()
        ImGui.popStyleColor()
    }

    private fun openSourceCodeWindow(language: Language) {
        fun openWindow(code: String?, name: String, language: Language) {
            if (code == null) {
                logger.warn("Code generation failed, cancelled opening source code window")
                return
            }

            CodeDisplayWindow(
                code, name, language,
                when (language) {
                    is CPythonLanguage -> TextEditorLanguage.Python()
                    else -> TextEditorLanguage.Cpp()
                },
                visionGraph.window
            ).apply {
                enable()
                size = ImVec2(nodeEditorSizeSupplier().x * 0.8f, nodeEditorSizeSupplier().y * 0.8f)
            }
        }

        if (visionGraph.engineClient.bridge.isConnected) {
            visionGraph.engineClient.sendMessage(AskProjectGenClassNameMessage().onResponseWith<StringResponse> { response ->
                visionGraph.onUpdate.once {
                    openWindow(visionGraph.codeGenManager.build(response.value, language), response.value, language)
                }
            })
        } else {
            visionGraph.onUpdate.once {
                openWindow(visionGraph.codeGenManager.build("Mack", language), "Mack", language)
            }
        }
    }
}



