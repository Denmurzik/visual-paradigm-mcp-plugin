package com.brunnen.vp.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.brunnen.vp.mcp.http.MiniHttpServer.Response;
import com.brunnen.vp.mcp.json.Json;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class McpHandlerTest {

  private McpHandler handler;

  @BeforeEach
  void setUp() {
    ToolRegistry tools = new ToolRegistry();
    tools.addReadOnly(
        "echo",
        "Echoes text",
        Schema.object().str("text", "Text", true),
        a -> ToolResult.text(a.str("text")));
    tools.add(
        "fail",
        "Always fails",
        Schema.object(),
        a -> {
          throw new ToolException("expected failure");
        });
    tools.add(
        "crash",
        "Unexpected exception",
        Schema.object(),
        a -> {
          throw new IllegalStateException("boom");
        });
    handler = new McpHandler("test", "1.0", "Use echo.", tools);
  }

  @Test
  @SuppressWarnings("unchecked")
  void initializeNegotiatesVersion() throws Exception {
    Map<String, Object> resp =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}");
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertEquals("2025-03-26", result.get("protocolVersion"));
    assertEquals("Use echo.", result.get("instructions"));
    assertTrue(((Map<String, Object>) result.get("capabilities")).containsKey("tools"));

    Map<String, Object> other =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");
    assertEquals(
        McpHandler.SUPPORTED_VERSIONS.get(0),
        ((Map<String, Object>) other.get("result")).get("protocolVersion"));
  }

  @Test
  void notificationsGet202() throws Exception {
    Response r =
        handler.handle(
            request(
                "POST",
                "/mcp",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                null));
    assertEquals(202, r.status);
    assertEquals(0, r.body.length);
  }

  @Test
  @SuppressWarnings("unchecked")
  void listsTools() throws Exception {
    Map<String, Object> resp = post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
    List<Object> tools = (List<Object>) ((Map<String, Object>) resp.get("result")).get("tools");
    assertEquals(3, tools.size());
    Map<String, Object> echo = (Map<String, Object>) tools.get(0);
    assertEquals("echo", echo.get("name"));
    Map<String, Object> schema = (Map<String, Object>) echo.get("inputSchema");
    assertEquals("object", schema.get("type"));
    assertEquals(List.of("text"), schema.get("required"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void callsTool() throws Exception {
    Map<String, Object> resp =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"привет\"}}}");
    Map<String, Object> result = (Map<String, Object>) resp.get("result");
    assertEquals(false, result.get("isError"));
    Map<String, Object> block = (Map<String, Object>) ((List<Object>) result.get("content")).get(0);
    assertEquals("привет", block.get("text"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void toolErrorsAreResults() throws Exception {
    Map<String, Object> missing =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"echo\",\"arguments\":{}}}");
    Map<String, Object> r1 = (Map<String, Object>) missing.get("result");
    assertEquals(true, r1.get("isError"));
    assertTrue(text(r1).contains("Missing required argument 'text'"));

    Map<String, Object> fail =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"fail\"}}");
    assertEquals("expected failure", text((Map<String, Object>) fail.get("result")));

    Map<String, Object> crash =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"crash\"}}");
    String crashText = text((Map<String, Object>) crash.get("result"));
    assertTrue(crashText.contains("IllegalStateException: boom"), crashText);
  }

  @Test
  @SuppressWarnings("unchecked")
  void protocolErrors() throws Exception {
    Map<String, Object> unknownTool =
        post(
            "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"x\"}}");
    assertEquals(-32602L, ((Map<String, Object>) unknownTool.get("error")).get("code"));

    Map<String, Object> unknownMethod = post("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"nope\"}");
    assertEquals(-32601L, ((Map<String, Object>) unknownMethod.get("error")).get("code"));

    Response bad = handler.handle(request("POST", "/mcp", "{broken", null));
    assertEquals(400, bad.status);
  }

  @Test
  void batchAndPing() throws Exception {
    Response r =
        handler.handle(
            request(
                "POST",
                "/mcp",
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                    + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}]",
                null));
    assertEquals(200, r.status);
    List<?> list = (List<?>) Json.parse(new String(r.body, StandardCharsets.UTF_8));
    assertEquals(1, list.size());
  }

  @Test
  void httpRules() throws Exception {
    assertEquals(405, handler.handle(request("GET", "/mcp", "", null)).status);
    assertEquals(404, handler.handle(request("POST", "/other", "{}", null)).status);
    assertEquals(
        403, handler.handle(request("POST", "/mcp", "{}", "http://evil.example.com")).status);
    assertTrue(McpHandler.isAllowedOrigin("http://localhost:3000"));
    assertTrue(McpHandler.isAllowedOrigin("http://127.0.0.1"));
    assertTrue(McpHandler.isAllowedOrigin(null));
    assertFalse(McpHandler.isAllowedOrigin("https://example.org"));
  }

  @Test
  void clientResponsesAreIgnored() {
    Map<String, Object> msg = new LinkedHashMap<>();
    msg.put("jsonrpc", "2.0");
    msg.put("id", 1);
    msg.put("result", new LinkedHashMap<>());
    assertNull(handler.dispatch(msg));
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> result) {
    Map<String, Object> block = (Map<String, Object>) ((List<Object>) result.get("content")).get(0);
    return (String) block.get("text");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String body) throws Exception {
    Response r = handler.handle(request("POST", "/mcp", body, null));
    assertEquals(200, r.status);
    assertEquals("application/json", r.contentType);
    return (Map<String, Object>) Json.parse(new String(r.body, StandardCharsets.UTF_8));
  }

  /** Builds a request through the package-private constructor. */
  static com.brunnen.vp.mcp.http.MiniHttpServer.Request request(
      String method, String path, String body, String origin) throws Exception {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("content-type", "application/json");
    if (origin != null) {
      headers.put("origin", origin);
    }
    Constructor<com.brunnen.vp.mcp.http.MiniHttpServer.Request> c =
        com.brunnen.vp.mcp.http.MiniHttpServer.Request.class.getDeclaredConstructor(
            String.class, String.class, Map.class, byte[].class);
    c.setAccessible(true);
    return c.newInstance(method, path, headers, body.getBytes(StandardCharsets.UTF_8));
  }
}
