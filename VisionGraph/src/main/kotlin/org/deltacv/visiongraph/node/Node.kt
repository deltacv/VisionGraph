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

package org.deltacv.visiongraph.node

import imgui.ImGui
import imgui.ImVec2
import imgui.extension.imnodes.ImNodes
import org.deltacv.visiongraph.attribute.Attribute
import org.deltacv.visiongraph.attribute.AttributeMode
import org.deltacv.visiongraph.attribute.TypedAttribute
import org.deltacv.visiongraph.attribute.rebuildOnLink
import org.deltacv.visiongraph.codegen.*
import org.deltacv.visiongraph.exception.NodeGenException
import org.deltacv.visiongraph.editor.NodeEditor
import org.deltacv.visiongraph.id.DrawableIdElementBase
import org.deltacv.visiongraph.id.container.IdContext
import org.deltacv.visiongraph.node.vision.OutputMatNode
import org.deltacv.visiongraph.serialization.v1.data.DataSerializable
import org.deltacv.visiongraph.serialization.v1.BasicNodeData
import org.deltacv.visiongraph.serialization.v1.NodeSerializationData
import org.deltacv.visiongraph.serialization.v2.CodecTypeRegistry
import org.deltacv.visiongraph.serialization.v2.DataCodec
import org.deltacv.visiongraph.serialization.v2.DataDecoder
import org.deltacv.visiongraph.serialization.v2.DataEncoder
import org.deltacv.visiongraph.util.DelegatedChangeEmitter
import org.deltacv.visiongraph.util.QueuedChangeEmitter
import org.deltacv.visiongraph.util.event.PaperEventHandler
import org.deltacv.visiongraph.util.event.PaperEventListenerId
import org.deltacv.visiongraph.util.loggerFor
import org.deltacv.visiongraph.util.loggerForThis
import org.deltacv.mai18n.tr
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

