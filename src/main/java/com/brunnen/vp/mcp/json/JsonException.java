package com.brunnen.vp.mcp.json;

/** Thrown when a JSON text cannot be parsed. */
public class JsonException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public JsonException(String message) {
    super(message);
  }
}
