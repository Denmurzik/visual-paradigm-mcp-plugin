package com.brunnen.vp.mcp.vp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Point;
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
}
