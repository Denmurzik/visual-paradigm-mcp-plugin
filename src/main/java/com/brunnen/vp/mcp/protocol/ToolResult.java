package com.brunnen.vp.mcp.protocol;

import com.brunnen.vp.mcp.json.Json;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of an MCP tool call: a list of content blocks plus an error flag. */
public final class ToolResult {

  private final List<Map<String, Object>> content = new ArrayList<>();
  private boolean error;

  private ToolResult() {}

  /** A text block holding the JSON serialization of {@code value}. */
  public static ToolResult json(Object value) {
    return new ToolResult().addText(Json.stringify(value));
  }

  public static ToolResult text(String text) {
    return new ToolResult().addText(text);
  }

  /** Error result shown to the model. */
  public static ToolResult error(String message) {
    ToolResult r = new ToolResult().addText(message);
    r.error = true;
    return r;
  }

  /** Appends a text block. */
  public ToolResult addText(String text) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "text");
    block.put("text", text);
    content.add(block);
    return this;
  }

  /** Appends an image block. */
  public ToolResult addImage(String base64, String mimeType) {
    Map<String, Object> block = new LinkedHashMap<>();
    block.put("type", "image");
    block.put("data", base64);
    block.put("mimeType", mimeType);
    content.add(block);
    return this;
  }

  public boolean isError() {
    return error;
  }

  public List<Map<String, Object>> getContent() {
    return Collections.unmodifiableList(content);
  }

  Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("content", content);
    m.put("isError", error);
    return m;
  }
}
