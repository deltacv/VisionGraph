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

package org.deltacv.visiongraph.plugin

import org.deltacv.eocvsim.plugin.EOCVSimPlugin
import org.deltacv.eocvsim.plugin.api.InputSourceApi
import org.deltacv.eocvsim.plugin.api.PipelineManagerApi
import org.deltacv.eocvsim.plugin.api.TunableFieldApi
import org.deltacv.eocvsim.plugin.loader.FilePluginLoader
import org.deltacv.eocvsim.plugin.loader.PluginSource
import org.deltacv.eocvsim.virtualreflect.VirtualField
import org.deltacv.visiongraph.engine.client.message.*
import org.deltacv.visiongraph.engine.client.response.ErrorResponse
import org.deltacv.visiongraph.engine.client.response.OkResponse
import org.deltacv.visiongraph.engine.client.response.PrevizStatisticsResponse
import org.deltacv.visiongraph.engine.client.response.StringResponse
import org.deltacv.visiongraph.plugin.previz.VisionGraphDefaultPipeline
import org.deltacv.visiongraph.plugin.gui.eocvsim.PaperVisionTabPanel
import org.deltacv.visiongraph.plugin.gui.eocvsim.dialog.VisionGraphDialogFactory
import org.deltacv.visiongraph.plugin.previz.EOCVSimEngineImageStreamer
import org.deltacv.visiongraph.plugin.previz.EOCVSimPrevizSession
import org.deltacv.visiongraph.plugin.engine.message.*
import org.deltacv.visiongraph.plugin.engine.message.response.InputSourcesListResponse
import org.deltacv.visiongraph.plugin.project.VisionGraphProjectManager
import org.deltacv.visiongraph.util.replaceLast
import org.deltacv.visiongraph.util.toValidIdentifier
import org.opencv.core.Size
import java.io.File
import java.util.*
import javax.swing.JMenu
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * Main entry point for the VisionGraph plugin.
 * Specified in the plugin.toml file.
 */
class VisionGraphPlugin : EOCVSimPlugin() {

    val engine = VisionGraphProcessRunner.paperVisionEngine

    var isRunningPreviewPipeline = false

    var currentPrevizSession: EOCVSimPrevizSession? = null

    private val tunableFieldCache = WeakHashMap<VirtualField, TunableFieldApi>()

    val visionGraphProjectManager by lazy {
        VisionGraphProjectManager(
            classpath.joinToString(File.pathSeparator),
            fileSystem,
            engine,
            this,
            eocvSimApi
        )
    }

    val paperVisionTabPanel by lazy { PaperVisionTabPanel(this) }

    override fun onLoad() {
        visionGraphProjectManager.init()

        eocvSimApi.visualizerApi.creationHook.once {
            val sidebar = eocvSimApi.visualizerApi.sidebarApi

            sidebar.addTab(paperVisionTabPanel)

            sidebar.tabChangeHook {
                isRunningPreviewPipeline = false
                switchToDefaultPipelineIfNecessary()
            }

            val fileNewMenu = eocvSimApi.visualizerApi.topMenuBarApi.fileMenuApi.findSubMenuByTitle("New")
            fileNewMenu?.addSeparator()

            val fileNewVisionGraphMenu = JMenu("VisionGraph")

            val fileNewPaperVisionProject = JMenuItem("New Project")
            fileNewPaperVisionProject.addActionListener {
                visionGraphProjectManager.newProjectAsk(eocvSimApi.visualizerApi.frame!!)
            }

            fileNewVisionGraphMenu.add(fileNewPaperVisionProject)

            val filePaperVisionImport = JMenuItem("Import...")
            filePaperVisionImport.addActionListener {
                visionGraphProjectManager.importProjectAsk(eocvSimApi.visualizerApi.frame!!)
            }

            fileNewVisionGraphMenu.add(filePaperVisionImport)

            fileNewMenu?.addMenuItem(fileNewVisionGraphMenu)
        }

        eocvSimApi.mainLoopHook.once(this::recoverProjects)
        VisionGraphProcessRunner.onPaperVisionExitError.once(this::recoverProjects)

        eocvSimApi.pipelineManagerApi.onPipelineChangeHook {
            switchToDefaultPipelineIfNecessary()
        }

        VisionGraphProcessRunner.onPaperVisionStart {
            // abort papervision pipeline
            switchToDefaultPipelineIfNecessary()

            eocvSimApi.mainLoopHook.once {
                eocvSimApi.pipelineManagerApi.changePipeline(0, force = true)
            }
        }

        VisionGraphProcessRunner.onPaperVisionExit {
            switchToDefaultPipelineIfNecessary()

            currentPrevizSession?.stopPreviz()
            currentPrevizSession = null

            SwingUtilities.invokeLater {
                eocvSimApi.visualizerApi.viewportApi.activate()
            }
        }
    }

