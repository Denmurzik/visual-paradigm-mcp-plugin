# Visual Paradigm MCP Plugin

**English** | [Русский](README.ru.md)

> Fork of [orgatex/visual-paradigm-mcp-plugin](https://github.com/orgatex/visual-paradigm-mcp-plugin)
> by Manoel Brunnen, rewritten for Visual Paradigm 18.1 (incl. the free Community Edition):
> no Spring, runs on VP's bundled Java 11, and supports all UML diagram types.

A Model Context Protocol (MCP) server plugin for Visual Paradigm that provides
AI applications with seamless integration to Visual Paradigm's modeling and
diagramming capabilities. The MCP server is embedded directly within
the Visual Paradigm plugin, starting automatically when the plugin loads.

## Architecture

- **Visual Paradigm 18.1** (also Community Edition): UML modeling platform integration
- **Java 11, no third-party dependencies**: Visual Paradigm runs plugins on its bundled
  Java 11 runtime, which also lacks the `jdk.httpserver` module. The plugin therefore ships
  its own small HTTP server, JSON reader/writer and MCP (JSON-RPC) layer.
- **MCP Transport**: Streamable HTTP (POST, JSON responses) on `127.0.0.1` only
- **Visual Paradigm Plugin API**: All model access runs on the Swing event dispatch thread,
  each change as one undoable project transaction
- **Maven**: Build automation
- **JUnit 5**: Tests for the HTTP, JSON, MCP and reflection layers

## Features

### MCP Server Integration

The plugin includes an embedded MCP server that:

- **Auto-starts** when Visual Paradigm plugin is loaded
- **Auto-stops** when Visual Paradigm plugin is unloaded
- Runs on **port 8931** with endpoint `/mcp` (configurable in `mcp.properties`
  in the installed plugin folder, or with `-Dvp.mcp.port=...`)
- Provides **tool capabilities** for external MCP clients

#### Available MCP Tools

The tools are generic: element and relationship types are Visual Paradigm model type
names, so every diagram type is supported. Use case, activity (with swimlanes), sequence,
class, ER and state machine diagrams are tested.

| Tool | Purpose |
| --- | --- |
| `vp_get_project_info` | Open project: name, file, unsaved changes, VP version |
| `vp_new_project`, `vp_open_project`, `vp_save_project` | Project files |
| `vp_list_diagrams`, `vp_get_diagram` | Diagrams and their shapes/connectors |
| `vp_find_elements`, `vp_get_element` | Search the model, read properties, relationships, flows of events |
| `vp_list_types` | Shape types a diagram accepts |
| `vp_get_view` | Presentation properties of a shape, connector or diagram (keys for `view`) |
| `vp_create_diagram` | Create any diagram type |
| `vp_build_diagram` | Many shapes and connectors in one call, referenced by own keys |
| `vp_build_sequence` | Complete sequence diagram from participants and steps: activation bars, create/destroy, self calls, async and reply messages, combined fragments (alt, opt, loop, par, break, ...) with guards, interaction uses (ref), `sd` frame |
| `vp_add_shape`, `vp_add_connector`, `vp_add_child` | Single elements, relationships, members (attributes, operations, columns, ...) |
| `vp_update_element`, `vp_delete`, `vp_set_bounds` | Rename, set properties, delete (model or view only), move with connectors |
| `vp_set_use_case_details` | Pre/post conditions, actors, flows of events |
| `vp_cleanup_extension_points` | Remove the "ExtensionPoint" entries VP adds for every Extend |
| `vp_layout_diagram`, `vp_open_diagram` | Automatic layout (`layered` for class/ER diagrams, `boundary` for System boundaries, `reroute` to redraw connectors only), show in VP |
| `vp_list_dialogs`, `vp_press_dialog_button` | See and answer dialogs Visual Paradigm opens (save changes, project recovery, ...) |
| `vp_export_diagram_image` | PNG/JPG/SVG/PDF export; PNG can be returned to the AI to look at |

Relationships are always given in UML reading direction (child → parent for
Generalization, class → interface for Realization, extension → base use case for Extend);
the plugin converts this to Visual Paradigm's internal direction.

Properties can be any setter of the Visual Paradigm model object without the `set` prefix
(`visibility`, `multiplicity`, `abstract`, `primaryKey`, `guard`, ...); int enumerations
accept their constant names, `from.`/`to.` prefixes address association ends.

Class models: `Interface`, `Enumeration` (with `EnumerationLiteral` children), `DataType`;
attributes, operations with parameters, static/abstract members, initial values; association
roles, multiplicities, navigability, aggregation/composition, qualifiers
(`"to.qualifier": "isbn: String"`), association classes; stereotypes on any element
(`stereotypes`, `removeStereotypes`). A type can reference a class by name, id or `@key` of the
same `vp_build_diagram` call. Elements can be referenced by id or by unique name. The `view`
argument changes the presentation of a shape, e.g. `{"displayStereotypeIcon": false}` shows
`«entity»` classes as boxes instead of robustness icons.

#### Future MCP Features (Planned)

##### Resources

- Server Status: Monitor MCP server health and connection status
- Project Information: Retrieve current project details
- Diagram Metadata: Access diagram properties and structure

##### Prompts

- Use Case Templates: Generate standard use case patterns
- Diagram Validation: Check diagram completeness and consistency

### Plugin Integration

- **Automatic Lifecycle Management**: MCP server starts/stops with plugin
- **Error Handling**: Startup problems (e.g. port in use) are shown in Visual Paradigm's
  message pane and in `vp.log`; tool errors are returned to the client as readable messages
- **Security**: Listens on the loopback interface only and rejects non-local browser origins

## Usage

### Installation

Three steps, the same on every operating system: **install the plugin** in Visual Paradigm,
**connect your AI client**, **install the skill**. The release needs no Java or Maven -
Visual Paradigm brings its own runtime.

#### 1. Install the plugin

1. Install [Visual Paradigm](https://www.visual-paradigm.com/download/) 18.1 or later
   (the free Community Edition works).
2. Download `visual-paradigm-mcp-plugin-<version>.zip` from the [Releases](../../releases) page
   and unzip it. You get a folder `visual-paradigm-mcp-plugin` containing `plugin.xml`.
3. In Visual Paradigm open **Help > Install Plugin**, choose **Install from a folder of plugin**
   and select that `visual-paradigm-mcp-plugin` folder. Restart Visual Paradigm.

   Or copy the folder manually while Visual Paradigm is closed. **Help > Install Plugin >
   Copy Path** shows the exact plugins directory on your machine; the usual locations are:

   | OS | Plugins directory |
   | --- | --- |
   | Windows | `%APPDATA%\VisualParadigm\plugins` |
   | macOS | `~/Library/Application Support/VisualParadigm/plugins` |
   | Linux | `~/.config/VisualParadigm/plugins` (older versions: `~/VisualParadigm/plugins`) |

   The result must be `<plugins directory>/visual-paradigm-mcp-plugin/plugin.xml`.
4. Start Visual Paradigm. The log file `vp.log` in the Visual Paradigm user directory (the parent
   of the plugins directory, e.g. `%APPDATA%\VisualParadigm\vp.log` on Windows) shows
   `[vp-mcp] MCP server listening on http://127.0.0.1:8931/mcp (26 tools)`.

To update, close Visual Paradigm, replace the `visual-paradigm-mcp-plugin` folder and start it
again (Visual Paradigm locks the plugin's jar while it runs, so the folder cannot be replaced
while it is open).

Tested on Windows 11 with Visual Paradigm Community Edition 18.1. The plugin is plain Java 11
without native code, so macOS and Linux work the same way; there only the directories differ.

#### From source

Requirements: JDK 11 or newer, Maven 3.9, and Visual Paradigm installed (the build compiles
against its `openapi.jar`).

Build, test and install with the `./run` command (bash; on Windows use Git Bash, or call Maven
directly with the same arguments). Set `MVN=/path/to/mvn` if Maven is not on the `PATH`. Extra
arguments are passed to Maven:

| Property | Meaning | Default |
| --- | --- | --- |
| `vp.lib.dir` | folder that contains `openapi.jar` (`lib` or `bundled` in the VP installation) | Windows: `C:/Program Files/Visual Paradigm CE 18.1/lib`, Linux: `~/Visual_Paradigm_18.1/lib` |
| `vp.plugins.dir` | Visual Paradigm plugins directory for `./run install` | Windows: `%APPDATA%/VisualParadigm/plugins`, Linux/macOS: `~/.config/VisualParadigm/plugins` |

```bash
# Windows (Git Bash), default installation
./run all

# macOS
./run all -Dvp.lib.dir="/Applications/Visual Paradigm.app/<path to the folder with openapi.jar>" \
          -Dvp.plugins.dir="$HOME/Library/Application Support/VisualParadigm/plugins"

# Linux
./run all -Dvp.lib.dir="$HOME/Visual_Paradigm_18.1/lib"
```

`./run all` builds, runs the tests and installs; `./run build`, `./run test`, `./run package` and
`./run install` do single steps. Close Visual Paradigm before installing.

### Using the MCP Server

Once Visual Paradigm is running with the plugin:

- **MCP Server Endpoint**: `http://127.0.0.1:8931/mcp` (Streamable HTTP)
- **Server Name**: `visual-paradigm`
- **Available Tools**: 26 tools (see above)

#### 2. Connect an AI client

Visual Paradigm must be running with the plugin; the server lives at
`http://127.0.0.1:8931/mcp` (Streamable HTTP). Register it once in your client:

| Client | Command |
| --- | --- |
| Claude Code | `claude mcp add --transport http -s user visual-paradigm http://127.0.0.1:8931/mcp` |
| Codex CLI | `codex mcp add visual-paradigm --url http://127.0.0.1:8931/mcp` |
| OpenCode | `opencode mcp add visual-paradigm --url http://127.0.0.1:8931/mcp` |
| Antigravity CLI (`agy`) | `agy mcp add visual-paradigm http://127.0.0.1:8931/mcp` |

Check: `claude mcp list`, `codex mcp list`, `opencode mcp list` or `agy mcp list`. These four
were tested with this server (Claude Code, Codex CLI 0.160, OpenCode 1.18, Antigravity CLI 1.2).

Other agents, configured as described in their official documentation (not tested here):

| Client | Configuration |
| --- | --- |
| Gemini CLI | `gemini mcp add -s user --transport http visual-paradigm http://127.0.0.1:8931/mcp` |
| Cursor | `~/.cursor/mcp.json` (or `.cursor/mcp.json` in a project): `{"mcpServers": {"visual-paradigm": {"url": "http://127.0.0.1:8931/mcp"}}}` |
| VS Code / GitHub Copilot | `.vscode/mcp.json`, or the user file via **MCP: Open User Configuration**: `{"servers": {"visual-paradigm": {"type": "http", "url": "http://127.0.0.1:8931/mcp"}}}` |
| Cline | `cline mcp add visual-paradigm http://127.0.0.1:8931/mcp --transport http`, or in `cline_mcp_settings.json`: `{"mcpServers": {"visual-paradigm": {"type": "streamableHttp", "url": "http://127.0.0.1:8931/mcp"}}}` |

Any other client that supports remote (Streamable HTTP) MCP servers works with the same URL;
the JSON keys differ per client, see its documentation.

Clients load the tool list when a session starts: restart the session after installing or
updating the plugin. The server only exists while Visual Paradigm is running.

#### 3. Install the skill (recommended)

The MCP server tells the model what each tool does; the **`visual-paradigm` skill** in
[`skills/visual-paradigm`](skills/visual-paradigm) adds how to work with it well: which tool to
use for which diagram, UML direction conventions, checking the result as an image, saving,
answering Visual Paradigm dialogs, and tested examples for every diagram type. Install it next to
the MCP server. It is a standard Agent Skill (a folder with `SKILL.md`), so the same folder works
in every client that supports skills:

| Client | Copy `skills/visual-paradigm` to |
| --- | --- |
| Claude Code | `~/.claude/skills/visual-paradigm` (or `.claude/skills/` in a project) |
| Codex CLI | `~/.codex/skills/visual-paradigm` |
| OpenCode | `~/.config/opencode/skills/visual-paradigm` |
| Antigravity CLI | `~/.gemini/antigravity/skills/visual-paradigm` (or `.agent/skills/` in a workspace) |
| Gemini CLI | `gemini skills install <path to the visual-paradigm folder>` |
| Cursor | `~/.cursor/skills/visual-paradigm` or `~/.agents/skills/visual-paradigm` (Cursor also reads `~/.claude/skills` and `~/.codex/skills`) |
| VS Code / GitHub Copilot | `~/.copilot/skills/visual-paradigm` or `~/.agents/skills/visual-paradigm` (also reads `~/.claude/skills`; in a repository `.github/skills/`) |

The skill folders for Gemini CLI, Cursor and VS Code are taken from their official documentation.

Commands (from a clone of this repository, or after unpacking `visual-paradigm-skill.zip` from the
release - then use `visual-paradigm` instead of `skills/visual-paradigm`):

macOS / Linux:

```bash
mkdir -p ~/.claude/skills && cp -r skills/visual-paradigm ~/.claude/skills/                    # Claude Code
mkdir -p ~/.codex/skills && cp -r skills/visual-paradigm ~/.codex/skills/                      # Codex CLI
mkdir -p ~/.config/opencode/skills && cp -r skills/visual-paradigm ~/.config/opencode/skills/  # OpenCode
mkdir -p ~/.gemini/antigravity/skills && cp -r skills/visual-paradigm ~/.gemini/antigravity/skills/  # Antigravity
```

Windows (PowerShell):

```powershell
foreach ($d in "$HOME\.claude\skills", "$HOME\.codex\skills",
               "$HOME\.config\opencode\skills", "$HOME\.gemini\antigravity\skills") {
  New-Item -ItemType Directory -Force $d | Out-Null
  Copy-Item -Recurse -Force skills\visual-paradigm $d
}
```

Copy only to the clients you use. Restart the client session afterwards.

## Development

### Building

```bash
./run build
./run test
./run package
./run all          # Build, test, package, and install
```

### Testing

- **Unit Tests**: JSON, HTTP server (sockets, chunked bodies, keep-alive), MCP JSON-RPC
  handling and reflective property setting
- **Manual Testing**: Visual Paradigm integration through the MCP tools

#### Unit Tests

```bash
./run test
```

#### MCP Protocol Testing

```bash
curl -s -X POST http://127.0.0.1:8931/mcp -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### Debugging

**MCP Server Logging**: Check `%APPDATA%\VisualParadigm\vp.log` (Windows) for lines starting
with `[vp-mcp]`:

- `MCP server listening on http://127.0.0.1:8931/mcp (26 tools)` - successful startup
- `MCP server stopped` - clean shutdown
- `MCP server could not start on port ...` - e.g. the port is used by another program

**Configuration**: `mcp.properties` in the installed plugin folder

```properties
port=8931
```

### Support

- **MCP Protocol**: [Model Context Protocol Specification](https://modelcontextprotocol.io/specification/2025-06-18/architecture)
- **Visual Paradigm**: [Plugin API Documentation](https://www.visual-paradigm.com/support/documents/pluginjavadoc/)

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Visual Paradigm is a trademark
of Visual Paradigm International; this project is not affiliated with it. The Visual Paradigm
Open API (`openapi.jar`) is not included; it is taken from the local Visual Paradigm
installation at build time.
