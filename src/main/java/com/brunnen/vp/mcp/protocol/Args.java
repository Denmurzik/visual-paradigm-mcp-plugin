package com.brunnen.vp.mcp.protocol;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Typed access to tool call arguments. Missing required values raise {@link ToolException}. */
public final class Args {

  private final Map<String, Object> values;

  public Args(Map<String, Object> values) {
    this.values = values == null ? Collections.emptyMap() : values;
  }

  public Map<String, Object> raw() {
    return values;
  }

  public boolean has(String name) {
    return values.get(name) != null;
  }

  /** Required argument as string. */
  public String str(String name) {
    Object v = values.get(name);
    if (v == null) {
      throw new ToolException("Missing required argument '" + name + "'");
    }
    return String.valueOf(v);
  }

  public String str(String name, String def) {
    Object v = values.get(name);
    return v == null ? def : String.valueOf(v);
  }

  /** Optional integer argument, {@code null} when absent. */
  public Integer intOrNull(String name) {
    Object v = values.get(name);
    if (v == null) {
      return null;
    }
    if (v instanceof Number) {
      return ((Number) v).intValue();
    }
    try {
      return (int) Double.parseDouble(v.toString());
    } catch (NumberFormatException e) {
      throw new ToolException("Argument '" + name + "' must be a number");
    }
  }

  public int integer(String name, int def) {
    Integer v = intOrNull(name);
    return v == null ? def : v;
  }

  /** Optional boolean argument. */
  public boolean bool(String name, boolean def) {
    Object v = values.get(name);
    if (v == null) {
      return def;
    }
    if (v instanceof Boolean) {
      return (Boolean) v;
    }
    return Boolean.parseBoolean(v.toString());
  }

  /** Optional object argument, empty when absent. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> map(String name) {
    Object v = values.get(name);
    if (v == null) {
      return Collections.emptyMap();
    }
    if (!(v instanceof Map)) {
      throw new ToolException("Argument '" + name + "' must be an object");
    }
    return (Map<String, Object>) v;
  }

  /** Optional array argument, empty when absent. */
  @SuppressWarnings("unchecked")
  public List<Object> list(String name) {
    Object v = values.get(name);
    if (v == null) {
      return Collections.emptyList();
    }
    if (!(v instanceof List)) {
      throw new ToolException("Argument '" + name + "' must be an array");
    }
    return (List<Object>) v;
  }

  /** Wraps a nested object value. */
  @SuppressWarnings("unchecked")
  public static Args of(Object value, String what) {
    if (value != null && !(value instanceof Map)) {
      throw new ToolException(what + " must be an object");
    }
    return new Args((Map<String, Object>) value);
  }
}