    override fun onEnable() {
        eocvSimApi.pipelineManagerApi.addPipelineClass(
            VisionGraphDefaultPipeline::class.java,
            PipelineManagerApi.PipelineSource.CLASSPATH,
            hidden = true
        )

        engine.setMessageHandlerOf<TunerChangeValueMessage> {
            eocvSimApi.mainLoopHook.once {
                val field = currentPrevizSession?.latestVirtualReflect?.getLabeledField(message.label)

                if (field != null) {
                    val tunableField = tunableFieldOf(field) ?: return@once
                    when(message.value) {
                        is TunerValue.ListValue -> for ((i, e) in (message.value as TunerValue.ListValue).asAny().withIndex()) {
                            tunableField.setFieldValue(i, e ?: continue)
                        }
                        else -> tunableField.setFieldValue(0, message.value.asAny() ?: return@once)
                    }
                }

                respond(OkResponse())
            }
        }

        engine.setMessageHandlerOf<GetInputSourcesMessage> {
            eocvSimApi.mainLoopHook.once {
                respond(
                    InputSourcesListResponse(inputSourcesToData())
                )
            }
        }

        engine.setMessageHandlerOf<GetCurrentInputSourceMessage> {
            eocvSimApi.mainLoopHook.once {
                respond(StringResponse(eocvSimApi.inputSourceManagerApi.currentSource?.name ?: ""))
            }
        }

        engine.setMessageHandlerOf<SetInputSourceMessage> {
            eocvSimApi.mainLoopHook.once {
                eocvSimApi.inputSourceManagerApi.setInputSource(message.inputSource)
                respond(OkResponse())
            }
        }

        engine.setMessageHandlerOf<OpenCreateInputSourceMessage> {
            eocvSimApi.mainLoopHook.once {
                eocvSimApi.visualizerApi.dialogFactoryApi.createSourceDialog(message.sourceType.toApi())

                respond(OkResponse())
            }
        }

        engine.setMessageHandlerOf<InputSourceListChangeListenerMessage> {
            val currentSourceAmount = eocvSimApi.inputSourceManagerApi.allSources.size

            eocvSimApi.mainLoopHook {
                if (eocvSimApi.inputSourceManagerApi.allSources.size > currentSourceAmount) {
                    respond(InputSourcesListResponse(inputSourcesToData()))
                }
            }
        }

        engine.setMessageHandlerOf<PrevizAskNameMessage> {
            respond(
                StringResponse(
                    visionGraphProjectManager.currentProject?.name?.replaceLast(".paperproj", "")?.toValidIdentifier()
                        ?: "Mack"
                )
            )
        }

        engine.setMessageHandlerOf<AskProjectGenClassNameMessage> {
            respond(
                StringResponse(
                    visionGraphProjectManager.currentProject?.name?.replaceLast(".paperproj", "")?.toValidIdentifier()
                        ?: "Mack"
                )
            )
        }

        engine.setMessageHandlerOf<PrevizStartMessage> {
            eocvSimApi.mainLoopHook.once {
                if (currentPrevizSession != null) {
                    logger.warn("Stopping current previz session '${currentPrevizSession?.sessionName}' to start new one")
                    logger.warn("It was not stopped beforehand, make sure to stop previz sessions timely")

                    currentPrevizSession?.stopPreviz()
                }

                val streamer = EOCVSimEngineImageStreamer(
                    previzNameProvider = { message.previzName },
                    Size(
                        message.streamWidth.toDouble(),
                        message.streamHeight.toDouble()
                    ),
                    engine
                )

                currentPrevizSession = EOCVSimPrevizSession(
                    message.previzName,
                    eocvSimApi,
                    visionGraphProjectManager,
                    streamer,
                    message.sourceCode
                )

                logger.debug("Starting with new source code\n{}", message.sourceCode)

                respond(OkResponse())
            }
        }

        engine.setMessageHandlerOf<PrevizPingMessage> {
            eocvSimApi.mainLoopHook.once {
                if (currentPrevizSession == null || currentPrevizSession?.sessionName != message.previzName) {
                    respond(ErrorResponse("Previz is not running"))
                } else {
                    currentPrevizSession?.handlePrevizPing()

                    val stats = eocvSimApi.pipelineManagerApi.pollStatistics()

                    respond(PrevizStatisticsResponse(stats.avgFps.toFloat(), stats.avgPipelineTimeMs.toLong()))
                }
            }
        }

        engine.setMessageHandlerOf<PrevizStopMessage> {
            eocvSimApi.mainLoopHook.once {
                if (currentPrevizSession?.sessionName == message.previzName) {
                    currentPrevizSession?.stopPreviz()
                    currentPrevizSession = null
                }
            }

            respond(OkResponse())
        }

        engine.setMessageHandlerOf<PrevizSourceCodeMessage> {
            eocvSimApi.mainLoopHook.once {
                if (currentPrevizSession?.sessionName == message.previzName) {
                    currentPrevizSession?.refreshPreviz(message.sourceCode)
                    logger.debug("Received source code\n{}", message.sourceCode)

                    respond(OkResponse())
                } else {
                    respond(ErrorResponse("No previz session with name ${message.previzName}"))
                }
            }
        }
    }

