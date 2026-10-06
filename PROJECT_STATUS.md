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

### Tested
- `./run test`: 28 unit tests; `mvn fmt:format` and `mvn checkstyle:check` clean
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
