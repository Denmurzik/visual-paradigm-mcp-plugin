package com.brunnen.vp.mcp.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ordered set of tools exposed by the MCP server. */
public final class ToolRegistry {

  /** Tool implementation. */
  @FunctionalInterface
  public interface ToolFunction {
    ToolResult call(Args args) throws Exception;
  }

  /** A registered tool. */
  public static final class Tool {
    final String name;
    final String description;
    final Map<String, Object> inputSchema;
    final boolean readOnly;
    final ToolFunction function;

    Tool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        boolean readOnly,
        ToolFunction function) {
      this.name = name;
      this.description = description;
      this.inputSchema = inputSchema;
      this.readOnly = readOnly;
      this.function = function;
    }

    Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("name", name);
      m.put("description", description);
      m.put("inputSchema", inputSchema);
      Map<String, Object> annotations = new LinkedHashMap<>();
      annotations.put("readOnlyHint", readOnly);
      m.put("annotations", annotations);
      return m;
    }
  }

  private final Map<String, Tool> tools = new LinkedHashMap<>();

  /** Registers a tool that modifies the model. */
  public ToolRegistry add(String name, String description, Schema schema, ToolFunction fn) {
    return add(name, description, schema, false, fn);
  }

  /** Registers a read-only tool. */
  public ToolRegistry addReadOnly(String name, String description, Schema schema, ToolFunction fn) {
    return add(name, description, schema, true, fn);
  }

  private ToolRegistry add(
      String name, String description, Schema schema, boolean readOnly, ToolFunction fn) {
    if (tools.containsKey(name)) {
      throw new IllegalArgumentException("Duplicate tool " + name);
    }
    tools.put(name, new Tool(name, description, schema.build(), readOnly, fn));
    return this;
  }

  public Tool get(String name) {
    return tools.get(name);
  }

  /** Tool descriptions for {@code tools/list}. */
  public List<Map<String, Object>> describe() {
    List<Map<String, Object>> list = new ArrayList<>();
    for (Tool t : tools.values()) {
      list.add(t.toMap());
    }
    return list;
  }

  public int size() {
    return tools.size();
  }
}
