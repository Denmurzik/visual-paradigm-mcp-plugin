package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.Args;
import com.brunnen.vp.mcp.protocol.ToolException;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeTypeConstants;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.shape.IActivityPartitionHeaderUIModel;
import com.vp.plugin.diagram.shape.IActivitySwimlane2CompartmentUIModel;
import com.vp.plugin.diagram.shape.IActivitySwimlane2NewUIModel;
import com.vp.plugin.model.IActivityPartition;
import com.vp.plugin.model.IActivitySwimlane2;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IMessage;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IRelationship;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.SwingUtilities;

/**
 * Adds shapes, nested children and connectors to one diagram. Elements created in the same builder
 * can reference each other by a caller-chosen {@code key}.
 */
final class DiagramBuilder {

  private static final int GRID_COLUMNS = 4;
  private static final int GRID_DX = 220;
  private static final int GRID_DY = 150;
  private static final int MARGIN = 40;

  private final IDiagramUIModel diagram;
  private final Map<String, IDiagramElement> keys = new HashMap<>();
  private final List<IDiagramElement> created = new ArrayList<>();
  private int autoPlaced;

  DiagramBuilder(IDiagramUIModel diagram) {
    this.diagram = diagram;
  }

  IDiagramUIModel diagram() {
    return diagram;
  }

  /** Resolves a key from this batch, then a shape id, model id or element name. */
  IDiagramElement resolve(String ref) {
    IDiagramElement de = keys.get(ref);
    return de != null ? de : VpModel.view(diagram, ref);
  }

  IShapeUIModel resolveShape(String ref) {
    IDiagramElement de = resolve(ref);
    if (!(de instanceof IShapeUIModel)) {
      throw new ToolException("'" + ref + "' is not a shape");
    }
    return (IShapeUIModel) de;
  }

  /** A partition (lane) of an activity swimlane created by this builder. */
  private static final class Lane {
    final IActivityPartition partition;
    final IShapeUIModel swimlane;
    final int index;
    final int count;
    final boolean vertical;
    int placed;

    Lane(
        IActivityPartition partition,
        IShapeUIModel swimlane,
        int index,
        int count,
        boolean vertical) {
      this.partition = partition;
      this.swimlane = swimlane;
      this.index = index;
      this.count = count;
      this.vertical = vertical;
    }
  }

  private final Map<String, Lane> lanes = new HashMap<>();

  /**
   * A swimlane partition referenced by a key of this batch, or (for existing diagrams) by its
   * header shape id, partition id or partition name.
   */
  private Lane lane(String ref) {
    Lane lane = lanes.get(ref);
    if (lane != null) {
      return lane;
    }
    IDiagramElement de;
    try {
      de = resolve(ref);
    } catch (ToolException e) {
      return null;
    }
    if (!(de instanceof IShapeUIModel) || !(de.getModelElement() instanceof IActivityPartition)) {
      return null;
    }
    IDiagramElement swShape = ((IShapeUIModel) de).getParent();
    if (!(swShape instanceof IShapeUIModel)
        || !(swShape.getModelElement() instanceof IActivitySwimlane2)) {
      return null;
    }
    IActivityPartition part = (IActivityPartition) de.getModelElement();
    IActivitySwimlane2 swModel = (IActivitySwimlane2) swShape.getModelElement();
    List<IActivityPartition> vertical = partitions(swModel.toVerticalPartitionArray());
    List<IActivityPartition> horizontal = partitions(swModel.toHorizontalPartitionArray());
    boolean isVertical = indexOf(vertical, part) >= 0;
    List<IActivityPartition> all = isVertical ? vertical : horizontal;
    int index = indexOf(all, part);
    if (index < 0) {
      return null;
    }
    lane = new Lane(part, (IShapeUIModel) swShape, index, all.size(), isVertical);
    lane.placed = part.containedElementCount();
    lanes.put(ref, lane);
    return lane;
  }

