package com.brunnen.vp.mcp.vp;

import com.vp.plugin.diagram.ICaptionUIModel;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IMessage;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moving shapes and laying out diagrams. Visual Paradigm keeps connector points and child positions
 * (absolute coordinates) unchanged when a shape is moved through the API, so this class moves
 * children along and re-routes the attached connectors.
 */
final class Geometry {

  /** Shapes that contain other shapes on use case diagrams. */
  static final Set<String> CONTAINERS =
      new HashSet<>(Arrays.asList("System", "Package", "Subsystem", "Collaboration"));

  /** Container shapes whose children VP manages itself (lanes); never resized here. */
  static final Set<String> MANAGED =
      new HashSet<>(
          Arrays.asList(
              "ActivitySwimlane2", "ActivityPartitionHeader", "ActivitySwimlane2Compartment"));

  private Geometry() {}

  // ------------------------------------------------------------- moving

  /** Sets the bounds of a shape, moves its children with it and re-routes its connectors. */
  static void moveShape(IShapeUIModel s, int x, int y, int w, int h) {
    int dx = x - s.getX();
    int dy = y - s.getY();
    List<IShapeUIModel> children = descendants(s);
    List<IShapeUIModel> moved = new ArrayList<>(children);
    moved.add(s);
    Map<IShapeUIModel, Point> before = new LinkedHashMap<>();
    Map<IShapeUIModel, Point> captions = new LinkedHashMap<>();
    for (IShapeUIModel c : moved) {
      before.put(c, new Point(c.getX(), c.getY()));
      ICaptionUIModel cap = c.getCaptionUIModel();
      if (cap != null) {
        captions.put(c, new Point(cap.getX(), cap.getY()));
      }
    }
    s.setBounds(x, y, w, h);
    if (dx != 0 || dy != 0) {
      for (IShapeUIModel c : children) {
        Point p = before.get(c);
        if (c.getX() == p.x && c.getY() == p.y) { // VP did not move it
          c.setLocation(p.x + dx, p.y + dy);
        }
      }
      // captions outside the shape (actor names) have absolute coordinates as well
      for (Map.Entry<IShapeUIModel, Point> e : captions.entrySet()) {
        ICaptionUIModel cap = e.getKey().getCaptionUIModel();
        Point p = e.getValue();
        if (cap != null && cap.getX() == p.x && cap.getY() == p.y) {
          cap.setX(p.x + dx);
          cap.setY(p.y + dy);
        }
      }
    }
    for (IShapeUIModel c : moved) {
      DiagramBuilder.resetCaption(c);
    }
    Set<String> done = new HashSet<>();
    for (IShapeUIModel m : moved) {
      for (IConnectorUIModel c : attached(m)) {
        if (done.add(c.getId())) {
          reroute(c);
        }
      }
    }
  }

  static List<IShapeUIModel> descendants(IShapeUIModel s) {
    List<IShapeUIModel> out = new ArrayList<>();
    IShapeUIModel[] kids = s.toChildArray();
    if (kids != null) {
      for (IShapeUIModel k : kids) {
        out.add(k);
        out.addAll(descendants(k));
      }
    }
    return out;
  }

  static List<IConnectorUIModel> attached(IDiagramElement s) {
    List<IConnectorUIModel> out = new ArrayList<>();
    IConnectorUIModel[] from = s.toFromConnectorArray();
    if (from != null) {
      out.addAll(Arrays.asList(from));
    }
    IConnectorUIModel[] to = s.toToConnectorArray();
    if (to != null) {
      out.addAll(Arrays.asList(to));
    }
    return out;
  }

  /** Straight connector between the borders of its two shapes (messages stay horizontal). */
  static void reroute(IConnectorUIModel c) {
    IDiagramElement from = c.getFromShape() != null ? c.getFromShape() : c.getFromConnector();
    IDiagramElement to = c.getToShape() != null ? c.getToShape() : c.getToConnector();
    if (from == null || to == null) {
      return;
    }
    Point[] old = c.getPoints();
    Point[] pts;
    if (c.getModelElement() instanceof IMessage && old != null && old.length > 0) {
      int y = old[0].y;
      if (from.getId().equals(to.getId())) {
        int dx = centerX(from) - old[0].x;
        pts = new Point[old.length];
        for (int i = 0; i < old.length; i++) {
          pts[i] = new Point(old[i].x + dx, old[i].y);
        }
      } else {
        pts = new Point[] {new Point(centerX(from), y), new Point(centerX(to), y)};
      }
    } else {
      Point a = center(from);
      Point b = center(to);
      pts = new Point[] {border(from, b), border(to, a)};
    }
    c.clearPoints();
    for (Point p : pts) {
      c.addPoint(p);
    }
    c.setRequestRebuild(true);
    DiagramBuilder.resetCaption(c);
  }

