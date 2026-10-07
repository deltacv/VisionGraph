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

package org.deltacv.visiongraph

import imgui.ImFontGlyphRangesBuilder
import imgui.ImGui
import imgui.flag.ImGuiCond
import org.deltacv.mai18n.Language
import org.deltacv.mai18n.makeThreadTr
import org.deltacv.visiongraph.action.Action
import org.deltacv.visiongraph.action.RootAction
import org.deltacv.visiongraph.attribute.Attribute
import org.deltacv.visiongraph.codegen.CodeGenManager
import org.deltacv.visiongraph.engine.bridge.NoOpPaperVisionEngineBridge
import org.deltacv.visiongraph.engine.client.PaperVisionEngineClient
import org.deltacv.visiongraph.engine.client.message.PrevizAskNameMessage
import org.deltacv.visiongraph.engine.client.response.StringResponse
import org.deltacv.visiongraph.engine.previz.ClientPrevizManager
import org.deltacv.visiongraph.gui.Popup
import org.deltacv.visiongraph.gui.ToastWindow
import org.deltacv.visiongraph.gui.Window
import org.deltacv.visiongraph.gui.display.ImageDisplay
import org.deltacv.visiongraph.editor.NodeEditor
import org.deltacv.visiongraph.editor.modal.IntroModalWindow
import org.deltacv.visiongraph.gui.font.Font
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons
import org.deltacv.visiongraph.gui.font.FontManager
import org.deltacv.visiongraph.gui.font.defaultFontConfig
import org.deltacv.visiongraph.gui.style.CurrentStyles
import org.deltacv.visiongraph.gui.style.imnodes.ImNodesDarkStyle
import org.deltacv.visiongraph.id.Misc
import org.deltacv.visiongraph.id.container.*
import org.deltacv.visiongraph.io.KeyManager
import org.deltacv.visiongraph.io.TextureProcessorQueue
import org.deltacv.visiongraph.node.Link
import org.deltacv.visiongraph.node.Node
import org.deltacv.visiongraph.platform.*
import org.deltacv.visiongraph.serialization.v2.PaperVisionProject
import org.deltacv.visiongraph.serialization.v2.json.JsonCodec
import org.deltacv.visiongraph.util.event.PaperEventHandler
import org.deltacv.visiongraph.util.loggerForThis