  /** Node types that VP labels with their type name unless given an explicit (empty) name. */
  private static final Set<String> UNNAMED_BY_DEFAULT =
      new HashSet<>(
          Arrays.asList(
              "InitialNode",
              "ActivityFinalNode",
              "FlowFinalNode",
              "DecisionNode",
              "MergeNode",
              "ForkNode",
              "JoinNode",
              "InitialPseudoState",
              "FinalState2",
              "Choice",
              "Junction",
              "Fork",
              "Join",
              "ShallowHistory",
              "DeepHistory",
              "Terminate"));

  /**
   * Spec fields: type, name, key, x, y, width, height, parent (shape ref or swimlane partition
   * key), modelId (reuse an existing model element), properties, children (model-only children such
   * as attributes) and, for ActivitySwimlane2, partitions + orientation.
   */
  Map<String, Object> addShape(Args spec) throws Exception {
    Lane lane = spec.has("parent") ? lane(spec.str("parent")) : null;
    IShapeUIModel parent =
        lane != null ? lane.swimlane : spec.has("parent") ? resolveShape(spec.str("parent")) : null;
    IModelElement owner = null; // model namespace of the new element
    if (lane == null && parent != null && spec.bool("nestModel", true)) {
      owner = parent.getModelElement();
    }
    IModelElement model;
    boolean reused = false;
    if (spec.has("modelId")) {
      model = VpModel.model(spec.str("modelId"));
    } else {
      model = VpModel.create(spec.str("type"));
      if (lane != null) {
        lane.partition.addContainedElement(model);
      } else if (owner != null) {
        try {
          owner.addChild(model);
        } catch (RuntimeException e) {
          owner = null; // not every container owns its contents in the model; view only
        }
      }
      if (spec.has("name")) {
        IModelElement existing = VpModel.rename(model, spec.str("name"), owner);
        if (existing != null) {
          if (!spec.bool("reuseExisting", true)) {
            model.delete();
            throw new ToolException(
                "A "
                    + existing.getModelType()
                    + " named '"
                    + spec.str("name")
                    + "' already exists there (id "
                    + existing.getId()
                    + "); VP does not allow duplicate names. Pass modelId to show it.");
          }
          model.delete();
          model = existing;
          reused = true;
        }
      } else if (UNNAMED_BY_DEFAULT.contains(model.getModelType())) {
        model.setName("");
      }
    }
    VpModel.apply(model, spec.map("properties"));

    List<Object> partitionResults = new ArrayList<>();
    List<IActivityPartition> partitions = new ArrayList<>();
    boolean vertical = !"horizontal".equalsIgnoreCase(spec.str("orientation", "vertical"));
    if (model instanceof IActivitySwimlane2) {
      for (Object p : spec.list("partitions")) {
        Args ps = p instanceof Map ? Args.of(p, "partition") : null;
        IActivityPartition part = VpModel.factory().createActivityPartition();
        part.setName(ps != null ? ps.str("name", "") : String.valueOf(p));
        if (vertical) {
          ((IActivitySwimlane2) model).addVerticalPartition(part);
        } else {
          ((IActivitySwimlane2) model).addHorizontalPartition(part);
        }
        partitions.add(part);
      }
    }

    IDiagramElement created = VpModel.diagrams().createDiagramElement(diagram, model);
    if (created == null) {
      throw new ToolException(
          "Visual Paradigm refused to put a "
              + model.getModelType()
              + " on a "
              + diagram.getType()
              + " (see vp_list_types for allowed shape types)");
    }
    if (parent != null && created instanceof IShapeUIModel) {
      parent.addChild((IShapeUIModel) created);
    }
    if (!partitions.isEmpty() && !spec.has("width") && !spec.has("height")) {
      created.setSize(
          vertical ? 260 * partitions.size() : 900, vertical ? 560 : 180 * partitions.size());
    }
    place(created, spec, lane == null ? parent : null, lane);
    resetCaption(created);
    this.created.add(created);
    if (!partitions.isEmpty()) {
      buildSwimlaneViews(created, partitions, vertical);
    }

    for (int i = 0; i < partitions.size(); i++) {
      Object p = spec.list("partitions").get(i);
      String key =
          p instanceof Map && Args.of(p, "partition").has("key")
              ? Args.of(p, "partition").str("key")
              : partitions.get(i).getName();
      lanes.put(
          key,
          new Lane(partitions.get(i), (IShapeUIModel) created, i, partitions.size(), vertical));
      Map<String, Object> pr = VpModel.brief(partitions.get(i));
      pr.put("key", key);
      partitionResults.add(pr);
    }

    List<Object> childResults = new ArrayList<>();
    for (Object c : spec.list("children")) {
      childResults.add(addModelChild(model, Args.of(c, "child")));
    }
    if (created instanceof IShapeUIModel
        && partitions.isEmpty()
        && spec.bool("fitSize", true)
        && !spec.has("width")
        && !spec.has("height")) {
      try {
        ((IShapeUIModel) created).fitSize();
      } catch (RuntimeException e) {
        // fitSize is cosmetic
      }
    }
    if (spec.has("key")) {
      keys.put(spec.str("key"), created);
    }
    Map<String, Object> r = VpModel.describeView(created);
    if (spec.has("key")) {
      r.put("key", spec.str("key"));
    }
    if (reused) {
      r.put("reused", true);
    }
    if (!partitionResults.isEmpty()) {
      r.put("partitions", partitionResults);
    }
    if (!childResults.isEmpty()) {
      r.put("children", childResults);
    }
    return r;
  }