abstract class Node<S: CodeGenSession>(
    val isDeletable: Boolean = true,
    val joinActionStack: Boolean = true,
    val rebuildOnLink: Boolean = true,
) : DrawableIdElementBase<Node<*>>(),
    GenNode<S>,
    GenValueMapper,
    DelegatedChangeEmitter<Node.ChangeType>,
    DataSerializable<NodeSerializationData>,
    DataCodec
{
    private val logger by loggerForThis()

    override val idContainer = IdContext.local.peekNonNull<Node<*>>()
    override val requestedId get() = if(forgetSerializedId) null else serializedId

    private var beforeDeletingPosition = ImVec2()

    val description by lazy { this::class.java.getAnnotation(PaperNode::class.java)?.description }

    var serializedId: Int? = null
        private set

    var showAttributesCircles = true

    var forgetSerializedId = false
        private set

    // will be set on NodeEditor#draw
    lateinit var editor: NodeEditor
        internal set

    val isOnEditor get() = ::editor.isInitialized && idContainer.contains(this)

    // it is the responsibility of the inheriting class to set this value in draw()
    val screenPosition = ImVec2()
    val gridPosition = ImVec2()
    val position = ImVec2()
    val size = ImVec2()

    override val generators: PolyglotGeneratorMap<Unit, S> = mapOf()

    override val genOptions = CodeGenOptions()

    val onDelete = PaperEventHandler("OnDelete-${this::class.simpleName}")

    private val attribOnChangeListenerIds = mutableMapOf<Attribute, PaperEventListenerId>()

    @Transient
    private val _nodeAttributes = mutableListOf<Attribute>() // internal mutable list

    val nodeAttributes = _nodeAttributes as List<Attribute> // public read-only

    override val changeEmitterDelegate = QueuedChangeEmitter<ChangeType>(defaultPeekChange = ChangeType.AttributeChange)

    protected open fun drawAttributes(additional: List<Attribute> = emptyList()) {
        val total = (nodeAttributes + additional)

        for((i, attribute) in total.withIndex()) {
            attribute.parentNode = this
            attribute.draw()

            if(i < total.size - 1 && attribute.drawState != Attribute.DrawState.SKIPPED) {
                ImGui.newLine() // make a new blank line if this isn't the last attribute
            }
        }
    }

    override fun delete() {
        if(isDeletable) {
            forceDelete()
        }
    }

    fun forceDelete() {
        beforeDeletingPosition = ImVec2(position)

        for (attribute in nodeAttributes.toTypedArray()) {
            attribute.delete()
        }

        idContainer.removeId(id)
        onDelete.run()
    }

    override fun restore() {
        if(isDeletable) {
            forceRestore()
        }
    }

    fun forceRestore() {
        for (attribute in nodeAttributes.toTypedArray()) {
            attribute.restore()
        }

        idContainer[id] = this

        if(this is DrawNode<*>) {
            nextNodePosition = beforeDeletingPosition
        }
    }

    open fun addAttribute(attribute: Attribute) {
        if(!_nodeAttributes.contains(attribute)) {
            attribute.parentNode = this
            attribOnChangeListenerIds[attribute] = attribute.onChange {
                // emit this attribute's changes to the node
                emitChange(ChangeType.AttributeChange)
            }

            if(rebuildOnLink) {
                // automatically rebuild previz when this attribute's links change
                attribute.rebuildOnLink()
            }

            attribute.enable()

            _nodeAttributes.add(attribute)
        }
    }

    fun removeAttribute(attribute: Attribute) {
        if(_nodeAttributes.contains(attribute)) {
            //attribute.parentNode = null
            attribOnChangeListenerIds.remove(attribute)?.let {
                attribute.onChange.removeListener(it)
            }
            _nodeAttributes.remove(attribute)
        }
    }

    operator fun Attribute.unaryPlus() = addAttribute(this)

    override fun getGenValueOf(current: CodeGen.Current, attrib: Attribute): GenValue {
        raise("Node does not have output attributes ('getGenValueOf' was not overridden)")
    }

    fun hasDeadEnd(initialNode: Node<*> = this): Boolean {
        for(attribute in nodeAttributes) {
            if(attribute.mode == AttributeMode.INPUT) {
                continue
            }

            for(linkedAttribute in attribute.availableLinkedAttributes) {
               if(linkedAttribute != null) {
                   if(linkedAttribute.mode == AttributeMode.OUTPUT || linkedAttribute.parentNode == initialNode) {
                       continue // uh oh
                   }

                   if(linkedAttribute.parentNode is OutputMatNode || !linkedAttribute.parentNode.hasDeadEnd(initialNode)) {
                       return false // not a dead end
                   }
               }
            }
        }

        return true
    }

    override fun codeGenPropagate(current: CodeGen.Current) {
        val linkedNodes = mutableListOf<Node<*>>()

        for(attribute in _nodeAttributes) {
            if(attribute.mode == AttributeMode.OUTPUT) {
                for(linkedAttribute in attribute.availableLinkedAttributes) {
                    if(linkedAttribute != null && !linkedNodes.contains(linkedAttribute.parentNode)) {
                        linkedNodes.add(linkedAttribute.parentNode)
                    }
                }
            }
        }

        val deadEndNodes = mutableListOf<Node<*>>()

        for(linkedNode in linkedNodes) {
            if(linkedNode.hasDeadEnd()) {
                deadEndNodes.add(linkedNode)
                logger.debug("Dead end: {}", linkedNode)
            } else {
                logger.debug("Part of the chain: {}", linkedNode)
            }
        }

        // Propagate to dead ends, so they can be processed without being left out
        // because no one depends on them, as they won't be propagated to otherwise.
        deadEndNodes.forEach { it.genCodeIfNecessary(current) }
    }

    // ------------------ Serialization v1 ------------------

    open fun takeSerializationData(data: NodeSerializationData) { /* do nothing */ }
    open fun makeSerializationData() = BasicNodeData(id, ImNodes.getNodeEditorSpacePos(id))

    /**
     * Call before enable()
     */
    final override fun deserialize(data: NodeSerializationData) {
        serializedId = data.id
        if(this is DrawNode<*>) {
            nextNodePosition = data.nodePos
        }

        takeSerializationData(data)
    }

    final override fun serialize(): NodeSerializationData {
        val data = makeSerializationData()
        data.id = id

        data.nodePos = ImNodes.getNodeEditorSpacePos(id)

        return data
    }

    // ------------------ Serialization v2 ------------------

    override fun encode(encoder: DataEncoder) {
        encoder.int("id", id)
    }

    override fun decode(decoder: DataDecoder) {
        serializedId = decoder.int("id")
        idContainer.reserveId(serializedId!!)
        logger.debug("Decoded node with id {} ({})", serializedId, CodecTypeRegistry.nameOf(this::class))
    }

    fun noValue(attrib: Attribute): Nothing {
        val name = (attrib as? TypedAttribute<*>)?.attributeName ?: attrib::class.simpleName ?: attrib.toString()
        raise(tr("err_attrib_nothandled_bythis", tr(name)))
    }

    fun raise(message: String): Nothing = throw NodeGenException(this, message)

    fun warn(message: String) {
        logger.warn("CODE GEN WARN: $message") // TODO: Warnings system...
    }

    @OptIn(ExperimentalContracts::class)
    fun raiseAssert(condition: Boolean, message: String) {
        contract {
            returns() implies condition
        }

        if(!condition) {
            raise(message)
        }
    }

    fun warnAssert(condition: Boolean, message: String) {
        if(!condition) {
            warn(message)
        }
    }

    fun forgetSerializedId() {
        forgetSerializedId = true
    }

    override fun toString() = "Node(\"$genNodeName\", id=$id)"

    sealed class ChangeType {
        object AttributeChange: ChangeType()
    }

    companion object {
        private val logger by loggerFor<Node<*>>()

        @JvmStatic protected val INPUT = AttributeMode.INPUT
        @JvmStatic protected val OUTPUT = AttributeMode.OUTPUT

        fun checkSimpleRecursion(from: Node<*>, to: Node<*>): Boolean {
            val linksBetween = Link.getLinksBetween(from, to)

            var hasOutputToInput = false
            var hasInputToOutput = false

            for(link in linksBetween) {
                val aNode = link.aAttrib?.parentNode ?: continue

                val fromAttrib = (if(aNode == from) link.aAttrib else link.bAttrib) ?: continue
                val toAttrib   = (if(aNode == to) link.aAttrib else link.bAttrib) ?: continue

                if(!hasOutputToInput)
                    hasOutputToInput = fromAttrib.mode == OUTPUT && toAttrib.mode == INPUT

                if(!hasInputToOutput)
                    hasInputToOutput = fromAttrib.mode == INPUT && toAttrib.mode == OUTPUT

                if(hasOutputToInput && hasInputToOutput)
                    return true
            }

            return false
        }
    }
}



