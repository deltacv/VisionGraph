# GUI, Visual Canvas, and Node Editor Subsystem

## Purpose and Scope

The GUI and Editor subsystem is the visual authoring surface of VisionGraph. It renders the interactive node graph canvas using Dear ImGui and ImNodes, presents a categorized node palette, renders property inspectors, displays video streams, coordinates user gestures (panning, zooming, box-selection, linking), and maintains undo/redo history through a command stack.

This document details the architectural layers, dual-context rendering design, declarative Compose DSL, input dispatching, and lifecycle management that keep the editor responsive at 60 FPS.

---

## High-Level Architecture

VisionGraph builds on Dear ImGui and ImNodes via Java native bindings (`imgui-java`). Because immediate-mode GUI frameworks re-render the entire visual hierarchy every frame, VisionGraph structures state into a clean hierarchy of windows, canvas elements, declarative property inspectors, and stack-allocated command actions:

```
+-------------------------------------------------------------+
| Root Viewport / ImGui Layer                                 |
|                                                             |
|  +-----------------------------------+  +----------------+  |
|  | NodeEditor (Main Window)          |  | NodeList       |  |
|  | Context: editorContext            |  | (Palette)      |  |
|  | - Draws Nodes (DrawNode)          |  | Context:       |  |
|  | - Draws Links (Link.draw)         |  | paletteContext |  |
|  | - MiniMap & Canvas Pan/Zoom       |  | - Search filter|  |
|  | - Compose DSL Property Inspectors |  | - Categorized  |  |
|  +-----------------------------------+  |   previews     |  |
|                                         +----------------+  |
|  +-------------------------------------------------------+  |
|  | Auxiliary & Modal Windows                             |  |
|  | - ImageDisplayWindow (Live video stream viewer)       |  |
|  | - OptionsWindow, ConfirmationModalWindow, IntroModal  |  |
|  +-------------------------------------------------------+  |
+-------------------------------------------------------------+
                               |
                               v
+-------------------------------------------------------------+
| Command Stack & State Mutation                              |
| - User actions generate Action commands                     |
| - Actions pushed to StackIdContainer<Action>                |
| - Non-destructive Undo (Ctrl+Z) and Redo (Ctrl+Y)           |
+-------------------------------------------------------------+
```

---

## Main Components and Class Hierarchy

### 1. The Visual Canvas: `NodeEditor`
* **File**: `VisionGraph/src/main/kotlin/org/deltacv/visiongraph/editor/NodeEditor.kt`
* **Inheritance**: Subclasses `Window` (`VisionGraph/src/main/kotlin/org/deltacv/visiongraph/gui/Window.kt`).
* **Responsibilities**:
  * Owns the primary ImNodes context handle (`editorContext`).
  * Manages canvas navigation: pan offsets (`editorPanning`), zoom scale, and minimap positioning.
  * Tracks selection state (selected nodes, selected links).
  * Manages lifecycle singletons: `InputMatNode` and `OutputMatNode` (`VisionGraph/src/main/kotlin/org/deltacv/visiongraph/node/vision/EntrypointNodes.kt`).
  * Intercepts connection gestures: validates socket compatibility and initiates link creation.

### 2. Node Palette: `NodeList`
* **File**: `VisionGraph/src/main/kotlin/org/deltacv/visiongraph/editor/palette/NodeList.kt`
* **Responsibilities**:
  * Displays all discoverable nodes grouped by `NodeCategory` (`Shared/src/main/kotlin/org/deltacv/visiongraph/node/NodeCategory.kt`).
  * Implements quick search filtering with fuzzy substring matching.
  * Allows users to spawn nodes onto the canvas at mouse position via click or drag-and-drop.

### 3. Window & Dialog Management: `Window` & `WindowGroup`
* **Files**: `Window.kt`, `WindowGroup.kt` in `VisionGraph/src/main/kotlin/org/deltacv/visiongraph/gui/`
* **Features**:
  * Managed under `visionGraph.windows` (`DenseIdContainer<Window>`).
  * Supports modal windows (`isModal = true`) which capture all keyboard and mouse interactions, blocking interaction with underlying canvas elements.

---

## Dual ImNodes Context Isolation

A critical architectural pitfall when using immediate-mode node editor libraries like ImNodes is ID collision between different node spaces. The main editor canvas renders real pipeline nodes with integer IDs, while the node palette (`NodeList`) renders interactive visual previews of available node types.

If both windows shared the same ImNodes context, selecting, hovering, or dragging a preview template in the palette would mutate the selection or state of the real pipeline nodes on the canvas.

VisionGraph solves this via **Dual Context Isolation**:
1. **Canvas Context**: `NodeEditor` allocates its own context via `ImNodes.editorContextCreate()`.
2. **Palette Context**: `NodeList` allocates a separate `ImNodes.editorContextCreate()`.
3. **Context Switching**: During rendering, `NodeEditor.draw()` binds `editorContext` via `ImNodes.editorContextSet(editorContext)`, while `NodeList.draw()` activates `paletteContext` via `ImNodes.editorContextSet(paletteContext)`.
4. **Scoped ID Container**: Furthermore, `NodeList` pushes its own isolated `DenseIdContainer` onto `IdContext.local`, ensuring that internal preview IDs never overlap with live canvas element IDs.

---

## The Canvas Drawing and Interaction Loop

During `NodeEditor.draw()`, the editor executes the following sequence:

```
NodeEditor.draw()
 │
 ├── 1. ImNodes.editorContextSet(editorContext)
 ├── 2. ImNodes.beginNodeEditor()
 │     ├── Iterates nodes.inmutable: calls node.draw()
 │     │   (draws title bar, input pins, output pins, property widgets)
 │     ├── Iterates links.inmutable: calls link.draw()
 │     │   (draws cubic Bézier curve via ImNodes.link)
 │     └── ImNodes.miniMap() renders canvas overview
 ├── 3. ImNodes.endNodeEditor()
 │
 ├── 4. Interaction Interception
 │     ├── Checks ImNodes.isLinkCreated(...)
 │     │   ├── Validates sockets via Attribute.acceptLink
 │     │   ├── Validates acyclic topology via DirectedNodeGraph.hasCycleIfAdded
 │     │   ├── If valid: executes CreateLinkAction(from, to).enable()
 │     │   └── If invalid: shows TooltipPopup rejection notice
 │     ├── Evaluates Keyboard Shortcuts (Ctrl+Z, Ctrl+Y, Ctrl+C, Ctrl+V, Del)
 │     └── Renders ContextMenuPopup on right-click
```

### Link Validation & Cycle Prevention
When a link gesture finishes, ImNodes outputs the start and end attribute IDs:
1. Sockets are retrieved from `visionGraph.attributes`.
2. **Mode & Directionality**: Links must flow strictly from `AttributeMode.OUTPUT` to `AttributeMode.INPUT`.
3. **Type Compatibility**: `Attribute.acceptLink` validates that the source data type can be ingested by the destination (or if a decomposer applies).
4. **Topological Cycle Prevention**: Before creating the link, `DirectedNodeGraph.hasCycleIfAdded(fromId, toId)` runs a recursive DFS. If the proposed edge would create an infinite cyclic loop, the link is rejected, preventing infinite recursion during execution and code generation.
5. If valid, `CreateLinkAction` is pushed to the undo stack.

---

## Declarative Property Inspection: The Compose DSL

While ImGui's raw immediate-mode paradigm is powerful, writing nested control panels with manual state management, variable caching, and layout math leads to brittle code. VisionGraph provides a declarative UI DSL located in `VisionGraph/src/main/kotlin/org/deltacv/visiongraph/gui/compose/`:

```kotlin
// Example: Composing an attribute inspector
compose {
    column {
        label(tr("prop_kernel_size"))
        sliderInt(attribute.kernelSizeProperty, min = 1, max = 31)
        combo(attribute.borderTypeProperty, BorderTypes.values())
    }
}
```

### Core Primitives
* **`Compose`**: The root layout container that measures available size and orchestrates the child rendering pass.
* **`Item` / `ContainerItem`**: Base building blocks representing layout containers (`RowItem`, `ColumnItem`) or leaf controls.
* **`Property<T>`**: Encapsulates a reactive state value with getter and setter lambdas.
* **`PropertyType` Implementations** (`VisionGraph/src/main/kotlin/org/deltacv/visiongraph/gui/compose/property/type/`):
  * `IntPropertyType`, `DoublePropertyType`, `BooleanPropertyType`: Numeric input boxes, sliders, and checkboxes.
  * `EnumPropertyType`: Dropdown combo boxes populated automatically from enum values.
  * `RangePropertyType`: Double-ended range sliders for min/max threshold tuning.
  * `ColorPropertyType`: RGB / HSV color pickers with visual swatch preview.

---

## Undo/Redo Command Architecture

User interactions are non-destructive and fully reversible through an undo/redo command stack located in `VisionGraph/src/main/kotlin/org/deltacv/visiongraph/action/`.

```
Command Hierarchy:
==================
Action (abstract base: enable, undo, execute)
   |
   +--> RootAction (baseline marker)
   |
   +--> CreateNodesAction (node restoration on redo, deletion on undo)
   +--> DeleteNodesAction (node deletion on execute, restoration on undo)
   +--> CreateLinkAction  (link creation on execute, deletion on undo)
   +--> DeleteLinksAction (link deletion on execute, re-enable on undo)
```

### Forkable Command Stack (`StackIdContainer<Action>`)
The actions are held in `StackIdContainer<Action>`:
* **Stack Pointer**: Tracks the current position in the undo history.
* **Undo (`Ctrl+Z`)**: Moves the stack pointer backward, invoking `action.undo()`.
* **Redo (`Ctrl+Y`)**: Moves the stack pointer forward, invoking `action.execute()`.
* **Forking**: If the user performs an undo and then executes a *new* action (instead of redoing), `idContainer.fork()` trims the redo tail, establishing a new active branch of history.
* **Batching**: Complex operations (like deleting multiple nodes along with all connected links) bundle actions together into composite actions so a single `Ctrl+Z` reverses the entire atomic operation.

---

## Performance Considerations

1. **Zero-Allocation Rendering Loop**:
   Because `process()` and `draw()` run 60 times per second, creating temporary objects inside the draw loop triggers severe garbage collection pauses. VisionGraph pre-allocates vectors (`ImVec2`), matrix buffers, and string caches (`hashCodeString`), passing reused buffers across iterations.
2. **Batching Model Change Events**:
   When a user drags a threshold slider continuously, hundreds of value adjustments fire per second. Rather than triggering synchronous graph traversals and code re-generation on each micro-tick, nodes emit change notifications through `QueuedChangeEmitter` (`VisionGraph/src/main/kotlin/org/deltacv/visiongraph/util/ChangeEmitter.kt`). The emitter coalesces notifications and dispatches a single downstream update per frame.
