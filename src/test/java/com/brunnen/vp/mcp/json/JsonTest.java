package com.brunnen.vp.mcp.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

  @Test
  @SuppressWarnings("unchecked")
  void parsesNestedStructures() {
    Object v =
        Json.parse(
            "{\"a\": 1, \"b\": [true, false, null], \"c\": {\"d\": \"x\\\"y\"}, \"e\": -2.5}");
    Map<String, Object> m = (Map<String, Object>) v;
    assertEquals(1L, m.get("a"));
    assertEquals(Arrays.asList(true, false, null), m.get("b"));
    assertEquals("x\"y", ((Map<String, Object>) m.get("c")).get("d"));
    assertEquals(-2.5, m.get("e"));
  }

  @Test
  void parsesUnicodeAndCyrillic() {
    assertEquals("Пользователь é", Json.parse("\"Пользователь \\u00e9\""));
  }

  @Test
  void roundTrips() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "Line\nbreak\t\"quoted\"");
    m.put("list", Arrays.asList(1, 2.5, "s", null, false));
    m.put("whole", 3.0);
    String json = Json.stringify(m);
    assertEquals(
        "{\"name\":\"Line\\nbreak\\t\\\"quoted\\\"\",\"list\":[1,2.5,\"s\",null,false],"
            + "\"whole\":3}",
        json);
    @SuppressWarnings("unchecked")
    Map<String, Object> back = (Map<String, Object>) Json.parse(json);
    assertEquals(m.get("name"), back.get("name"));
    assertEquals(5, ((List<?>) back.get("list")).size());
  }

  @Test
  void escapesControlCharacters() {
    assertEquals("\"\\u0001\"", Json.stringify("\u0001"));
  }

  @Test
  void parsesEmptyContainers() {
    assertTrue(((Map<?, ?>) Json.parse("{}")).isEmpty());
    assertTrue(((List<?>) Json.parse(" [ ] ")).isEmpty());
    assertNull(Json.parse("null"));
  }

  @Test
  void rejectsInvalidInput() {
    assertThrows(JsonException.class, () -> Json.parse("{\"a\":}"));
    assertThrows(JsonException.class, () -> Json.parse("[1,2"));
    assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
    assertThrows(JsonException.class, () -> Json.parse("{} x"));
    assertThrows(JsonException.class, () -> Json.parse(null));
  }
}
