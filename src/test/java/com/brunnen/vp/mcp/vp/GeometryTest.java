package com.brunnen.vp.mcp.vp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.brunnen.vp.mcp.protocol.ToolException;
import java.awt.Point;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeometryTest {

  @Test
  void clipsToRectangleBorder() {
    // 100x40 box at (0,0), centre (50,20)
    assertEquals(new Point(100, 20), Geometry.clip(0, 0, 100, 40, new Point(300, 20)));
    assertEquals(new Point(0, 20), Geometry.clip(0, 0, 100, 40, new Point(-300, 20)));
    assertEquals(new Point(50, 40), Geometry.clip(0, 0, 100, 40, new Point(50, 500)));
    // diagonal: leaves through the top edge first because the box is wide
    assertEquals(new Point(70, 0), Geometry.clip(0, 0, 100, 40, new Point(150, -80)));
  }

  @Test
  void targetInsideOrAtCentre() {
    assertEquals(new Point(50, 20), Geometry.clip(0, 0, 100, 40, new Point(50, 20)));
    assertEquals(new Point(60, 25), Geometry.clip(0, 0, 100, 40, new Point(60, 25)));
  }

  @Test
  void clipsToEllipse() {
    // ellipse 100x40 at (0,0): along the axes it matches the box, diagonally it is inside
    assertEquals(new Point(100, 20), Geometry.clipEllipse(0, 0, 100, 40, new Point(300, 20)));
    assertEquals(new Point(50, 0), Geometry.clipEllipse(0, 0, 100, 40, new Point(50, -100)));
    Point p = Geometry.clipEllipse(0, 0, 100, 40, new Point(150, 120));
    double nx = (p.x - 50) / 50.0;
    double ny = (p.y - 20) / 20.0;
    assertEquals(1.0, nx * nx + ny * ny, 0.1);
  }

  @Test
  void captionGoesBesideMiddleOfSteepLine() {
    // vertical line from (100,100) to (100,160): label 60x14 to the right of y=130
    assertEquals(
        new Point(106, 123),
        Geometry.captionBeside(new Point(100, 100), new Point(100, 160), 60, 14));
  }

  @Test
  void selfMessageLabelGoesAboveTheLoop() {
    assertEquals(new Point(439, 318), Geometry.captionAboveLoop(new Point(435, 335), 15));
  }

  @Test
  void parallelLinesBendAlternatelyToBothSides() {
    Point a = new Point(0, 0);
    Point b = new Point(100, 0);
    assertNull(Geometry.parallelBend(a, b, 0));
    assertEquals(new Point(50, 30), Geometry.parallelBend(a, b, 1));
    assertEquals(new Point(50, -30), Geometry.parallelBend(a, b, 2));
    assertEquals(new Point(50, 60), Geometry.parallelBend(a, b, 3));
    // vertical line: the bend goes sideways
    assertEquals(new Point(-30, 50), Geometry.parallelBend(a, new Point(0, 100), 1));
  }

  @Test
  void readsWaypointsAsPairsOrObjects() {
    Map<String, Object> xy = new LinkedHashMap<>();
    xy.put("x", 10.4);
    xy.put("y", 20);
    List<Object> raw = Arrays.asList(Arrays.asList(1, 2), xy);
    assertEquals(Arrays.asList(new Point(1, 2), new Point(10, 20)), Geometry.waypoints(raw));
    assertThrows(
        ToolException.class, () -> Geometry.waypoints(Arrays.asList(Arrays.asList("a", 1))));
    assertThrows(ToolException.class, () -> Geometry.waypoints(Arrays.asList(5)));
  }
}
