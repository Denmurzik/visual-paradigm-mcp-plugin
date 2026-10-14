# PROJECT_STATUS.md

## Task Completed: Rewrite for Visual Paradigm 18.1 (Java 11) with full diagram support

### Why
Visual Paradigm (17.x and 18.1, incl. Community Edition) runs plugins on a bundled Java 11
runtime. The previous Spring Boot 3 / Spring AI implementation needs Java 17 and failed with
`UnsupportedClassVersionError`. The bundled runtime also has no `jdk.httpserver` module.

### What changed
- Removed Spring, Spring AI, Lombok, Mockito, the toolbar actions and the old `usecase` package.
- New package `com.brunnen.vp.mcp`, Java 11 (`maven.compiler.release=11`), no runtime dependencies:
  - `http.MiniHttpServer` – HTTP/1.1 on `ServerSocket` (Content-Length, chunked, keep-alive).
  - `json.Json` – JSON reader/writer.
  - `protocol.*` – MCP Streamable HTTP / JSON-RPC (`initialize`, `ping`, `tools/list`,
    `tools/call`), tool registry, schema builder, typed args. No VP imports, unit-tested.
  - `vp.VpTools` – 21 tools; `vp.DiagramBuilder` – shapes, nesting, swimlanes, connectors,
    sequence messages; `vp.VpModel` – lookup/description/property setting; `vp.Reflect` –
    setter/constant reflection; `vp.Edt` – runs every VP call on the Swing EDT.
- Port 8931 (configurable via `mcp.properties` in the plugin folder or `-Dvp.mcp.port`),
  bound to 127.0.0.1, non-local `Origin` rejected.
- `pom.xml`: Windows defaults (`vp.home`, `%APPDATA%\VisualParadigm\plugins`), install profile.
- `run`: Windows plugin dir, `MVN` override, extra args passed to Maven; removed
  format/spotbugs/pmd commands; `pmd-rules.xml` deleted. fmt and checkstyle plugins stay in
  the pom (not bound to phases) for the pre-commit hooks.

### Visual Paradigm API findings (verified in VP CE 18.1 build 20260914)
- Generalization, Realization and Extend store `from` = parent/supplier/base. Tools accept and
  report UML direction and swap internally (`DiagramBuilder.REVERSED_IN_VP`).
- Labels outside shapes (actor names, «include», connector names) only render after
  `IDiagramElement.resetCaption()`; done on creation and again via `invokeLater` (`finish()`).
- Swimlanes: one `ActivitySwimlane2` with `ActivityPartition`s; header
  (`ActivityPartitionHeader`) and compartment shapes must be created and registered manually.
- Sequence messages need explicit points (y) and `setSequenceNumber`; lifelines are lengthened.
  Message kinds via `IMessage.setActionType` (call/return/send/create/destroy).
- ER column type: `IDBColumn.setType(name, length, scale)`; aggregation kind values are
  `None`/`Aggregation`/`Composited`; state machine final state is `FinalState2`, choice `Choice`.
- Unnamed control nodes get an empty name, otherwise VP shows the type name.
- Elements can be added to an existing swimlane later: the lane is derived from the partition
  header's parent shape and the swimlane model's partition arrays (compared by id).
- Inline images (`vp_export_diagram_image` without path) are capped at 2000 px per side via
  `ExportDiagramAsImageOption.setMaxSize`; file exports are full size. Diagrams that are not
  open in VP export fine.

### Fixes after first real use
- `vp_save_project` ran inside a project transaction: later saves failed with
  `table LAST_PROJECT_INFO already exists` and left stale `LAST_*` tables in the .vpp, which VP
  then loads as an empty "untitled" project. Saving now runs outside any transaction and uses
  `saveProject()` when the path is the current file. A damaged file is repaired by dropping its
  `LAST_*` SQLite tables (on a copy; VP then opens it with all saved diagrams).
- `vp_set_bounds` (`Geometry.moveShape`) moves children along and re-routes attached connectors.
- Layout `boundary` (used by `auto` when a System/Package has shapes inside): actors left,
  contained shapes in a grid; other layouts get `Geometry.repairAfterLayout`.
- Duplicate names: VP silently keeps the type name; `VpModel.rename` detects this and reuses the
  existing element (`reused=true`) or errors with `reuseExisting:false`.
- Coordinates of nested shapes are documented as absolute.
- Modal dialogs run the EDT event queue, so a queued tool call executed *inside* another one
  (a save inside an unfinished delete's transaction broke the project file again and left VP's
  save machinery unusable for the rest of that VP session). `Edt.call(body, exclusive=true)`
  (all changes, save, open, new) now refuses to run while a modal dialog is open or another
  tool body is running; a timed-out request that has not started is cancelled.