  static Point center(IDiagramElement e) {
    return new Point(e.getX() + e.getWidth() / 2, e.getY() + e.getHeight() / 2);
  }

  private static int centerX(IDiagramElement e) {
    return e.getX() + e.getWidth() / 2;
  }

  /** Shapes drawn as ellipses/circles: connectors must end on the curve, not the bounding box. */
  static final Set<String> ELLIPTIC =
      new HashSet<>(
          Arrays.asList(
              "UseCase",
              "InitialNode",
              "ActivityFinalNode",
              "FlowFinalNode",
              "InitialPseudoState",
              "FinalState2",
              "Choice",
              "Junction"));

  /** Point where the line from the center of {@code e} towards {@code target} leaves it. */
  static Point border(IDiagramElement e, Point target) {
    if (ELLIPTIC.contains(e.getShapeType())) {
      return clipEllipse(e.getX(), e.getY(), e.getWidth(), e.getHeight(), target);
    }
    return clip(e.getX(), e.getY(), e.getWidth(), e.getHeight(), target);
  }

  static Point clipEllipse(int x, int y, int w, int h, Point target) {
    double cx = x + w / 2.0;
    double cy = y + h / 2.0;
    double dx = target.x - cx;
    double dy = target.y - cy;
    double a = w / 2.0;
    double b = h / 2.0;
    if ((dx == 0 && dy == 0) || a <= 0 || b <= 0) {
      return new Point((int) cx, (int) cy);
    }
    double t = 1.0 / Math.sqrt((dx * dx) / (a * a) + (dy * dy) / (b * b));
    t = Math.min(t, 1.0);
    return new Point((int) Math.round(cx + dx * t), (int) Math.round(cy + dy * t));
  }

  static Point clip(int x, int y, int w, int h, Point target) {
    double cx = x + w / 2.0;
    double cy = y + h / 2.0;
    double dx = target.x - cx;
    double dy = target.y - cy;
    if (dx == 0 && dy == 0) {
      return new Point((int) cx, (int) cy);
    }
    double sx = dx == 0 ? Double.MAX_VALUE : (w / 2.0) / Math.abs(dx);
    double sy = dy == 0 ? Double.MAX_VALUE : (h / 2.0) / Math.abs(dy);
    double s = Math.min(Math.min(sx, sy), 1.0);
    return new Point((int) Math.round(cx + dx * s), (int) Math.round(cy + dy * s));
  }

  // ------------------------------------------------------------- layout

