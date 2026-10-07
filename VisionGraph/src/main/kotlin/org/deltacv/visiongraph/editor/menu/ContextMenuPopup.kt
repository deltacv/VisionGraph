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
import imgui.flag.ImGuiCol
import imgui.flag.ImGuiWindowFlags
import org.deltacv.mai18n.tr
import org.deltacv.visiongraph.action.editor.DeleteLinksAction
import org.deltacv.visiongraph.action.editor.DeleteNodesAction
import org.deltacv.visiongraph.gui.Popup
import org.deltacv.visiongraph.editor.palette.NodeList
import org.deltacv.visiongraph.id.DrawableIdElement
import org.deltacv.visiongraph.node.Link
import org.deltacv.visiongraph.node.Node
import org.deltacv.visiongraph.util.flags

class ContextMenuPopup(
    val nodeList: NodeList,
    val undo: () -> Unit,
    val redo: () -> Unit,
    val cut: (List<Node<*>>) -> Unit,
    val copy: (List<Node<*>>) -> Unit,
    val paste: () -> Unit,
    val selection: List<DrawableIdElement>
) : Popup() {

    override val title = "right click menu"
    override val flags = flags(
        ImGuiWindowFlags.NoTitleBar,
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.Popup
    )

    override fun drawContents() {
        ImGui.pushStyleColor(ImGuiCol.Button, 0)

        if(selection.isNotEmpty() && !selection.all { it is Link }) {
            if(ImGui.button(tr("mis_cut"))) {
                cut(selection.filterIsInstance<Node<*>>().map { it })
                ImGui.closeCurrentPopup()
            }

            if(ImGui.button(tr("mis_copy"))) {
                copy(selection.filterIsInstance<Node<*>>().map { it })
                ImGui.closeCurrentPopup()
            }
        }

        if(ImGui.button(tr("mis_paste"))) {
            paste()
            ImGui.closeCurrentPopup()
        }

        if (selection.isNotEmpty()) {
            if (ImGui.button(tr("mis_delete"))) {
                selection.find { it is Node<*> }?.let {
                    val nodesToDelete = selection.filterIsInstance<Node<*>>()
                    DeleteNodesAction(nodesToDelete).enable()
                }

                selection.find { it is Link }?.let {
                    val linksToDelete = selection.filterIsInstance<Link>()
                    DeleteLinksAction(linksToDelete).enable()
                }

                ImGui.closeCurrentPopup()
            }
        }

        ImGui.separator()

        if (ImGui.button(tr("mis_undo"))) {
            undo()
            ImGui.closeCurrentPopup()
        }

        if (ImGui.button(tr("mis_redo"))) {
            redo()
            ImGui.closeCurrentPopup()
        }

        ImGui.separator()

        if (ImGui.button(tr("mis_addnode"))) {
            nodeList.showList()
        }

        ImGui.popStyleColor()
    }
}



