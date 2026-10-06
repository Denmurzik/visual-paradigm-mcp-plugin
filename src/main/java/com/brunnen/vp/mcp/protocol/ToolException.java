package com.brunnen.vp.mcp.protocol;

/**
 * Expected failure inside a tool (bad argument, unknown id, ...). Reported to the client as a tool
 * result with {@code isError=true} and just the message, without a stack trace.
 */
public class ToolException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ToolException(String message) {
    super(message);
  }

  public ToolException(String message, Throwable cause) {
    super(message, cause);
  }
}
