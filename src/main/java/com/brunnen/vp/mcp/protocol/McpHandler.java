package com.brunnen.vp.mcp.protocol;

import com.brunnen.vp.mcp.http.MiniHttpServer.Handler;
import com.brunnen.vp.mcp.http.MiniHttpServer.Request;
import com.brunnen.vp.mcp.http.MiniHttpServer.Response;
import com.brunnen.vp.mcp.json.Json;
import com.brunnen.vp.mcp.json.JsonException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP over the Streamable HTTP transport (POST-only, JSON responses, no server-initiated stream).
 *
 * <p>Implements JSON-RPC 2.0 dispatch for {@code initialize}, {@code ping}, {@code tools/list} and
 * {@code tools/call}. Contains no Visual Paradigm code so it can be unit-tested in isolation.
 */
public final class McpHandler implements Handler {

  static final List<String> SUPPORTED_VERSIONS =
      Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05");

  private static final int PARSE_ERROR = -32700;
  private static final int INVALID_REQUEST = -32600;
  private static final int METHOD_NOT_FOUND = -32601;
  private static final int INVALID_PARAMS = -32602;
  private static final int INTERNAL_ERROR = -32603;

  private final String serverName;
  private final String serverVersion;
  private final String instructions;
  private final ToolRegistry tools;

  /** Handler announcing the given server identity, instructions and tools. */
  public McpHandler(
      String serverName, String serverVersion, String instructions, ToolRegistry tools) {
    this.serverName = serverName;
    this.serverVersion = serverVersion;
    this.instructions = instructions;
    this.tools = tools;
  }

  @Override
  public Response handle(Request request) {
    if (!"/mcp".equals(request.path) && !"/".equals(request.path)) {
      return Response.text(404, "Not found. The MCP endpoint is /mcp");
    }
    if (!isAllowedOrigin(request.header("origin"))) {
      return Response.text(403, "Forbidden origin");
    }
    if (!"POST".equals(request.method)) {
      Response r = Response.text(405, "Only POST is supported");
      r.headers.put("Allow", "POST");
      return r;
    }
    Object message;
    try {
      message = Json.parse(request.bodyAsString());
    } catch (JsonException e) {
      return Response.json(400, Json.stringify(error(null, PARSE_ERROR, e.getMessage())));
    }
    if (message instanceof List) {
      List<Object> responses = new ArrayList<>();
      for (Object m : (List<?>) message) {
        Map<String, Object> resp = dispatch(m);
        if (resp != null) {
          responses.add(resp);
        }
      }
      return responses.isEmpty()
          ? Response.empty(202)
          : Response.json(200, Json.stringify(responses));
    }
    Map<String, Object> resp = dispatch(message);
    return resp == null ? Response.empty(202) : Response.json(200, Json.stringify(resp));
  }

  /** Browsers send an Origin header; only local pages may talk to the server (DNS rebinding). */
  static boolean isAllowedOrigin(String origin) {
    if (origin == null || origin.isEmpty() || "null".equals(origin)) {
      return true;
    }
    try {
      String host = URI.create(origin).getHost();
      return "localhost".equalsIgnoreCase(host)
          || "127.0.0.1".equals(host)
          || "[::1]".equals(host)
          || "::1".equals(host);
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Returns the JSON-RPC response, or {@code null} for notifications and responses. */
  @SuppressWarnings("unchecked")
  Map<String, Object> dispatch(Object raw) {
    if (!(raw instanceof Map)) {
      return error(null, INVALID_REQUEST, "Message must be a JSON object");
    }
    Map<String, Object> msg = (Map<String, Object>) raw;
    Object id = msg.get("id");
    Object method = msg.get("method");
    if (!(method instanceof String)) {
      // a response from the client, or garbage without id: nothing to answer
      return id == null || msg.containsKey("result") || msg.containsKey("error")
          ? null
          : error(id, INVALID_REQUEST, "Missing method");
    }
    if (!msg.containsKey("id")) {
      return null; // notification (e.g. notifications/initialized)
    }
    Object params = msg.get("params");
    Map<String, Object> p =
        params instanceof Map ? (Map<String, Object>) params : new LinkedHashMap<>();
    try {
      switch ((String) method) {
        case "initialize":
          return result(id, initialize(p));
        case "ping":
          return result(id, new LinkedHashMap<>());
        case "tools/list":
          Map<String, Object> list = new LinkedHashMap<>();
          list.put("tools", tools.describe());
          return result(id, list);
        case "tools/call":
          return callTool(id, p);
        default:
          return error(id, METHOD_NOT_FOUND, "Method not found: " + method);
      }
    } catch (RuntimeException e) {
      return error(id, INTERNAL_ERROR, String.valueOf(e));
    }
  }

  private Map<String, Object> initialize(Map<String, Object> params) {
    Object requested = params.get("protocolVersion");
    String version =
        requested instanceof String && SUPPORTED_VERSIONS.contains(requested)
            ? (String) requested
            : SUPPORTED_VERSIONS.get(0);
    Map<String, Object> toolsCap = new LinkedHashMap<>();
    toolsCap.put("listChanged", false);
    Map<String, Object> caps = new LinkedHashMap<>();
    caps.put("tools", toolsCap);
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("name", serverName);
    info.put("version", serverVersion);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("protocolVersion", version);
    r.put("capabilities", caps);
    r.put("serverInfo", info);
    if (instructions != null) {
      r.put("instructions", instructions);
    }
    return r;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> callTool(Object id, Map<String, Object> params) {
    Object name = params.get("name");
    if (!(name instanceof String)) {
      return error(id, INVALID_PARAMS, "tools/call requires a 'name'");
    }
    ToolRegistry.Tool tool = tools.get((String) name);
    if (tool == null) {
      return error(id, INVALID_PARAMS, "Unknown tool: " + name);
    }
    Object arguments = params.get("arguments");
    if (arguments != null && !(arguments instanceof Map)) {
      return error(id, INVALID_PARAMS, "'arguments' must be an object");
    }
    ToolResult result;
    try {
      result = tool.function.call(new Args((Map<String, Object>) arguments));
      if (result == null) {
        result = ToolResult.text("OK");
      }
    } catch (ToolException e) {
      result = ToolResult.error(e.getMessage());
    } catch (Exception e) {
      result = ToolResult.error(describe(e));
    }
    return result(id, result.toMap());
  }

  /** Unexpected failure: message plus a trimmed stack trace so the caller can diagnose it. */
  static String describe(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null && root.getCause() != root) {
      if (root.getCause() instanceof ToolException) {
        return root.getCause().getMessage();
      }
      root = root.getCause();
    }
    StringWriter sw = new StringWriter();
    root.printStackTrace(new PrintWriter(sw));
    String trace = sw.toString();
    String[] lines = trace.split("\\R");
    StringBuilder sb = new StringBuilder("Error: ").append(root).append('\n');
    for (int i = 1; i < Math.min(lines.length, 12); i++) {
      sb.append(lines[i]).append('\n');
    }
    return sb.toString();
  }

  private static Map<String, Object> result(Object id, Object result) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("jsonrpc", "2.0");
    r.put("id", id);
    r.put("result", result);
    return r;
  }

  private static Map<String, Object> error(Object id, int code, String message) {
    Map<String, Object> err = new LinkedHashMap<>();
    err.put("code", code);
    err.put("message", message);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("jsonrpc", "2.0");
    r.put("id", id);
    r.put("error", err);
    return r;
  }
}
