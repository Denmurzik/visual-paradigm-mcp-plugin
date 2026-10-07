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

#### From a release (no build tools needed)

1. Install [Visual Paradigm](https://www.visual-paradigm.com/download/) 18.1 or later
   (Community Edition works).
2. Download `visual-paradigm-mcp-plugin-<version>.zip` from the
   [Releases](../../releases) page.
3. Close Visual Paradigm and unzip the archive into the Visual Paradigm plugins folder, so that
   you get `<plugins>/visual-paradigm-mcp-plugin/plugin.xml`:
   - Windows: `%APPDATA%\VisualParadigm\plugins`
   - Linux: `~/.config/VisualParadigm/plugins`
4. Start Visual Paradigm. `vp.log` (Windows: `%APPDATA%\VisualParadigm\vp.log`) shows
   `[vp-mcp] MCP server listening on http://127.0.0.1:8931/mcp`.
5. Connect your MCP client (see below).

Tested on Windows 11 with Visual Paradigm Community Edition 18.1.

#### From source

Build, test and install with the `./run` command. Set `MVN=/path/to/mvn` if Maven is not on
the `PATH`. Extra arguments are passed to Maven, e.g. `./run install -Dvp.home="D:/VP 18.1"`
if Visual Paradigm is not installed in `C:/Program Files/Visual Paradigm CE 18.1`:

1. **Build the plugin**:

   ```bash
   ./run build
   ```

2. **Package for distribution**:

   ```bash
   ./run package
   ```

3. **Install to Visual Paradigm** (close Visual Paradigm first, it locks the plugin jars):

   ```bash
   ./run install
   ```

4. **Start Visual Paradigm** - the MCP server will start automatically

### Using the MCP Server

Once Visual Paradigm is running with the plugin:

- **MCP Server Endpoint**: `http://127.0.0.1:8931/mcp` (Streamable HTTP)
- **Server Name**: `visual-paradigm`
- **Available Tools**: 26 tools (see above)

#### Connecting with Claude or MCP Clients

Claude Code:

```bash
claude mcp add --transport http -s user visual-paradigm http://127.0.0.1:8931/mcp
```

Other clients:

```json
{
  "mcpServers": {
    "visual-paradigm": {
      "type": "http",
      "url": "http://127.0.0.1:8931/mcp"
    }
  }
}
```

The server only exists while Visual Paradigm is running. Claude Code loads the tool list when a
session starts, so restart the session after installing or updating the plugin.

Tips for good results: let the AI look at its work with `vp_export_diagram_image`, save with
`vp_save_project` (changes are not saved automatically), and close dialogs that Visual Paradigm
opens - while a modal dialog is open, changes are refused with a "busy" message; the AI can
read and answer it with `vp_list_dialogs` / `vp_press_dialog_button`.

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
