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
import imgui.flag.ImGuiWindowFlags
import org.deltacv.mai18n.tr
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.editor.NodeEditor
import org.deltacv.visiongraph.editor.menu.GuidedTourWindow
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.util.ImGuiEx
import org.deltacv.visiongraph.io.resourceToString
import org.deltacv.visiongraph.util.event.PaperEventHandler
import org.deltacv.visiongraph.util.flags

class IntroModalWindow(
    val nodeEditor: NodeEditor,
    chooseLanguage: Boolean = nodeEditor.visionGraph.setup.config.data.shouldAskForLang
) : Window() {
    override var title = "win_welcome"

    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.AlwaysAutoResize,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoCollapse,
        ImGuiWindowFlags.HorizontalScrollbar
    )

    private val imguiFont = Font.find("default-12")
    private val monoFont = Font.find("jetbrains-mono-big")

    override val modal = ModalMode.Modal(closeOnOutsideClick = false)

    val onDontShowAgain = PaperEventHandler("IntroModalWindow-OnDontShowAgain")

    private var isFirstDraw = true

    private var choosingLanguage = chooseLanguage

    companion object {
        val icon = resourceToString("/ico/ico_ezv.txt")
        val iconLogo = resourceToString("/ico/ico_ezv_logo.txt")
    }

    override fun drawContents() {
        if(choosingLanguage) {
            monoFont.push()

            ImGui.newLine()

            val lines = tr("mis_welcomelanguage").split("\n")
            for(line in lines) {
                ImGuiEx.centeredText(line)
            }

            var width = 0f
            width += ImGui.calcTextSize(tr("lan_en")).x
            width += ImGui.getStyle().itemSpacing.x + 30f
            width += ImGui.calcTextSize(tr("lan_es")).x

            val alignment = ImGuiEx.alignForWidth(width, 0.5f)

            if(ImGui.button(tr("lan_en"))) {
                nodeEditor.visionGraph.changeLanguage("en")
                choosingLanguage = false
            }

            ImGui.sameLine(alignment + 30f + ImGui.calcTextSize(tr("lan_en")).x)

            if(ImGui.button(tr("lan_es"))) {
                nodeEditor.visionGraph.changeLanguage("es")
                choosingLanguage = false
            }

            nodeEditor.visionGraph.config.save(
                nodeEditor.visionGraph.config.data.copy(
                    lang = nodeEditor.visionGraph.currentLanguage.lang,
                    shouldAskForLang = false
                )
            )

            ImGui.popFont()

            centerWindow()
        } else {
            ImGui.newLine()
            ImGui.newLine()

            imguiFont.push()
            ImGuiEx.centeredText(icon)
            ImGui.popFont()

            ImGui.newLine()
            ImGui.newLine()

            monoFont.push()

            ImGuiEx.centeredText("mis_welcome1")
            ImGuiEx.centeredText("mis_welcome2")
            ImGuiEx.centeredText("mis_welcome3")
            ImGuiEx.centeredText("mis_welcome4")

            ImGui.newLine()

            ImGuiEx.centeredText("mis_welcome5")

            ImGui.newLine()

            var width = 0f
            width += ImGui.calcTextSize(tr("mis_gotit")).x
            width += ImGui.getStyle().itemSpacing.x
            width += ImGui.calcTextSize(tr("mis_dontshow_again")).x
            width += ImGui.getStyle().itemSpacing.x
            width += ImGui.calcTextSize(tr("mis_guidedtour")).x

            ImGuiEx.alignForWidth(width, 0.5f)

            if (ImGui.button(tr("mis_gotit"))) {
                delete()
            }

            ImGui.sameLine()

            if (ImGui.button(tr("mis_dontshow_again"))) {
                onDontShowAgain.run()
                delete()
            }

            ImGui.sameLine()

            if (ImGui.button(tr("mis_guidedtour"))) {
                GuidedTourWindow(nodeEditor).enable()
                delete()
            }

            ImGui.popFont()

            ImGui.sameLine()

            centerWindow()

            if (!isFirstDraw) {
                size = ImVec2(ImGui.getMainViewport().sizeX * 0.6f, ImGui.getWindowSizeY())
            }

            isFirstDraw = false
        }
    }
}



