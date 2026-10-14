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
import com.vp.plugin.model.IAssociationEnd;
import com.vp.plugin.model.IEndRelationship;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IMessage;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IRelationship;
import com.vp.plugin.model.IRelationshipEnd;
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

  private final Map<String, IModelElement> modelKeys = new HashMap<>();
  private final List<IShapeUIModel> refit = new ArrayList<>();
  private final Map<IConnectorUIModel, List<Point>> routed = new LinkedHashMap<>();

  DiagramBuilder(IDiagramUIModel diagram) {
    this.diagram = diagram;
    VpModel.BATCH_KEYS.set(modelKeys);
    // VP drops the layout state of diagrams that are not open (shapes report 0x0 and are exported
    // with default sizes); open the diagram so that new shapes get their real size
    VpModel.ensureOpen(diagram);
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
    if (created.getWidth() <= 0 || created.getHeight() <= 0) {
      // a re-shown element gets its size only later; take it from another view meanwhile
      IDiagramElement[] views = model.getDiagramElements();
      if (views != null) {
        for (IDiagramElement v : views) {
          if (!v.getId().equals(created.getId()) && v.getWidth() > 0 && v.getHeight() > 0) {
            created.setSize(v.getWidth(), v.getHeight());
            break;
          }
        }
      }
    }
    if (parent != null && created instanceof IShapeUIModel) {
      parent.addChild((IShapeUIModel) created);
    }
    if (!partitions.isEmpty() && !spec.has("width") && !spec.has("height")) {
      created.setSize(
          vertical ? 260 * partitions.size() : 900, vertical ? 560 : 180 * partitions.size());
    }
    place(created, spec, lane == null ? parent : null, lane);
    VpModel.applyView(created, spec.map("view"));
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
      refit.add((IShapeUIModel) created);
      try {
        ((IShapeUIModel) created).fitSize();
      } catch (RuntimeException e) {
        // fitSize is cosmetic
      }
    }
    if (spec.has("key")) {
      keys.put(spec.str("key"), created);
      modelKeys.put(spec.str("key"), model);
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
    if ("Message".equals(type)) {
      // a message whose end is an activation loses that end in VP (no receiver in the model)
      from = lifelineOf(from);
      to = lifelineOf(to);
    }
    if (REVERSED_IN_VP.contains(type)) {
      IDiagramElement tmp = from;
      from = to;
      to = tmp;
    }
    IModelElement fromModel = from.getModelElement();
    IModelElement toModel = to.getModelElement();

    IModelElement rel = null;
    boolean existing = spec.has("modelId");
    boolean reusedRel = false;
    if (!existing && DEDUPLICATED.contains(type) && !spec.bool("allowDuplicate", false)) {
      // the same relationship between the same elements usually exists already (drawn on
      // another diagram); show it instead of creating a duplicate in the model
      String name = spec.str("name", null);
      Map<String, Object> props = spec.map("properties");
      IModelElement same = findRelationship(type, fromModel, toModel, name, props);
      if (same == null && "Association".equals(type)) {
        same = findRelationship(type, toModel, fromModel, name, swapEnds(props));
      }
      if (same != null) {
        rel = same;
        existing = true;
        reusedRel = true;
      }
    }
    if (existing) {
      if (rel == null) {
        rel = VpModel.model(spec.str("modelId"));
      }
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
    if (reusedRel) {
      IDiagramElement shown = viewOn(rel);
      if (shown != null) { // already on this diagram: nothing to draw
        if (spec.has("key")) {
          keys.put(spec.str("key"), shown);
        }
        Map<String, Object> r = VpModel.describeView(shown);
        r.put("reused", true);
        if (spec.has("key")) {
          r.put("key", spec.str("key"));
        }
        return r;
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
      if (reusedRel
          && rel instanceof IRelationship
          && ((IRelationship) rel).getFrom() != null
          && fromModel != null
          && !((IRelationship) rel).getFrom().getId().equals(fromModel.getId())) {
        props = swapEnds(props); // the existing association runs the other way
      }
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
    VpModel.applyView(created, spec.map("view"));
    if (created instanceof IConnectorUIModel && !(rel instanceof IMessage)) {
      IConnectorUIModel c = (IConnectorUIModel) created;
      if (spec.has("waypoints")) {
        List<Point> waypoints = Geometry.waypoints(spec.list("waypoints"));
        Geometry.route(c, waypoints);
        routed.put(c, waypoints);
      } else if (Geometry.parallelIndex(c) > 0) {
        Geometry.reroute(c); // a second line between the same shapes gets a bend
      }
    }
    resetCaption(created);
    this.created.add(created);
    if (spec.has("key")) {
      keys.put(spec.str("key"), created);
    }
    Map<String, Object> r = VpModel.describeView(created);
    if (reusedRel) {
      r.put("reused", true);
    }
    if (spec.has("key")) {
      r.put("key", spec.str("key"));
    }
    return r;
  }

  /** The lifeline shape that holds an activation bar (the shape itself otherwise). */
  static IDiagramElement lifelineOf(IDiagramElement view) {
    IDiagramElement v = view;
    while (v instanceof IShapeUIModel
        && "Activation".equals(v.getShapeType())
        && ((IShapeUIModel) v).getParent() != null) {
      v = ((IShapeUIModel) v).getParent();
    }
    return v;
  }

  /** Relationship types that are not duplicated between the same two elements. */
  static final Set<String> DEDUPLICATED =
      new HashSet<>(
          Arrays.asList(
              "Association",
              "Include",
              "Extend",
              "Generalization",
              "Realization",
              "Dependency",
              "Usage",
              "Abstraction",
              "DBForeignKey"));

  /**
   * Existing relationship of {@code type} whose VP "from" is {@code a} and "to" is {@code b}, with
   * the same name (no name matches an unnamed relationship) and, for associations, the same role
   * names, multiplicities and aggregation as far as {@code props} gives them.
   */
  static IModelElement findRelationship(
      String type, IModelElement a, IModelElement b, String name, Map<String, Object> props) {
    if (a == null || b == null) {
      return null;
    }
    List<IModelElement> candidates = new ArrayList<>();
    addAll(candidates, a.toFromRelationshipArray());
    addAll(candidates, a.toToRelationshipArray());
    for (IRelationshipEnd e : ends(a.toFromRelationshipEndArray(), a.toToRelationshipEndArray())) {
      if (e.getEndRelationship() != null) {
        candidates.add(e.getEndRelationship());
      }
    }
    String wanted = name == null ? "" : name;
    for (IModelElement c : candidates) {
      if (!(c instanceof IRelationship) || !type.equals(c.getModelType())) {
        continue;
      }
      IRelationship rel = (IRelationship) c;
      String have = rel.getName() == null ? "" : rel.getName();
      if (rel.getFrom() != null
          && rel.getTo() != null
          && rel.getFrom().getId().equals(a.getId())
          && rel.getTo().getId().equals(b.getId())
          && wanted.equals(have)
          && sameEnds(props, endValues(rel))) {
        return rel;
      }
    }
    return null;
  }

  /** End properties compared before an association is reused. */
  static final List<String> END_KEYS = Arrays.asList("name", "multiplicity", "aggregationKind");

  /** "from.name", "to.multiplicity", ... of an association (VP from/to). */
  private static Map<String, String> endValues(IRelationship rel) {
    Map<String, String> out = new HashMap<>();
    if (rel instanceof IEndRelationship) {
      putEnd(out, "from.", ((IEndRelationship) rel).getFromEnd());
      putEnd(out, "to.", ((IEndRelationship) rel).getToEnd());
    }
    return out;
  }

  private static void putEnd(Map<String, String> out, String prefix, IRelationshipEnd end) {
    if (end instanceof IAssociationEnd) {
      IAssociationEnd e = (IAssociationEnd) end;
      out.put(prefix + "name", e.getName());
      out.put(prefix + "multiplicity", e.getMultiplicity());
      out.put(prefix + "aggregationKind", e.getAggregationKind());
    }
  }

  /**
   * Whether every end property given in {@code props} (role name, multiplicity, aggregation kind)
   * equals the existing value; ends that are not mentioned do not matter.
   */
  static boolean sameEnds(Map<String, Object> props, Map<String, String> existing) {
    if (props == null) {
      return true;
    }
    for (Map.Entry<String, Object> e : props.entrySet()) {
      String key = e.getKey();
      int dot = key.indexOf('.');
      if (dot <= 0 || !END_KEYS.contains(key.substring(dot + 1))) {
        continue;
      }
      String prop = key.substring(dot + 1);
      String wanted = normalizeEnd(prop, e.getValue() == null ? "" : String.valueOf(e.getValue()));
      String have = normalizeEnd(prop, existing.get(key));
      if (!wanted.equals(have)) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeEnd(String prop, String value) {
    String v = value == null ? "" : value.trim();
    if ("multiplicity".equals(prop) && "Unspecified".equalsIgnoreCase(v)) {
      return "";
    }
    if ("aggregationKind".equals(prop)) {
      return VpModel.aggregationKind(v);
    }
    return v;
  }

  private static void addAll(List<IModelElement> out, IModelElement[] arr) {
    if (arr != null) {
      out.addAll(Arrays.asList(arr));
    }
  }

  private static List<IRelationshipEnd> ends(IRelationshipEnd[] x, IRelationshipEnd[] y) {
    List<IRelationshipEnd> out = new ArrayList<>();
    if (x != null) {
      out.addAll(Arrays.asList(x));
    }
    if (y != null) {
      out.addAll(Arrays.asList(y));
    }
    return out;
  }

  /** The view of a model element on this builder's diagram, or null. */
  private IDiagramElement viewOn(IModelElement m) {
    IDiagramElement[] views = m.getDiagramElements();
    if (views != null) {
      for (IDiagramElement v : views) {
        if (v.getDiagramUIModel() != null
            && diagram.getId().equals(v.getDiagramUIModel().getId())) {
          return v;
        }
      }
    }
    return null;
  }

  /** "from.x" <-> "to.x" for a relationship reused in the opposite direction. */
  private static Map<String, Object> swapEnds(Map<String, Object> props) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : props.entrySet()) {
      String k = e.getKey();
      if (k.startsWith("from.")) {
        k = "to." + k.substring(5);
      } else if (k.startsWith("to.")) {
        k = "from." + k.substring(3);
      }
      out.put(k, e.getValue());
    }
    return out;
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
    VpModel.BATCH_KEYS.remove();
    List<IDiagramElement> all = new ArrayList<>(created);
    List<IShapeUIModel> fit = new ArrayList<>(refit);
    Map<IConnectorUIModel, List<Point>> fixed = new LinkedHashMap<>(routed);
    SwingUtilities.invokeLater(
        () -> {
          // VP lays out compartments (members, literals) only after the creating event; size
          // shapes again now so that classes are neither clipped nor too tall
          for (IShapeUIModel s : fit) {
            String t = s.getShapeType();
            if ("InteractionLifeLine".equals(t) || "InteractionActor".equals(t)) {
              continue; // their length is set by the builder; fitSize() would reset it
            }
            try {
              int w = s.getWidth();
              int h = s.getHeight();
              s.fitSize();
              if (s.getWidth() != w || s.getHeight() != h) {
                for (IConnectorUIModel c : Geometry.attached(s)) {
                  Geometry.reroute(c);
                }
              }
            } catch (RuntimeException e) {
              // cosmetic
            }
          }
          Geometry.keepSizes(diagram);
          // VP starts new connectors at a corner of the shapes (often running along their top
          // edges): draw them between the shape borders, with bends where requested
          for (IDiagramElement de : all) {
            if (de instanceof IConnectorUIModel
                && !(de.getModelElement() instanceof IMessage)
                && !fixed.containsKey(de)
                && !Geometry.isSelfLoop((IConnectorUIModel) de)) {
              Geometry.reroute((IConnectorUIModel) de);
            }
          }
          for (Map.Entry<IConnectorUIModel, List<Point>> e : fixed.entrySet()) {
            Geometry.route(e.getKey(), e.getValue());
          }
          for (IDiagramElement de : all) {
            resetCaption(de);
          }
          // VP renumbers sequence messages after that, which moves their labels again
          SwingUtilities.invokeLater(
              () -> {
                for (IDiagramElement de : all) {
                  resetCaption(de);
                }
                for (IDiagramElement de : all) {
                  if (de instanceof IConnectorUIModel) {
                    Geometry.scheduleCaptionBeside((IConnectorUIModel) de);
                  }
                }
              });
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

  /** Includes an element created outside this builder in the deferred caption pass. */
  void track(IDiagramElement view) {
    created.add(view);
  }

  /** Registers an element created outside this builder under a key. */
  void register(String key, IDiagramElement view, IModelElement model) {
    keys.put(key, view);
    modelKeys.put(key, model);
  }

  Map<String, Object> keyMap() {
    Map<String, Object> r = new LinkedHashMap<>();
    for (Map.Entry<String, IDiagramElement> e : keys.entrySet()) {
      r.put(e.getKey(), e.getValue().getId());
    }
    return r;
  }
}
