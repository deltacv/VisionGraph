package org.deltacv.visiongraph.editor.modal

import imgui.ImGui
import imgui.ImVec2
import imgui.flag.ImGuiWindowFlags
import org.deltacv.visiongraph.BuildInfo
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.util.ARCH
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.util.ImGuiEx
import org.deltacv.visiongraph.gui.util.OS
import org.deltacv.visiongraph.gui.util.getMemoryUsageMB
import org.deltacv.visiongraph.id.container.IdContext
import org.deltacv.visiongraph.io.resourceToString
import org.deltacv.visiongraph.util.flags
import org.deltacv.mai18n.tr

class AboutModalWindow : Window() {
    override var title = "win_about"

    val imguiFont by Font.findLazy("default-12")
    val monoFont by Font.findLazy("jetbrains-mono-big")

    override val windowFlags = flags(
        ImGuiWindowFlags.NoResize,
        ImGuiWindowFlags.AlwaysAutoResize,
        ImGuiWindowFlags.NoMove,
        ImGuiWindowFlags.NoCollapse,
        ImGuiWindowFlags.HorizontalScrollbar
    )

    override val modal = ModalMode.Modal()

    private var isFirstDraw = true

    companion object {
        val icon = resourceToString("/ico/ico_ezv.txt")
        val iconLogo = resourceToString("/ico/ico_ezv_logo.txt")
    }

    override fun drawContents() {

        ImGui.newLine()
        ImGui.newLine()

        imguiFont.push()
        ImGuiEx.centeredText(icon)
        ImGui.popFont()

        ImGui.newLine()
        ImGui.newLine()

        monoFont.push()

        ImGuiEx.centeredText(tr("win_about_version", BuildInfo.VERSION_STRING, BuildInfo.BUILD_DATE))

        if(BuildInfo.IS_DEV) {
            ImGuiEx.centeredText(tr("win_about_devbuild"))
        } else {
            ImGuiEx.centeredText(tr("win_about_stablebuild"))
        }

        ImGui.newLine()

        val OS_VERSION = System.getProperty("os.version")

        var elementCount = 0
        for(container in IdContext.local.all()) {
            elementCount += container.inmutable.size
        }

        ImGuiEx.centeredText(tr("win_about_systemdetails", OS, OS_VERSION, ARCH, System.getProperty("java.version"), System.getProperty("java.vendor")))
        ImGuiEx.centeredText(tr("win_about_memusage", getMemoryUsageMB(), elementCount))

        ImGui.newLine()

        var width = ImGui.calcTextSize(tr("mis_gotit")).x
        alignForWidth(width, 0.5f)

        if (ImGui.button(tr("mis_gotit"))) {
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

    private fun alignForWidth(width: Float, alignment: Float): Float {
        val windowSize = ImGui.getWindowSize()
        val pos = windowSize.x / 2 - width / 2
        ImGui.sameLine(pos + alignment)

        return pos + alignment
    }
}