  private static int indexOf(List<IActivityPartition> list, IActivityPartition part) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).getId().equals(part.getId())) {
        return i;
      }
    }
    return -1;
  }

  private static List<IActivityPartition> partitions(IActivityPartition[] arr) {
    return arr == null ? new ArrayList<>() : Arrays.asList(arr);
  }

  /**
   * VP does not create the visual parts of a swimlane by itself: one header per partition and one
   * compartment per lane, all registered by id on the swimlane shape.
   */
  private void buildSwimlaneViews(
      IDiagramElement swimlane, List<IActivityPartition> partitions, boolean vertical) {
    if (!(swimlane instanceof IActivitySwimlane2NewUIModel)) {
      return;
    }
    IActivitySwimlane2NewUIModel sw = (IActivitySwimlane2NewUIModel) swimlane;
    int n = partitions.size();
    int header = 30;
    List<String> partitionIds = new ArrayList<>();
    List<String> compartmentIds = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      IActivityPartition part = partitions.get(i);
      partitionIds.add(part.getId());
      int x;
      int y;
      int w;
      int h;
      if (vertical) {
        w = sw.getWidth() / n;
        h = sw.getHeight();
        x = sw.getX() + i * w;
        y = sw.getY();
      } else {
        w = sw.getWidth();
        h = sw.getHeight() / n;
        x = sw.getX();
        y = sw.getY() + i * h;
      }
      IDiagramElement hd = VpModel.diagrams().createDiagramElement(diagram, part);
      if (hd instanceof IActivityPartitionHeaderUIModel) {
        IActivityPartitionHeaderUIModel ph = (IActivityPartitionHeaderUIModel) hd;
        ph.setSwimlane(sw);
        ph.setHorizontal(!vertical);
        sw.addChild(ph);
        if (vertical) {
          ph.setBounds(x, y, w, header);
        } else {
          ph.setBounds(x, y, header, h);
        }
        resetCaption(ph);
      }
      IDiagramElement cp =
          diagram.createDiagramElement(
              IShapeTypeConstants.SHAPE_TYPE_ACTIVITY_SWIMLANE2_COMPARTMENT);
      if (cp instanceof IActivitySwimlane2CompartmentUIModel) {
        IActivitySwimlane2CompartmentUIModel c = (IActivitySwimlane2CompartmentUIModel) cp;
        if (vertical) {
          c.setVerticalPartitionId(part.getId());
          c.setBounds(x, y + header, w, h - header);
        } else {
          c.setHorizontalPartitionId(part.getId());
          c.setBounds(x + header, y, w - header, h);
        }
        sw.addChild(c);
        compartmentIds.add(c.getId());
      }
    }
    if (vertical) {
      sw.setVerticalPartitionIds(partitionIds.toArray(new String[0]));
    } else {
      sw.setHorizontalPartitionIds(partitionIds.toArray(new String[0]));
    }
    sw.setCompartmentIds(compartmentIds.toArray(new String[0]));
  }

  /** Creates a model-only child (attribute, operation, column, parameter, ...) recursively. */
  static Map<String, Object> addModelChild(IModelElement parent, Args spec) throws Exception {
    IModelElement child = null;
    boolean reused = false;
    if (spec.has("name")) {
      child = VpModel.childNamed(parent, spec.str("type"), spec.str("name"));
      reused = child != null;
    }
    if (child == null) {
      child = VpModel.createChild(parent, spec.str("type"));
      if (spec.has("name")) {
        IModelElement existing = VpModel.rename(child, spec.str("name"), parent);
        if (existing != null) {
          child.delete();
          child = existing;
          reused = true;
        }
      }
    }
    VpModel.apply(child, spec.map("properties"));
    List<Object> nested = new ArrayList<>();
    for (Object c : spec.list("children")) {
      nested.add(addModelChild(child, Args.of(c, "child")));
    }
    Map<String, Object> r = VpModel.brief(child);
    if (reused) {
      r.put("reused", true);
    }
    if (!nested.isEmpty()) {
      r.put("children", nested);
    }
    return r;
  }

  /**
   * Relationships whose VP "from" end is the general/supplier/base element, i.e. the opposite of
   * the UML reading direction (child -> parent, class -> interface, extension -> base). Callers
   * always pass the UML direction; it is swapped for VP.
   */
  static final Set<String> REVERSED_IN_VP =
      new HashSet<>(Arrays.asList("Generalization", "Realization", "Extend"));

  /** Spec fields: type, from, to, name, key, properties. */
  Map<String, Object> addConnector(Args spec) throws Exception {
    String type =
        spec.has("modelId") ? VpModel.model(spec.str("modelId")).getModelType() : spec.str("type");
    IDiagramElement from = resolve(spec.str("from"));
    IDiagramElement to = resolve(spec.str("to"));
    if (REVERSED_IN_VP.contains(type)) {
      IDiagramElement tmp = from;
      from = to;
      to = tmp;
    }
    IModelElement fromModel = from.getModelElement();
    IModelElement toModel = to.getModelElement();

    IModelElement rel = null;
    boolean existing = spec.has("modelId");
    if (existing) {
      rel = VpModel.model(spec.str("modelId"));
      if (rel instanceof IRelationship) {
        // view ends must match VP's own from/to of the relationship, whatever order was given
        IModelElement vpFrom = ((IRelationship) rel).getFrom();
        if (vpFrom != null && toModel != null && vpFrom.getId().equals(toModel.getId())) {
          IDiagramElement tmp = from;
          from = to;
          to = tmp;
        }
      }
    } else {
      try {
        rel = VpModel.factory().create(spec.str("type"));
      } catch (RuntimeException e) {
        rel = null;
      }
    }
    IDiagramElement created;
    if (rel != null) {
      if (rel instanceof IRelationship && !existing) {
        ((IRelationship) rel).setFrom(fromModel);
        ((IRelationship) rel).setTo(toModel);
      }
      if (spec.has("name")) {
        rel.setName(spec.str("name"));
      }
      Map<String, Object> props = new LinkedHashMap<>(spec.map("properties"));
      props.remove("extensionPoint");
      VpModel.apply(rel, props);
      Point[] points = null;
      if (rel instanceof IMessage) {
        points = messagePoints(from, to, spec.intOrNull("y"));
        if (!spec.map("properties").containsKey("sequenceNumber")) {
          ((IMessage) rel).setSequenceNumber(String.valueOf(countMessages() + 1));
        }
      }
      created = VpModel.diagrams().createConnector(diagram, rel, from, to, points);
    } else {
      // view-only connector types (e.g. Anchor) have no model element
      created = VpModel.diagrams().createConnector(diagram, type, from, to, null);
    }
    if (rel instanceof IExtend && !existing) {
      handleExtensionPoint((IExtend) rel, created, extensionPointArg(spec));
    }
    if (created == null) {
      throw new ToolException(
          "Visual Paradigm refused to connect "
              + describeEnd(from)
              + " to "
              + describeEnd(to)
              + " with a "
              + type);
    }
    resetCaption(created);
    this.created.add(created);
    if (spec.has("key")) {
      keys.put(spec.str("key"), created);
    }
    Map<String, Object> r = VpModel.describeView(created);
    if (spec.has("key")) {
      r.put("key", spec.str("key"));
    }
    return r;
  }

  private Integer nextMessageY;

  /**
   * Sequence diagram messages are positioned by their points: each new message goes below the
   * previous one (or at the given y).
   */
  private Point[] messagePoints(IDiagramElement from, IDiagramElement to, Integer y) {
    if (y == null) {
      if (nextMessageY == null) {
        int top = 0;
        for (IShapeUIModel s : diagram.toShapeUIModelArray()) {
          String t = s.getShapeType();
          if ("InteractionLifeLine".equals(t) || "InteractionActor".equals(t)) {
            top = Math.max(top, s.getY() + Math.min(s.getHeight(), 90));
          }
        }
        int lowest = top + 30;
        for (IConnectorUIModel c : diagram.toConnectorUIModelArray()) {
          if (c.getModelElement() instanceof IMessage && c.getPoints() != null) {
            for (Point p : c.getPoints()) {
              lowest = Math.max(lowest, p.y + MESSAGE_GAP);
            }
          }
        }
        nextMessageY = lowest;
      }
      y = nextMessageY;
    }
    nextMessageY = y + MESSAGE_GAP;
    extendLifelines(y + 2 * MESSAGE_GAP);
    int fx = from.getX() + from.getWidth() / 2;
    int tx = to.getX() + to.getWidth() / 2;
    if (from == to || from.getId().equals(to.getId())) {
      nextMessageY += MESSAGE_GAP / 2;
      return new Point[] {
        new Point(fx, y), new Point(fx + 40, y), new Point(fx + 40, y + 20), new Point(fx, y + 20)
      };
    }
    return new Point[] {new Point(fx, y), new Point(tx, y)};
  }

  private static final int MESSAGE_GAP = 40;

  /** Makes every lifeline long enough to reach {@code bottom}. */
  private void extendLifelines(int bottom) {
    for (IShapeUIModel s : diagram.toShapeUIModelArray()) {
      String t = s.getShapeType();
      if (("InteractionLifeLine".equals(t) || "InteractionActor".equals(t))
          && s.getY() + s.getHeight() < bottom) {
        s.setHeight(bottom - s.getY());
      }
    }
  }

  private int countMessages() {
    int n = 0;
    for (IConnectorUIModel c : diagram.toConnectorUIModelArray()) {
      if (c.getModelElement() instanceof IMessage) {
        n++;
      }
    }
    return n;
  }

  /**
   * VP adjusts some views (e.g. sequence messages snapping to activations) after the current event;
   * labels are laid out again once that has happened.
   */
  void finish() {
    List<IDiagramElement> all = new ArrayList<>(created);
    SwingUtilities.invokeLater(
        () -> {
          for (IDiagramElement de : all) {
            resetCaption(de);
          }
        });
  }

  private static Object extensionPointArg(Args spec) {
    return spec.has("extensionPoint")
        ? spec.raw().get("extensionPoint")
        : spec.map("properties").get("extensionPoint");
  }

  /**
   * VP adds an extension point called "ExtensionPoint" to the base use case for every Extend. By
   * default it is removed (plain UML extend); a string keeps it under that name.
   */
  private static void handleExtensionPoint(IExtend ext, IDiagramElement view, Object arg) {
    IExtensionPoint ep = ext.getExtensionPoint();
    if (ep == null) {
      return;
    }
    IModelElement base = ext.getFrom(); // VP stores the base use case as "from"
    if (arg instanceof String && !((String) arg).isEmpty()) {
      ep.setName((String) arg);
      if (base != null) {
        ExtensionPoints.refreshViews(base);
      }
      return;
    }
    if (Boolean.TRUE.equals(arg)) {
      return;
    }
    ext.setExtensionPoint(null);
    try {
      ep.delete();
    } catch (RuntimeException e) {
      // not a registered element; unlinking removes it
    }
    if (base != null) {
      ExtensionPoints.refreshViews(base);
    }
  }

  /** Asks VP to lay out the element's labels (names, stereotypes, multiplicities). */
  static void resetCaption(IDiagramElement de) {
    try {
      de.resetCaption();
    } catch (RuntimeException e) {
      // cosmetic
    }
    try {
      de.setRequestResetCaption(true);
    } catch (RuntimeException e) {
      // cosmetic
    }
  }

  private static String describeEnd(IDiagramElement de) {
    IModelElement m = de.getModelElement();
    return m == null ? de.getShapeType() : m.getModelType() + " '" + m.getName() + "'";
  }

  private void place(IDiagramElement created, Args spec, IShapeUIModel parent, Lane lane) {
    if (created instanceof IConnectorUIModel) {
      return;
    }
    Integer w = spec.intOrNull("width");
    Integer h = spec.intOrNull("height");
    if (w != null || h != null) {
      created.setSize(w != null ? w : created.getWidth(), h != null ? h : created.getHeight());
    }
    Integer x = spec.intOrNull("x");
    Integer y = spec.intOrNull("y");
    if (x == null || y == null) {
      int px;
      int py;
      if (lane != null) {
        // stack inside the lane (column for vertical swimlanes, row for horizontal ones)
        IShapeUIModel sw = lane.swimlane;
        int n = lane.placed++;
        if (lane.vertical) {
          int laneW = sw.getWidth() / lane.count;
          px = sw.getX() + lane.index * laneW + Math.max(10, (laneW - created.getWidth()) / 2);
          py = sw.getY() + 60 + n * 90;
        } else {
          int laneH = sw.getHeight() / lane.count;
          px = sw.getX() + 60 + n * 180;
          py = sw.getY() + lane.index * laneH + Math.max(10, (laneH - created.getHeight()) / 2);
        }
      } else if (parent != null) {
        // two columns inside the container, below its caption
        int idx = Math.max(0, parent.childrenCount() - 1);
        px = parent.getX() + 40 + (idx % 2) * 200;
        py = parent.getY() + 50 + (idx / 2) * 90;
      } else {
        if (rowStart == null) {
          int bottom = bottomOfShapes(created);
          rowStart = bottom > 0 ? bottom + MARGIN : MARGIN;
        }
        int n = autoPlaced++;
        px = MARGIN + (n % GRID_COLUMNS) * GRID_DX;
        py = rowStart + (n / GRID_COLUMNS) * GRID_DY;
      }
      created.setLocation(x != null ? x : px, y != null ? y : py);
    } else {
      created.setLocation(x, y);
    }
    if (parent != null) {
      growToContain(parent, created);
    }
  }

  private Integer rowStart;

  /** Lowest edge of the shapes already on the diagram, ignoring {@code exclude}. */
  private int bottomOfShapes(IDiagramElement exclude) {
    int bottom = 0;
    IShapeUIModel[] shapes = diagram.toShapeUIModelArray();
    if (shapes == null) {
      return bottom;
    }
    for (IShapeUIModel s : shapes) {
      if (s.getId().equals(exclude.getId())) {
        continue;
      }
      bottom = Math.max(bottom, s.getY() + s.getHeight());
    }
    return bottom;
  }

  private static void growToContain(IShapeUIModel parent, IDiagramElement child) {
    int right = child.getX() + child.getWidth() + 30;
    int bottom = child.getY() + child.getHeight() + 30;
    int w = Math.max(parent.getWidth(), right - parent.getX());
    int h = Math.max(parent.getHeight(), bottom - parent.getY());
    if (w != parent.getWidth() || h != parent.getHeight()) {
      parent.setSize(w, h);
    }
  }

  Map<String, Object> keyMap() {
    Map<String, Object> r = new LinkedHashMap<>();
    for (Map.Entry<String, IDiagramElement> e : keys.entrySet()) {
      r.put(e.getKey(), e.getValue().getId());
    }
    return r;
  }
}
