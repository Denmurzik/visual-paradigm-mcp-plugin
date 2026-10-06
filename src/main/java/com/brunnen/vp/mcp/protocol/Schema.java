package com.brunnen.vp.mcp.protocol;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fluent builder for the JSON Schema of a tool's input object. */
public final class Schema {

  private final Map<String, Object> properties = new LinkedHashMap<>();
  private final List<String> required = new ArrayList<>();

  public static Schema object() {
    return new Schema();
  }

  public Schema str(String name, String description, boolean req) {
    return prop(name, typed("string", description), req);
  }

  /** String property restricted to {@code values}. */
  public Schema enumStr(String name, String description, boolean req, String... values) {
    Map<String, Object> p = typed("string", description);
    p.put("enum", Arrays.asList(values));
    return prop(name, p, req);
  }

  public Schema integer(String name, String description, boolean req) {
    return prop(name, typed("integer", description), req);
  }

  public Schema number(String name, String description, boolean req) {
    return prop(name, typed("number", description), req);
  }

  public Schema bool(String name, String description, boolean req) {
    return prop(name, typed("boolean", description), req);
  }

  /** Free-form object (additional properties allowed). */
  public Schema obj(String name, String description, boolean req) {
    Map<String, Object> p = typed("object", description);
    p.put("additionalProperties", true);
    return prop(name, p, req);
  }

  /** Array-of-strings property. */
  public Schema strArray(String name, String description, boolean req) {
    Map<String, Object> p = typed("array", description);
    p.put("items", typed("string", null));
    return prop(name, p, req);
  }

  /** Array of free-form objects. */
  public Schema objArray(String name, String description, boolean req) {
    Map<String, Object> p = typed("array", description);
    Map<String, Object> items = typed("object", null);
    items.put("additionalProperties", true);
    p.put("items", items);
    return prop(name, p, req);
  }

  /** Property with an explicit JSON schema. */
  public Schema prop(String name, Map<String, Object> schema, boolean req) {
    properties.put(name, schema);
    if (req) {
      required.add(name);
    }
    return this;
  }

  /** The JSON schema of the input object. */
  public Map<String, Object> build() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("type", "object");
    s.put("properties", properties);
    if (!required.isEmpty()) {
      s.put("required", required);
    }
    return s;
  }

  private static Map<String, Object> typed(String type, String description) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("type", type);
    if (description != null) {
      p.put("description", description);
    }
    return p;
  }
}
