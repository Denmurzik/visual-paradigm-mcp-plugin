package com.brunnen.vp.mcp.vp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiagramBuilderTest {

  private static Map<String, String> existingComposition() {
    Map<String, String> ends = new HashMap<>();
    ends.put("from.name", "");
    ends.put("from.multiplicity", "Unspecified");
    ends.put("from.aggregationKind", "Composited");
    ends.put("to.name", "draft");
    ends.put("to.multiplicity", "1");
    ends.put("to.aggregationKind", "None");
    return ends;
  }

  @Test
  void sameEndsWhenNothingIsGiven() {
    assertTrue(DiagramBuilder.sameEnds(new LinkedHashMap<>(), existingComposition()));
    assertTrue(DiagramBuilder.sameEnds(null, existingComposition()));
  }

  @Test
  void sameEndsComparesRoleMultiplicityAndAggregation() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("from.aggregationKind", "composite"); // UML spelling of VP's "Composited"
    props.put("to.name", "draft");
    props.put("to.multiplicity", "1");
    assertTrue(DiagramBuilder.sameEnds(props, existingComposition()));

    props.put("to.name", "published");
    assertFalse(DiagramBuilder.sameEnds(props, existingComposition()));
  }

  @Test
  void unspecifiedMultiplicityEqualsEmpty() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("from.multiplicity", "");
    assertTrue(DiagramBuilder.sameEnds(props, existingComposition()));
    props.put("from.multiplicity", "0..*");
    assertFalse(DiagramBuilder.sameEnds(props, existingComposition()));
  }

  @Test
  void otherPropertiesDoNotPreventReuse() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("to.navigable", "true");
    props.put("documentation", "x");
    assertTrue(DiagramBuilder.sameEnds(props, existingComposition()));
  }
}
