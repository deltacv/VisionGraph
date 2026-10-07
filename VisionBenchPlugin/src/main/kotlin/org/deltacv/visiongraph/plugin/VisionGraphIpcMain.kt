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

@file:Suppress("DEPRECATION")

package org.deltacv.visiongraph.plugin

import imgui.app.Application
import org.deltacv.visiongraph.engine.client.response.JsonElementResponse
import org.deltacv.visiongraph.engine.client.response.OkResponse
import org.deltacv.visiongraph.engine.client.response.PaperVisionEngineMessageResponse
import org.deltacv.visiongraph.gui.font.FontAwesomeIcons
import org.deltacv.visiongraph.editor.Option
import org.deltacv.visiongraph.gui.TooltipPopup
import org.deltacv.visiongraph.platform.lwjgl.LWJGLPaperVisionApp
import org.deltacv.visiongraph.plugin.gui.imgui.CloseConfirmWindow
import org.deltacv.visiongraph.plugin.gui.imgui.InputSourceWindow
import org.deltacv.visiongraph.plugin.engine.EOCVSimIpcEngineBridge
import org.deltacv.visiongraph.plugin.engine.message.DiscardCurrentRecoveryMessage
import org.deltacv.visiongraph.plugin.engine.message.EditorChangeMessage
import org.deltacv.visiongraph.plugin.engine.message.GetCurrentProjectMessage
import org.deltacv.visiongraph.plugin.engine.message.SaveCurrentProjectMessage
import org.deltacv.visiongraph.serialization.v1.PaperVisionSerializer.deserializeAndApply
import org.deltacv.visiongraph.serialization.v2.PaperVisionProject
import org.deltacv.visiongraph.serialization.v2.json.JsonCodec
import org.deltacv.visiongraph.util.loggerForThis
import kotlinx.serialization.json.Json
import picocli.CommandLine
import java.util.concurrent.Callable
import kotlin.system.exitProcess

class VisionGraphIpcMain : Callable<Int?> {
    @CommandLine.Option(names = ["-i", "--ipcport"], description = ["Engine IPC server port"])
    var ipcPort: Int = 0

    @CommandLine.Option(names = ["-q", "--queryproject"], description = ["Asks the engine for the current project on startup"])
    var queryProject: Boolean = false

    private lateinit var app: LWJGLPaperVisionApp

    private var userCloseRequestsCount = 0

    private val logger by loggerForThis()

    override fun call(): Int {
        logger.info("IPC port {}", ipcPort)

        val bridge = EOCVSimIpcEngineBridge(ipcPort)

        app = LWJGLPaperVisionApp(bridge, false)

        app.visionGraph.onUpdate.once {
            if (queryProject) {
                app.visionGraph.engineClient.sendMessage(GetCurrentProjectMessage().onResponseWith<JsonElementResponse> { response ->
                    val json = response.value

                    app.visionGraph.onUpdate.once {
                        // v2 serialization attempt first
                        try {
                            JsonCodec().decode(json, PaperVisionProject()).apply(app.visionGraph)
                        } catch (e: Exception) {
                            logger.warn(
                                "Failed to deserialize project with v2 format, falling back to v1 (will be automatically migrated to v2 on next save)",
                                e
                            )

                            // fall back to v1, this will be the last time this project
                            // is deserialized with v1. After this, the project will be saved with v2.
                            deserializeAndApply(Json.encodeToString(json), app.visionGraph)
                        }

                        app.visionGraph.onUpdate.once {
                            app.visionGraph.nodeEditor.onEditorChange {
                                app.visionGraph.onUpdate.once {
                                    val project = PaperVisionProject.from(app.visionGraph)
                                    app.visionGraph.engineClient.sendMessage(
                                        EditorChangeMessage(
                                            JsonCodec().encodeToJsonElement(project)
                                        )
                                    )
                                }
                            }
                        }
                    }
                })
            }
        }

        app.visionGraph.onInit.once {
            val inputSourceWindow = InputSourceWindow(
                app.visionGraph.engineClient
            )

            app.visionGraph.nodeEditor.streamWindowGroup.add(inputSourceWindow)

            app.visionGraph.nodeEditor.options[FontAwesomeIcons.Save] = Option("mis_saveproject") {
                app.visionGraph.engineClient.sendMessage(
                    SaveCurrentProjectMessage(
                        // save v2
                        JsonCodec().encodeToJsonElement(PaperVisionProject.from(app.visionGraph))
                    ).onResponseWith<OkResponse> {
                        app.visionGraph.onUpdate.once {
                            TooltipPopup("mis_projectsaved", 4.0).enable()
                        }

                        logger.info("Project saved")
                    }
                )
            }

            app.visionGraph.window.setCloseListener(::paperVisionUserCloseListener)
        }

        Application.launch(app)

        return 0
    }

    private fun paperVisionUserCloseListener(): Boolean {
        userCloseRequestsCount++

        app.visionGraph.onUpdate.once {
            openCloseConfirmDialog()
        }

        return userCloseRequestsCount >= 3
    }

    private fun openCloseConfirmDialog() {
        CloseConfirmWindow { action: CloseConfirmWindow.Action ->
            userCloseRequestsCount = 0

            when (action) {
                CloseConfirmWindow.Action.YES -> app.visionGraph.engineClient.sendMessage(
                    SaveCurrentProjectMessage(
                        // save v2
                        JsonCodec().encodeToJsonElement(PaperVisionProject.from(app.visionGraph))
                    ).onResponse { response: PaperVisionEngineMessageResponse? ->
                        if (response is OkResponse) {
                            exitProcess(0)
                        }
                    }.onTimeout(2000) {
                        logger.warn("Timeout saving project, exiting anyway")
                        exitProcess(-1)
                    }
                )

                CloseConfirmWindow.Action.NO -> app.visionGraph.engineClient.sendMessage(
                    DiscardCurrentRecoveryMessage().onResponse { response ->
                        if (response is OkResponse) {
                            exitProcess(0)
                        }
                    }.onTimeout(2000) {
                        logger.warn("Timeout discarding recovery, exiting anyway")
                        exitProcess(-1)
                    }
                )

                else -> {}
            }
        }.enable()
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val exitCode = CommandLine(VisionGraphIpcMain()).execute(*args)
            exitProcess(exitCode)
        }
    }
}