class VisionGraph(
    private val platformSetupCallback: PlatformSetupCallback
) {

    companion object {
        var imnodesStyle
            set(value) {
                CurrentStyles.imnodesStyle = value
            }
            get() = CurrentStyles.imnodesStyle
        init {
            imnodesStyle = ImNodesDarkStyle
        }
    }

    private val logger by loggerForThis()

    lateinit var setup: PlatformSetup
        private set
    lateinit var window: PlatformWindow
        private set
    lateinit var textureFactory: PlatformTextureFactory
        private set
    lateinit var config: PlatformConfigManager
        private set

    val onInit            = PaperEventHandler("VisionGraph-OnInit")
    val onUpdate          = PaperEventHandler("VisionGraph-OnUpdate")
    val onDeserialization = PaperEventHandler("VisionGraph-OnDeserialization")

    // these depend on PlatformSetup so they are initialized later
    lateinit var textureProcessorQueue: TextureProcessorQueue
        private set
    lateinit var keyManager: KeyManager
        private set

    val codeGenManager  = CodeGenManager(this)
    val fontManager     = FontManager()
    lateinit var currentLanguage: Language
        private set

    val nodeEditor by lazy { NodeEditor(this, keyManager) }

    private val containers = IdContainerRegistry()

    val nodes: IdContainer<Node<*>>                                by containers { DenseIdContainer() }
    val attributes: IdContainer<Attribute>                         by containers { DenseIdContainer() }
    val links: IdContainer<Link>                                   by containers { DenseIdContainer() }
    val windows: IdContainer<Window>                               by containers { DenseIdContainer() }
    val textures: IdContainer<PlatformTexture>                     by containers { DenseIdContainer() }
    val textureProcessorQueues: IdContainer<TextureProcessorQueue> by containers { SingleIdContainer() }
    val fonts: IdContainer<Font>                                   by containers { SparseIdContainer() }
    val streamDisplays: IdContainer<ImageDisplay>                  by containers { DenseIdContainer() }
    val actions: StackIdContainer<Action>                          by containers { StackIdContainer() }
    val popups: IdContainer<Popup>                                 by containers { DenseIdContainer() }
    val misc: IdContainer<Misc>                                    by containers { SparseIdContainer() }

    lateinit var engineClient: PaperVisionEngineClient
    lateinit var previzManager: ClientPrevizManager

    lateinit var defaultFont: Font

    fun init() = containers.withContext {
        logger.info("-- Starting VisionGraph v${BuildInfo.VERSION_STRING} --\n\n${IntroModalWindow.iconLogo}\n")
        logger.info("Using the ${platformSetupCallback.name} platform")

        initPlatform()
        initLanguage()
        initEngine()
        initFonts()
        initUI()

        RootAction().enable()
        onInit.run()

        dumpStartupDiagnostics()
        logger.info("VisionGraph started")
    }

    private fun initPlatform() {
        setup = platformSetupCallback.setup()
        keyManager = KeyManager(setup.keys ?: error("Platform ${setup.name} must provide PlatformKeys"))
        window = setup.window ?: error("Platform ${setup.name} must provide a Window")
        textureFactory = setup.textureFactory ?: error("Platform ${setup.name} must provide a TextureFactory")
        config = setup.config
        config.load()

        window.title = "VisionGraph"
        window.icon = "/ico/ico_ezv.png"

        // serialize and log
        keyManager.addShortcut(keyManager.keys.NativeLeftSuper, keyManager.keys.Spacebar) {
            logger.info("-- Serialized project for Ctrl + Spacebar debugging --")
            logger.info(JsonCodec().encode(PaperVisionProject.from(this)))
        }

        // close the topmost modal window on escape
        keyManager.addShortcut(keyManager.keys.Escape) {
            for(window in windows.inmutable) {
                if(window.isEnabled && window.isModal) {
                    window.delete()
                    break
                }
            }
        }
    }

    private fun initLanguage() {
        try {
            changeLanguage(config.data.lang)
        } catch (_: Exception) {
            logger.warn("Configured language ${config.data.lang} is not available, defaulting")
        }
    }

    private fun initEngine() {
        textureProcessorQueue = TextureProcessorQueue(textureFactory)
        textureProcessorQueue.enable()

        engineClient = PaperVisionEngineClient(setup.engineBridge ?: NoOpPaperVisionEngineBridge)
        previzManager = ClientPrevizManager(
            160, 120, codeGenManager, engineClient
        )
        engineClient.connect()

        ImGui.getIO().apply {
            iniFilename = null
            logFilename = null
            fonts.setFreeTypeRenderer(false)
        }
    }

    private fun initFonts() {
        val rangesBuilder = ImFontGlyphRangesBuilder().apply {
            addRanges(ImGui.getIO().fonts.glyphRangesDefault)
            addRanges(FontAwesomeIcons._IconRange)
        }
        val ranges = rangesBuilder.buildRanges()

        defaultFont = font("calcutta", "/fonts/Calcutta-SemiBold.otf", 20f)
        font("calcutta-big", "/fonts/Calcutta-SemiBold.otf", 28f)

        font("jetbrains-mono", "/fonts/JetBrainsMono-Regular.ttf", 20f, ranges)
        font("jetbrains-mono-big", "/fonts/JetBrainsMono-Regular.ttf", 28f)

        fontManager.makeDefaultFont(20) // "default-20"
        fontManager.makeDefaultFont(12) // "default-12"

        // Pass the ranges to the icon fonts so FontAwesome glyphs are available
        font("font-awesome", "/fonts/icons/FontAwesome6-Free-Solid-900.otf", 16f, ranges)
        font("font-awesome-big", "/fonts/icons/FontAwesome6-Free-Solid-900.otf", 52f, ranges)
        font("font-awesome-brands", "/fonts/icons/FontAwesome6-Brands-Regular-400.otf", 16f, ranges)
        font("font-awesome-brands-big", "/fonts/icons/FontAwesome6-Brands-Regular-400.otf", 80f, ranges)
    }

    private fun initUI() {
        nodeEditor.enable()
    }

    private fun dumpStartupDiagnostics() {
        logger.debug("--  VisionGraph JVM startup diagnostics --")

        logger.debug("java.version = {}", System.getProperty("java.version"))
        logger.debug("java.home = {}", System.getProperty("java.home"))
        logger.debug("java.class.path = {}", System.getProperty("java.class.path"))
        logger.debug("java.library.path = {}", System.getProperty("java.library.path"))
        logger.debug("org.lwjgl.librarypath = {}", System.getProperty("org.lwjgl.librarypath"))
        logger.debug("java.io.tmpdir = {}", System.getProperty("java.io.tmpdir"))
        logger.debug("user.dir = {}", System.getProperty("user.dir"))

        // env vars
        val path = System.getenv("PATH") ?: "<null>"
        val temp = System.getenv("TEMP") ?: System.getenv("TMP") ?: "<null>"

        logger.debug("env PATH (truncated) = {}", path.take(800))
        logger.debug("env TEMP/TMP = {}", temp)

        logger.debug("=== end diagnostics ===")
    }

    fun firstProcess() {
        window.maximized = true

        onUpdate.once {
            if (setup.showWelcomeWindow) {
                showWelcome()
            } else {
                onDeserialization {
                    if (nodeEditor.flags.getOrElse("showWelcome") { true }) showWelcome()
                }
            }
            window.requestFocus()
        }
    }

    fun process() = containers.withContext {
        onUpdate.run()
        engineClient.process()

        ImGui.setNextWindowPos(0f, 0f, ImGuiCond.Always)
        val size = window.size
        ImGui.setNextWindowSize(size.x, size.y, ImGuiCond.Always)

        defaultFont.push()

        windows.forEach { it.draw() }
        popups.forEach { it.draw() }
        textureProcessorQueues.forEach { it.draw() }

        ImGui.popFont()

        keyManager.update()
        previzManager.update()
    }

    fun destroy() {
        logger.info("Shutting down VisionGraph...")

        config.save()

        engineClient.disconnect()

        textureProcessorQueue.delete()

        windows.reversed().forEach { it.delete() }
        popups.reversed().forEach { it.delete() }

        nodeEditor.delete()
    }

    fun changeLanguage(langCode: String) {
        currentLanguage = Language("/lang_pv.csv", langCode).apply { makeThreadTr() }
    }

    fun showWelcome(askLanguage: Boolean = setup.config.data.shouldAskForLang) {
        IntroModalWindow(
            nodeEditor, chooseLanguage = askLanguage
        ).apply {
            onDontShowAgain {
                nodeEditor.flags["showWelcome"] = false
                logger.info("showWelcome = ${nodeEditor.flags["showWelcome"]}")
            }
        }.enable()
    }

    fun startPrevizWithEngine() {
        engineClient.sendMessage(PrevizAskNameMessage().onResponseWith<StringResponse> { response ->
            logger.info("Engine responded with previz name ${response.value}")
            onUpdate.once {
                previzManager.startPreviz(response.value)
                window.title = "VisionGraph - ${response.value}"
            }
        })
    }

    fun clearToasts() {
        windows.filterIsInstance<ToastWindow>().forEach { it.delete() }
    }

    /** Helper to simplify font creation */
    private fun font(name: String, path: String, size: Float, ranges: ShortArray? = null) =
        fontManager.makeFont(name, path, defaultFontConfig(size), ranges)
}