  /** True for diagrams that VP's automatic layout breaks: boundaries with nested shapes. */
  static boolean hasFilledContainers(IDiagramUIModel d) {
    for (IShapeUIModel s : d.toShapeUIModelArray()) {
      if (CONTAINERS.contains(s.getShapeType()) && s.childrenCount() > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Layout for use case style diagrams: actors in a column on the left, every boundary to the right
   * of them with its children in a grid (keeping their current reading order), remaining top-level
   * shapes in a column on the far right. All connectors are re-routed.
   */
  static void containerLayout(IDiagramUIModel d) {
    List<IShapeUIModel> actors = new ArrayList<>();
    List<IShapeUIModel> containers = new ArrayList<>();
    List<IShapeUIModel> others = new ArrayList<>();
    for (IShapeUIModel s : d.toShapeUIModelArray()) {
      if (s.getParent() != null) {
        continue;
      }
      String t = s.getShapeType();
      if (CONTAINERS.contains(t) && s.childrenCount() > 0) {
        containers.add(s);
      } else if ("Actor".equals(t)) {
        actors.add(s);
      } else {
        others.add(s);
      }
    }
    Comparator<IShapeUIModel> readingOrder =
        Comparator.<IShapeUIModel>comparingInt(IShapeUIModel::getY)
            .thenComparingInt(IShapeUIModel::getX);
    actors.sort(readingOrder);
    containers.sort(readingOrder);
    others.sort(readingOrder);

    int margin = 40;
    int actorColumn = 0;
    for (IShapeUIModel a : actors) {
      actorColumn = Math.max(actorColumn, Math.max(a.getWidth(), 100));
    }
    int left = margin + (actors.isEmpty() ? 0 : actorColumn + 100);

    // containers stacked vertically
    int y = margin;
    int right = left;
    for (IShapeUIModel c : containers) {
      int[] size = gridChildren(c, left, y);
      right = Math.max(right, left + size[0]);
      y += size[1] + margin;
    }
    int contentBottom = Math.max(y - margin, margin);

    // actors spread along the containers' height, centred in their column
    if (!actors.isEmpty()) {
      int slot = Math.max(130, (contentBottom - margin) / actors.size());
      int ay = margin + Math.max(0, (contentBottom - margin - slot * actors.size()) / 2);
      for (IShapeUIModel a : actors) {
        int ax = margin + (actorColumn - a.getWidth()) / 2;
        moveShape(a, ax, ay + (slot - a.getHeight()) / 2, a.getWidth(), a.getHeight());
        ay += slot;
      }
    }

    // anything else (notes, loose use cases, packages without content) on the right
    int ox = right + 100;
    int oy = margin;
    for (IShapeUIModel o : others) {
      moveShape(o, ox, oy, o.getWidth(), o.getHeight());
      oy += o.getHeight() + margin;
    }

    for (IConnectorUIModel c : d.toConnectorUIModelArray()) {
      reroute(c);
    }
    for (IDiagramElement e : d.toDiagramElementArray()) {
      DiagramBuilder.resetCaption(e);
    }
  }

  /**
   * Places the container at (x, y), its direct children in a grid inside it and sizes it to fit.
   *
   * @return width and height of the container
   */
  private static int[] gridChildren(IShapeUIModel c, int x, int y) {
    List<IShapeUIModel> kids = new ArrayList<>(Arrays.asList(c.toChildArray()));
    kids.sort(
        Comparator.<IShapeUIModel>comparingInt(IShapeUIModel::getY)
            .thenComparingInt(IShapeUIModel::getX));
    int n = kids.size();
    int cols = n <= 5 ? 1 : n <= 12 ? 2 : 3;
    int rows = (n + cols - 1) / cols;
    int cellW = 0;
    int cellH = 0;
    for (IShapeUIModel k : kids) {
      cellW = Math.max(cellW, k.getWidth());
      cellH = Math.max(cellH, k.getHeight());
    }
    cellW += 60;
    cellH += 40;
    int header = 40;
    int pad = 20;
    int w = Math.max(160, cols * cellW + 2 * pad);
    int h = header + rows * cellH + pad;
    moveShape(c, x, y, w, h); // moves children along; repositioned next
    for (int i = 0; i < n; i++) {
      IShapeUIModel k = kids.get(i);
      int col = i % cols;
      int row = i / cols;
      int kx = x + pad + col * cellW + (cellW - k.getWidth()) / 2;
      int ky = y + header + row * cellH + (cellH - k.getHeight()) / 2;
      moveShape(k, kx, ky, k.getWidth(), k.getHeight());
    }
    return new int[] {w, h};
  }

  /** Nested in a container other than a swimlane (whose layout VP handles itself). */
  private static boolean inBoundary(IDiagramElement e) {
    if (!(e instanceof IShapeUIModel)) {
      return false;
    }
    IDiagramElement parent = ((IShapeUIModel) e).getParent();
    return parent != null && !MANAGED.contains(parent.getShapeType());
  }

  /**
   * After VP's own layout: grows boundaries so that they contain their children again and re-routes
   * connectors that touch nested shapes (VP's layout leaves them dangling).
   */
  static void repairAfterLayout(IDiagramUIModel d) {
    for (IShapeUIModel s : d.toShapeUIModelArray()) {
      if (MANAGED.contains(s.getShapeType()) || s.childrenCount() == 0) {
        continue;
      }
      int minX = Integer.MAX_VALUE;
      int minY = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      int maxY = Integer.MIN_VALUE;
      for (IShapeUIModel k : s.toChildArray()) {
        minX = Math.min(minX, k.getX());
        minY = Math.min(minY, k.getY());
        maxX = Math.max(maxX, k.getX() + k.getWidth());
        maxY = Math.max(maxY, k.getY() + k.getHeight());
      }
      int x = Math.min(s.getX(), minX - 20);
      int y = Math.min(s.getY(), minY - 40);
      int r = Math.max(s.getX() + s.getWidth(), maxX + 20);
      int b = Math.max(s.getY() + s.getHeight(), maxY + 20);
      s.setBounds(x, y, r - x, b - y);
    }
    for (IConnectorUIModel c : d.toConnectorUIModelArray()) {
      IDiagramElement from = c.getFromShape();
      IDiagramElement to = c.getToShape();
      boolean nested = inBoundary(from) || inBoundary(to);
      if (nested && !(c.getModelElement() instanceof IMessage)) {
        reroute(c);
      }
    }
    for (IDiagramElement e : d.toDiagramElementArray()) {
      DiagramBuilder.resetCaption(e);
    }
  }
}
