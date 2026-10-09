---
name: visual-paradigm
description: >
  How to model with Visual Paradigm through the visual-paradigm MCP server (vp_* tools): build
  and edit UML diagrams (use case, class, sequence, activity, state machine), ER diagrams and
  use case specifications, check the result as an image, and save safely. Use this skill whenever
  the vp_* tools are available and the user wants to draw, create, change, review, lay out or
  export a diagram or model, mentions Visual Paradigm, VP or a .vpp file, or asks for UML/ER
  modelling of a system - even if they do not name the tool.
---

# Modelling in Visual Paradigm via MCP

## Prerequisites / Setup

This skill needs the **visual-paradigm MCP server** (tools named `vp_*`). If those tools are not
available, do not improvise: tell the user to set it up first and point to
https://github.com/Denmurzik/visual-paradigm-mcp-plugin.

1. Visual Paradigm 18.1+ (the free Community Edition works) with the plugin from the
   repository's Releases: **Help > Install Plugin > Install from a folder of plugin**, restart VP.
2. Register the server `http://127.0.0.1:8931/mcp` (Streamable HTTP; only reachable while VP runs):
   - Claude Code: `claude mcp add --transport http -s user visual-paradigm http://127.0.0.1:8931/mcp`
   - Codex CLI: `codex mcp add visual-paradigm --url http://127.0.0.1:8931/mcp`
   - OpenCode: `opencode mcp add visual-paradigm --url http://127.0.0.1:8931/mcp`
   - Antigravity CLI: `agy mcp add visual-paradigm http://127.0.0.1:8931/mcp`
   - Gemini CLI: `gemini mcp add -s user --transport http visual-paradigm http://127.0.0.1:8931/mcp`
   - Cursor (`~/.cursor/mcp.json`): `{"mcpServers": {"visual-paradigm": {"url": "http://127.0.0.1:8931/mcp"}}}`
   - VS Code / GitHub Copilot (`mcp.json`): `{"servers": {"visual-paradigm": {"type": "http", "url": "http://127.0.0.1:8931/mcp"}}}`
   - Cline: `{"mcpServers": {"visual-paradigm": {"type": "streamableHttp", "url": "http://127.0.0.1:8931/mcp"}}}`
3. Restart the agent session so it loads the tools.

The visual-paradigm MCP server is a plugin running inside the user's Visual Paradigm (VP). Every
call changes the live model the user sees, so work like a careful colleague at their screen:
look before you change, build in few large steps, check the picture, save on purpose.

## Before you start

1. `vp_get_project_info` - which project is open, is it saved, does it have unsaved changes?
   VP reopens the last project on start, so the open project may not be the one the task is
   about. If it is the wrong one, ask before switching; `vp_open_project` on a modified project
   makes VP ask the user to save (see "Dialogs").
2. `vp_list_diagrams` / `vp_find_elements` - reuse what exists. Elements are shared across
   diagrams: an actor or class that already exists should be shown again (`modelId`), not
   duplicated. VP refuses duplicate names in one namespace; the tools then reuse the existing
   element and report `reused: true`.

## Pick the right tool

| Task | Tool |
| --- | --- |
| Sequence diagram | `vp_build_sequence` - participants + ordered steps; activation bars, create/destroy, fragments (alt/opt/loop/par/break...), refs and the `sd` frame are generated |
| Any other new diagram | `vp_build_diagram` with all elements and connectors in one call (keys link them) |
| Add to an existing diagram | `vp_add_shape`, `vp_add_connector`, `vp_add_child` (members), or `vp_build_diagram` with `diagram` |
| Change / delete | `vp_update_element` (name, documentation, properties, `view`), `vp_delete` (`viewOnly` keeps the model) |
| Use case specification | `vp_set_use_case_details` (pre/post conditions, actors, flows of events) |
| Arrange | `vp_layout_diagram` (`auto` picks `boundary` for System boundaries, `layered` for class/ER diagrams) |
| Look at the result | `vp_export_diagram_image` without `path` returns the PNG to you |

Prefer one `vp_build_*` call over dozens of single calls: it is one undo step for the user, keeps
consistent spacing and lets elements reference each other by `key`. Examples for every diagram
type are in [references/examples.md](references/examples.md) - read the one you need before
writing a large spec.

## Rules that keep diagrams correct

- **UML direction.** Give relationships in UML reading direction: Generalization child -> parent,
  Realization class -> interface, Extend extension -> base use case, Include base -> included.
  The plugin converts to VP's internal storage.
- **Coordinates are absolute**, also for shapes inside a System boundary, package or swimlane.
  Usually omit x/y and let auto placement or `vp_layout_diagram` do the work.
- **Containers first.** Create the System boundary / package / swimlane before its content and
  put content in with `parent` (a key, id or partition key/name for swimlanes).
- **Types as references.** An attribute type that is a model class should reference it (`"type":
  "@key"` within the same build call, or the class name/id) so VP links it.
- **Names of nodes.** Leave unnamed control nodes (initial/final/decision) without `name`.
- **Extend** gets no "ExtensionPoint" compartment by default; pass `extensionPoint: "name"` only
  when the user wants a named extension point. Old automatic ones: `vp_cleanup_extension_points`.
- **Stereotypes** go in `properties.stereotypes` (`removeStereotypes` to drop). Robustness
  stereotypes (entity/boundary/control) are drawn as icons; `view: {"displayStereotypeIcon":
  false}` keeps the class box.

## Verify, then save

After building or changing a diagram, export it as an image and actually look at it: clipped
classes, labels far from their lines, overlapping shapes or lines ending in empty space are easy
to see and cheap to fix (`vp_layout_diagram`, `layout: "reroute"` to redraw connectors only,
`vp_set_bounds`). Tell the user what you built in a sentence or two, not the JSON.

Nothing is saved automatically. Call `vp_save_project` when a piece of work is done (a never
saved project needs `path`). Save before anything that might reopen or reload the project.

## Dialogs and "busy"

While VP shows a modal dialog it refuses changes and the tool answers "Visual Paradigm is busy".
Read it with `vp_list_dialogs`, then answer with `vp_press_dialog_button` - choose the answer
that matches what the user wants, and never discard unsaved work of the user on your own
("No"/"Don't save" on someone's real project). If the dialog is about the user's own project and
the right answer is unclear, ask. Do not retry a timed-out call blindly: the result says whether
the request was cancelled or is still running.

## Moving things

`vp_set_bounds` moves nested shapes and re-routes connectors with the shape, but only on a
diagram that is open in VP (`vp_open_diagram` first). Layouts open a closed diagram themselves.

## When something looks wrong

- `vp_get_diagram` shows ids, bounds, parents and connector points; `vp_get_element` shows
  properties, children (attributes, operations...) and relationships.
- `vp_get_view` lists presentation properties of a shape or diagram (keys usable in `view`).
- `vp_list_types` lists the shape types a diagram accepts when a type is refused.
- Errors from the tools name the problem (unknown property, ambiguous name, refused connection);
  fix the input instead of repeating the same call.