- `vp_delete viewOnly`: `IDiagramUIModel.removeDiagramElement` silently does nothing; deleting
  attached connectors first and then `deleteViewOnly()` avoids VP's confirmation dialog.
- `vp_add_connector`/`vp_build_diagram` accept `modelId` to show an existing relationship
  (used to rebuild a lost diagram from a `vp_get_diagram` dump).
- Extension points are not registered project elements (not found by id or iterators); they are
  reachable only via `IExtend.getExtensionPoint()`, the base use case is the Extend's VP "from".
  Extend no longer keeps VP's automatic point; `vp_cleanup_extension_points` removes old ones and
  hides the compartment. Captions of moved shapes (actor names) are moved along.

### v0.3.0: class models, dialogs
- `Interface`, `Enumeration`, `DataType`, `Primitive` are created as a Class with the matching
  stereotype (VP has no separate model types for them); `EnumerationLiteral` children work.
- Qualifiers: `from./to.qualifier: "name: Type, ..."` creates an `IQualifier` with attributes.
- Association classes: connector `AssociationClass` from the class to the association connector.
- Property values resolve to model elements by id, `@key` (same build call) or unique class
  name; `Reflect.trySet` prefers the object overload when a value resolved (`setType`).
- `VpModel.model()` also accepts unique names; members (attributes, operations...) are found by
  id through a child search (`getModelElementById` does not find them).
- `removeStereotypes`; `view` map applies presentation setters on the shape
  (`displayStereotypeIcon:false` also clears `displayAsRobustnessAnalysisIcon`), then refits.
- `fitSize()` only requests a size; shapes are refit in `finish()` / before `layered` arranges.
- Layout `layered` (default `auto` for class/ER diagrams): VP's own layouts called through the
  API did not move class shapes and broke generalization connectors.
- VP keeps connector geometry of diagrams that are not open in an editor stale; layout opens
  the diagram and runs in the next event, `vp_set_bounds` asks to open it first.
- `vp_list_dialogs` / `vp_press_dialog_button` read and answer VP dialogs (click is deferred).
- Graceful VP shutdown in the redeploy script avoids the project recovery dialog.

### v0.4.0: sequence diagrams
- `vp_build_sequence` (`SequenceBuilder`): plans rows, activation bars (from call/return pairs;
  short bars for self calls, creation and async calls; none for actors), fragment/operand
  bounds (nested fragments supported) and refs, then creates everything. Lifelines get a
  classifier (`baseClassifier`), created objects start at the create message, destroyed ones
  end with an X (`stopped`).
- Message source ends are attached to the lifeline shape: a source end attached to an
  activation shape is stored relative to it after reopening the project (lines start at the
  diagram corner in the editor, exports looked fine). Target ends on activations are fine.
- Operands: `ICombinedFragment.createInteractionOperand()` + `addOperand()`, operand shapes as
  children of the fragment shape; VP draws the dashed separators (sometimes only after a
  repaint/reopen, not in an export made right away).
- `showDiagramFrame` has no effect through the API; the `sd` frame is an `IFrame` shape.
- `IInteractionOccurrence` shows the name of the frame it refers to (`setRefersTo`).
- Sequence numbers: `IInteractionDiagramUIModel.setShowSequenceNumbers`; VP renumbers after the
  build event, so captions are reset in a second deferred pass.
- `vp_get_view` lists presentation properties; `vp_update_element` accepts `view` for diagrams.

### v0.4.1: fixes reported by session sa-18
- Steps of a flow of events live in `IStepContainer.toStepArray()` (not in the children);
  `vp_get_element` returns them, sub-steps as `{text, steps}`. Flows and steps are found by id
  through `IUseCase.toStepContainerArray()` (`VpModel.flowElementById`).
- `vp_set_use_case_details`: `replaceActors` makes the given actor lists complete and ordered
  (`removePrimaryActor` / `removeSupportingActor`); steps may have sub-steps.
- Connectors reuse an identical existing relationship (type, VP from/to, name; associations in
  both directions with from./to. properties swapped) and do not draw a second view on the same
  diagram; `allowDuplicate` forces a new one (`DiagramBuilder.DEDUPLICATED`).
- An element shown again via `modelId` takes its size from another view right away.
- «include»/«extend» labels on steep lines are moved beside the middle of the line two events
  after VP laid them out (`Geometry.scheduleCaptionBeside`).