    private fun inputSourcesToData() = eocvSimApi.inputSourceManagerApi.allSources.map {
        IpcInputSourceData(
            it.name,
            it.data.type.toIpc(),
            it.creationTime
        )
    }.toTypedArray().apply { sortBy { it.timestamp } }

    override fun onDisable() {
        VisionGraphProcessRunner.stopPaperVision()

        currentPrevizSession?.stopPreviz()
        currentPrevizSession = null

        visionGraphProjectManager.closeCurrentProject()
    }

    private fun recoverProjects() {
        if (visionGraphProjectManager.recoveredProjects.isNotEmpty()) {
            VisionGraphDialogFactory.displayProjectRecoveryDialog(
                eocvSimApi.visualizerApi.frame!!, visionGraphProjectManager.recoveredProjects
            ) {
                for (recoveredProject in it) {
                    visionGraphProjectManager.recoverProject(recoveredProject)
                }

                if (it.isNotEmpty()) {
                    JOptionPane.showMessageDialog(
                        eocvSimApi.visualizerApi.frame!!,
                        "Successfully recovered ${it.size} unsaved project(s)",
                        "VisionGraph Project Recovery",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }

                visionGraphProjectManager.deleteAllRecoveredProjects()
            }
        }
    }

    internal fun switchToDefaultPipeline() {
        isRunningPreviewPipeline = true

        eocvSimApi.mainLoopHook.once {
            eocvSimApi.pipelineManagerApi.changePipeline(
                eocvSimApi.pipelineManagerApi.getIndexOf(
                    VisionGraphDefaultPipeline::class.java,
                    PipelineManagerApi.PipelineSource.CLASSPATH
                ) ?: 0
            )
        }
    }

    internal fun switchToDefaultPipelineIfNecessary() {
        if (isRunningPreviewPipeline) return

        if (eocvSimApi.visualizerApi.sidebarApi.isActive(paperVisionTabPanel)) {
            if (currentPrevizSession?.previzRunning != true || !VisionGraphProcessRunner.isRunning) {
                switchToDefaultPipeline()
            }
        } else {
            isRunningPreviewPipeline = false
        }
    }

    private fun tunableFieldOf(field: VirtualField): TunableFieldApi? {
        if (tunableFieldCache.containsKey(field)) {
            return tunableFieldCache[field]!!
        }
        val pipeline = currentPrevizSession?.latestPipeline ?: return null
        val tunableField = eocvSimApi.variableTunerApi.newTunableFieldInstanceOf(field, pipeline)

        tunableFieldCache[field] = tunableField
        return tunableField
    }
}

fun IpcInputSourceType.toApi() = when(this) {
    IpcInputSourceType.IMAGE -> InputSourceApi.Type.IMAGE
    IpcInputSourceType.VIDEO -> InputSourceApi.Type.VIDEO
    IpcInputSourceType.CAMERA -> InputSourceApi.Type.CAMERA
    IpcInputSourceType.HTTP -> InputSourceApi.Type.HTTP
}
fun InputSourceApi.Type.toIpc() = when(this) {
    InputSourceApi.Type.IMAGE -> IpcInputSourceType.IMAGE
    InputSourceApi.Type.VIDEO -> IpcInputSourceType.VIDEO
    InputSourceApi.Type.CAMERA -> IpcInputSourceType.CAMERA
    InputSourceApi.Type.HTTP -> IpcInputSourceType.HTTP
}