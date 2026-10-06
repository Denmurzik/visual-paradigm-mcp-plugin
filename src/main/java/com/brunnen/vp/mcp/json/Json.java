package com.brunnen.vp.mcp.json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer.
 *
 * <p>Objects map to {@link LinkedHashMap}, arrays to {@link ArrayList}, numbers to {@link Long}
 * (integral) or {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 */
public final class Json {

  private final String src;
  private int pos;

  private Json(String src) {
    this.src = src;
  }

  /** Parses a JSON text. */
  public static Object parse(String text) {
    if (text == null) {
      throw new JsonException("JSON text is null");
    }
    Json p = new Json(text);
    p.skipWs();
    Object value = p.readValue();
    p.skipWs();
    if (p.pos != p.src.length()) {
      throw p.error("Unexpected trailing characters");
    }
    return value;
  }

  /** Serializes a value made of maps, collections, arrays, strings, numbers and booleans. */
  public static String stringify(Object value) {
    StringBuilder sb = new StringBuilder();
    write(sb, value);
    return sb.toString();
  }

  // ---------------------------------------------------------------- reader

  private Object readValue() {
    if (pos >= src.length()) {
      throw error("Unexpected end of input");
    }
    char c = src.charAt(pos);
    switch (c) {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return readString();
      case 't':
        expectWord("true");
        return Boolean.TRUE;
      case 'f':
        expectWord("false");
        return Boolean.FALSE;
      case 'n':
        expectWord("null");
        return null;
      default:
        if (c == '-' || (c >= '0' && c <= '9')) {
          return readNumber();
        }
        throw error("Unexpected character '" + c + "'");
    }
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<>();
    pos++;
    skipWs();
    if (peek() == '}') {
      pos++;
      return map;
    }
    while (true) {
      skipWs();
      if (peek() != '"') {
        throw error("Expected object key");
      }
      String key = readString();
      skipWs();
      if (peek() != ':') {
        throw error("Expected ':'");
      }
      pos++;
      skipWs();
      map.put(key, readValue());
      skipWs();
      char c = peek();
      pos++;
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("Expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<>();
    pos++;
    skipWs();
    if (peek() == ']') {
      pos++;
      return list;
    }
    while (true) {
      skipWs();
      list.add(readValue());
      skipWs();
      char c = peek();
      pos++;
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("Expected ',' or ']'");
      }
    }
  }

  private String readString() {
    pos++; // opening quote
    StringBuilder sb = new StringBuilder();
    while (true) {
      if (pos >= src.length()) {
        throw error("Unterminated string");
      }
      char c = src.charAt(pos++);
      if (c == '"') {
        return sb.toString();
      }
      if (c != '\\') {
        sb.append(c);
        continue;
      }
      if (pos >= src.length()) {
        throw error("Unterminated escape");
      }
      char e = src.charAt(pos++);
      switch (e) {
        case '"':
        case '\\':
        case '/':
          sb.append(e);
          break;
        case 'b':
          sb.append('\b');
          break;
        case 'f':
          sb.append('\f');
          break;
        case 'n':
          sb.append('\n');
          break;
        case 'r':
          sb.append('\r');
          break;
        case 't':
          sb.append('\t');
          break;
        case 'u':
          if (pos + 4 > src.length()) {
            throw error("Bad unicode escape");
          }
          try {
            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
          } catch (NumberFormatException ex) {
            throw error("Bad unicode escape");
          }
          pos += 4;
          break;
        default:
          throw error("Bad escape '\\" + e + "'");
      }
    }
  }

  private Object readNumber() {
    int start = pos;
    if (peek() == '-') {
      pos++;
    }
    boolean integral = true;
    while (pos < src.length()) {
      char c = src.charAt(pos);
      if (c >= '0' && c <= '9') {
        pos++;
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        integral = false;
        pos++;
      } else {
        break;
      }
    }
    String num = src.substring(start, pos);
    try {
      if (integral) {
        return Long.parseLong(num);
      }
      return Double.parseDouble(num);
    } catch (NumberFormatException ex) {
      throw error("Bad number '" + num + "'");
    }
  }

  private void expectWord(String word) {
    if (!src.startsWith(word, pos)) {
      throw error("Expected '" + word + "'");
    }
    pos += word.length();
  }

  private char peek() {
    if (pos >= src.length()) {
      throw error("Unexpected end of input");
    }
    return src.charAt(pos);
  }

  private void skipWs() {
    while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
      pos++;
    }
  }

  private JsonException error(String msg) {
    return new JsonException(msg + " at position " + pos);
  }

  // ---------------------------------------------------------------- writer

  private static void write(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof String) {
      writeString(sb, (String) v);
    } else if (v instanceof Boolean) {
      sb.append(v.toString());
    } else if (v instanceof Double || v instanceof Float) {
      double d = ((Number) v).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        sb.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        sb.append((long) d);
      } else {
        sb.append(d);
      }
    } else if (v instanceof Number) {
      sb.append(v.toString());
    } else if (v instanceof Map) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        write(sb, e.getValue());
      }
      sb.append('}');
    } else if (v instanceof Collection) {
      sb.append('[');
      boolean first = true;
      for (Object item : (Collection<?>) v) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        write(sb, item);
      }
      sb.append(']');
    } else if (v instanceof Object[]) {
      List<Object> list = new ArrayList<>();
      for (Object o : (Object[]) v) {
        list.add(o);
      }
      write(sb, list);
    } else if (v instanceof int[]) {
      List<Object> list = new ArrayList<>();
      for (int i : (int[]) v) {
        list.add(i);
      }
      write(sb, list);
    } else {
      writeString(sb, v.toString());
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