### v0.4.2: fixes reported by session sa-9f
- Shapes created through the API keep VP's `requestDefaultSize` flag until the diagram is painted
  in an editor; a project saved before that comes back with default-sized shapes (classes 80x40)
  when VP loads the diagram again (also after VP unloads a closed diagram: "Garbage collection
  diagram" in vp.log). Builds clear the flag for every shape with a real size
  (`Geometry.keepSizes`); when the tools open a closed diagram (`VpModel.ensureOpen`: builds,
  layouts, exports, `vp_open_diagram`, waypoints) classes/tables/enumerations are fitted to their
  content without shrinking (`Geometry.growToContent`), which repairs already damaged diagrams.
- `vp_export_diagram_image` opens a closed diagram first and exports in a later event; closed
  diagrams were rendered with stale/default sizes. `vp_get_diagram` reports `opened`.
- `vp_add_child` / member changes refit the owner's shapes (`setRequestFitSize` for closed
  diagrams, `fitSize` on open ones), so new members show up in the compartments.
- Association reuse compares the given role names, multiplicities and aggregation kinds
  (`DiagramBuilder.sameEnds`, both directions); two compositions with different roles are no
  longer merged. Association ends are reported as `fromEnd`/`toEnd`.
- Properties of type `TYPE_TEXT_MODEL` (attribute/parameter `type`, operation `returnType`) were
  dropped; they are read now, and members get `dataType`/`returnType` (with type modifier) in
  every description. `vp_find_elements` includes members (the all-level iterator skips them).
- Connectors: `waypoints` (bend points; ends on the shape borders) on `vp_add_connector`, build
  connectors and `vp_update_element`. A further connector between the same two shapes gets a bend
  (`Geometry.parallelIndex`/`parallelBend`, also in `reroute`). New connectors are drawn between
  the shape borders after a build (VP starts them at a corner, often along the top edges). VP
  itself moves the ends of a bent connector to a corner of the shape; pin ratios
  (`fromPinType`=RATIO) gave unusable points and are not used.
- `vp_update_element` with the project id renames the project (`IProject.setName`).
- `vp_list_types` accepts a diagram type; without a diagram of that type it asks a temporary
  diagram (created and deleted, so the project is marked modified). Answers are cached per type.

### v0.4.3: messages lost their receiver
- A message whose target end is an activation shape loses its receiver in VP: the model's `to`
  end has no element and the connector has no `toShape` (right after creation and in the saved
  file); only the stored points keep the picture right. `vp_build_sequence` attached every call
  and return to the receiver's activation bar, so nearly all its messages had no receiver
  (`vp_get_element` showed `to: {}`). Both ends are now attached to the lifelines (the source end
  already was, see v0.4.0); `IMessage.setFromActivation`/`setToActivation` still link the bars.
  VP puts the ends of lifeline-attached messages on the lifeline's centre line, so arrow heads
  reach half an activation bar into it.
- `vp_add_connector` / build connectors of type Message given an activation shape use its
  lifeline; activation shape ids are found (`IProject.getDiagramElementById` fallback).
- VP places the label of a message from a lifeline to itself far from the loop; it is moved
  above the loop's first segment (`Geometry.placeSelfMessageCaption`).
- Diagrams built with older versions keep messages without a receiver; rebuild them (or re-create
  the messages) to repair the model.

### Tested
- `./run test`: 38 unit tests; `mvn fmt:format` and `mvn checkstyle:check` clean
  (pre-commit itself is not installed on this machine; its generic checks were done by hand).
- Manually through MCP in VP CE 18.1: use case (system boundary, include/extend/generalization,
  use case specification), class (members, abstract, stereotype, realization, dependency,
  composition with multiplicities), activity (swimlanes, decision with guards), state machine,
  ER (PK, varchar(255), FK), sequence (actor, lifelines, call/return messages), find/get/update/
  delete, layout, image export (file + inline PNG), save project. Claude Code connects
  (`claude mcp list` ✔) and lists all 21 tools.

### Known limitations / ideas
- Sequence diagrams: activation bars are only drawn by VP for the actor; combined fragments are
  untested.
- Swimlane height is fixed (560 / 180 per lane) and is not shrunk to its content.
- No MCP resources/prompts yet.
- Parallel connectors share VP's corner end points (labels at that end may overlap); classes that
  grow after new members can overlap their neighbours.
- Use case `boundary` layout puts use cases in one column; an extend/include between the first
  and the last use case can pass over the middle one.
