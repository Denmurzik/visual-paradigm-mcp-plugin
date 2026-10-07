package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.ToolException;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.diagram.IConnectorUIModel;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IAssociationEnd;
import com.vp.plugin.model.IAttribute;
import com.vp.plugin.model.IDBColumn;
import com.vp.plugin.model.IEndRelationship;
import com.vp.plugin.model.IMessage;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IQualifier;
import com.vp.plugin.model.IRelationship;
import com.vp.plugin.model.IRelationshipEnd;
import com.vp.plugin.model.IStepContainer;
import com.vp.plugin.model.IStereotype;
import com.vp.plugin.model.IUseCase;
import com.vp.plugin.model.factory.IModelElementFactory;
import com.vp.plugin.model.property.IModelProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lookup, description and mutation helpers on top of the Visual Paradigm Open API. */
final class VpModel {

  private static final int MAX_COLLECTION_ITEMS = 25;
  private static final int MAX_STRING = 2000;

  private VpModel() {}

  // ------------------------------------------------------------------ access

  static ApplicationManager app() {
    ApplicationManager app = ApplicationManager.instance();
    if (app == null) {
      throw new ToolException("Visual Paradigm application is not available");
    }
    return app;
  }

  static DiagramManager diagrams() {
    return app().getDiagramManager();
  }

  static IProject project() {
    IProject p = app().getProjectManager().getProject();
    if (p == null) {
      throw new ToolException("No project is open in Visual Paradigm");
    }
    return p;
  }

  static IModelElementFactory factory() {
    return IModelElementFactory.instance();
  }

  // --------------------------------------------------------------- resolve

  /** Finds a model element by id; a shape/connector id resolves to its model element. */
  static IModelElement findModel(String id) {
    if (id == null || id.isEmpty()) {
      return null;
    }
    IProject p = project();
    IModelElement m = p.getModelElementById(id);
    if (m != null) {
      return m;
    }
    IDiagramElement de = p.getDiagramElementById(id);
    if (de != null) {
      return de.getModelElement();
    }
    return nestedById(id);
  }

  /**
   * Members such as attributes, operations, parameters and literals are not found by
   * IProject.getModelElementById; they are searched among the children of all elements.
   */
  private static IModelElement nestedById(String id) {
    java.util.Iterator<?> it = project().allLevelModelElementIterator();
    while (it.hasNext()) {
      IModelElement found = childById((IModelElement) it.next(), id, 0);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static IModelElement childById(IModelElement parent, String id, int depth) {
    if (depth > 4) {
      return null;
    }
    IModelElement[] kids = parent.toChildArray();
    if (kids == null) {
      return null;
    }
    for (IModelElement k : kids) {
      if (id.equals(k.getId())) {
        return k;
      }
      IModelElement deeper = childById(k, id, depth + 1);
      if (deeper != null) {
        return deeper;
      }
    }
    return null;
  }

  /** Model element by id (or shape id), else by its exact name if that is unique. */
  static IModelElement model(String id) {
    IModelElement m = findModel(id);
    if (m != null) {
      return m;
    }
    List<IModelElement> named = byName(id, null);
    if (named.size() == 1) {
      return named.get(0);
    }
    if (named.size() > 1) {
      StringBuilder ids = new StringBuilder();
      for (IModelElement n : named) {
        ids.append(' ').append(n.getModelType()).append(':').append(n.getId());
      }
      throw new ToolException("Name '" + id + "' is ambiguous, use an id:" + ids);
    }
    throw new ToolException("No model element with id or name '" + id + "'");
  }

  /** All elements with this exact name, optionally only of one model type. */
  static List<IModelElement> byName(String name, String type) {
    List<IModelElement> out = new ArrayList<>();
    if (name == null || name.isEmpty()) {
      return out;
    }
    java.util.Iterator<?> it =
        type == null
            ? project().allLevelModelElementIterator()
            : project().allLevelModelElementIterator(type);
    while (it.hasNext()) {
      IModelElement e = (IModelElement) it.next();
      if (name.equals(e.getName())) {
        out.add(e);
      }
    }
    return out;
  }

  /** Keys of the vp_build_diagram call in progress ("@key" references in property values). */
  static final ThreadLocal<Map<String, IModelElement>> BATCH_KEYS = new ThreadLocal<>();

  /**
   * Resolves a string property value to a model element: "@key" of the current batch, an element
   * id, or the name of a unique class (so that "type": "Color" links the class Color).
   */
  static Object resolveValue(String s) {
    if (s.startsWith("@") && s.length() > 1) {
      Map<String, IModelElement> keys = BATCH_KEYS.get();
      IModelElement k = keys == null ? null : keys.get(s.substring(1));
      if (k == null) {
        throw new ToolException(
            "Unknown key '"
                + s
                + "' (keys work inside one vp_build_diagram call and must be"
                + " defined by an earlier element)");
      }
      return k;
    }
    IModelElement m = findModel(s);
    if (m != null) {
      return m;
    }
    List<IModelElement> classes = byName(s, "Class");
    return classes.size() == 1 ? classes.get(0) : null;
  }

  /** Diagram by id, or by exact (then case-insensitive) name. */
  static IDiagramUIModel diagram(String idOrName) {
    if (idOrName == null || idOrName.isEmpty()) {
      throw new ToolException("A diagram id is required");
    }
    IProject p = project();
    IDiagramUIModel d = p.getDiagramById(idOrName);
    if (d != null) {
      return d;
    }
    IDiagramUIModel ci = null;
    for (IDiagramUIModel x : p.toDiagramArray()) {
      if (idOrName.equals(x.getName())) {
        return x;
      }
      if (ci == null && idOrName.equalsIgnoreCase(x.getName())) {
        ci = x;
      }
    }
    if (ci != null) {
      return ci;
    }
    throw new ToolException("No diagram with id or name '" + idOrName + "'");
  }

  /**
   * Finds the view of something on a diagram. Accepts a shape/connector id, a model element id (its
   * view on that diagram) or the exact name of an element shown on the diagram.
   */
  static IDiagramElement view(IDiagramUIModel diagram, String ref) {
    if (ref == null || ref.isEmpty()) {
      throw new ToolException("A shape reference is required");
    }
    IDiagramElement de = diagram.getDiagramElementById(ref);
    if (de != null) {
      return de;
    }
    IModelElement m = project().getModelElementById(ref);
    if (m != null) {
      for (IDiagramElement v : m.getDiagramElements()) {
        if (v.getDiagramUIModel() != null
            && diagram.getId().equals(v.getDiagramUIModel().getId())) {
          return v;
        }
      }
      throw new ToolException(
          "Element '"
              + m.getName()
              + "' ("
              + ref
              + ") is not shown on diagram '"
              + diagram.getName()
              + "'");
    }
    IDiagramElement byName = null;
    for (IDiagramElement v : diagram.toDiagramElementArray()) {
      IModelElement vm = v.getModelElement();
      if (vm != null && ref.equals(vm.getName())) {
        if (byName != null) {
          throw new ToolException(
              "Name '" + ref + "' is ambiguous on diagram '" + diagram.getName() + "', use an id");
        }
        byName = v;
      }
    }
    if (byName != null) {
      return byName;
    }
    throw new ToolException(
        "Nothing matching '" + ref + "' on diagram '" + diagram.getName() + "'");
  }

  static IShapeUIModel shape(IDiagramUIModel diagram, String ref) {
    IDiagramElement de = view(diagram, ref);
    if (!(de instanceof IShapeUIModel)) {
      throw new ToolException("'" + ref + "' is a connector, not a shape");
    }
    return (IShapeUIModel) de;
  }

  // -------------------------------------------------------------- describe

  static Map<String, Object> brief(IModelElement m) {
    Map<String, Object> r = new LinkedHashMap<>();
    if (m == null) {
      return r;
    }
    r.put("id", m.getId());
    r.put("type", m.getModelType());
    r.put("name", m.getName());
    return r;
  }

  static Map<String, Object> describeModel(IModelElement m, boolean detail) {
    Map<String, Object> r = brief(m);
    IModelElement parent = m.getParent();
    if (parent != null) {
      r.put("parent", brief(parent));
    }
    putIfText(r, "documentation", m.getDocumentation());
    List<String> stereotypes = new ArrayList<>();
    IStereotype[] sts = m.toStereotypeModelArray();
    if (sts != null) {
      for (IStereotype s : sts) {
        stereotypes.add(s.getName());
      }
    }
    if (!stereotypes.isEmpty()) {
      r.put("stereotypes", stereotypes);
    }
    if (m instanceof IRelationship) {
      IRelationship rel = (IRelationship) m;
      boolean rev = reversed(rel);
      r.put("from", brief(rev ? rel.getTo() : rel.getFrom()));
      r.put("to", brief(rev ? rel.getFrom() : rel.getTo()));
    }
    if (!detail) {
      return r;
    }
    if (m instanceof IUseCase) {
      List<Object> flows = new ArrayList<>();
      IStepContainer[] containers = ((IUseCase) m).toStepContainerArray();
      if (containers != null) {
        for (IStepContainer sc : containers) {
          Map<String, Object> f = brief(sc);
          List<Object> steps = new ArrayList<>();
          IModelElement[] kids = sc.toChildArray();
          if (kids != null) {
            for (IModelElement st : kids) {
              steps.add(st.getName());
            }
          }
          f.put("steps", steps);
          flows.add(f);
        }
      }
      if (!flows.isEmpty()) {
        r.put("flows", flows);
      }
      List<Object> eps = ExtensionPoints.describe(m);
      if (!eps.isEmpty()) {
        r.put("extensionPoints", eps);
      }
    }
    r.put("properties", properties(m));
    List<Object> children = new ArrayList<>();
    IModelElement[] kids = m.toChildArray();
    if (kids != null) {
      for (IModelElement c : kids) {
        children.add(describeModel(c, false));
      }
    }
    if (!children.isEmpty()) {
      r.put("children", children);
    }
    List<Object> rels = new ArrayList<>();
    collectRelationships(rels, m.toFromRelationshipArray(), "outgoing");
    collectRelationships(rels, m.toToRelationshipArray(), "incoming");
    IRelationshipEnd[] fromEnds = m.toFromRelationshipEndArray();
    if (fromEnds != null) {
      for (IRelationshipEnd e : fromEnds) {
        addEndRelationship(rels, e, "outgoing");
      }
    }
    IRelationshipEnd[] toEnds = m.toToRelationshipEndArray();
    if (toEnds != null) {
      for (IRelationshipEnd e : toEnds) {
        addEndRelationship(rels, e, "incoming");
      }
    }
    if (!rels.isEmpty()) {
      r.put("relationships", rels);
    }
    List<Object> views = new ArrayList<>();
    IDiagramElement[] des = m.getDiagramElements();
    if (des != null) {
      for (IDiagramElement de : des) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("shapeId", de.getId());
        if (de.getDiagramUIModel() != null) {
          v.put("diagramId", de.getDiagramUIModel().getId());
          v.put("diagramName", de.getDiagramUIModel().getName());
        }
        views.add(v);
      }
    }
    if (!views.isEmpty()) {
      r.put("views", views);
    }
    return r;
  }

  private static void collectRelationships(
      List<Object> out, com.vp.plugin.model.ISimpleRelationship[] rels, String direction) {
    if (rels == null) {
      return;
    }
    for (IRelationship rel : rels) {
      boolean rev = reversed(rel);
      Map<String, Object> x = brief(rel);
      x.put(
          "direction", rev ? ("outgoing".equals(direction) ? "incoming" : "outgoing") : direction);
      x.put("from", brief(rev ? rel.getTo() : rel.getFrom()));
      x.put("to", brief(rev ? rel.getFrom() : rel.getTo()));
      out.add(x);
    }
  }

  private static void addEndRelationship(List<Object> out, IRelationshipEnd end, String dir) {
    IEndRelationship rel = end.getEndRelationship();
    if (rel == null) {
      return;
    }
    Map<String, Object> x = brief(rel);
    x.put("direction", dir);
    IRelationshipEnd opposite = end.getOppositeEnd();
    if (opposite != null) {
      x.put("other", brief(opposite.getModelElement()));
    }
    out.add(x);
  }

  private static final java.util.Set<String> HIDDEN_PROPERTIES =
      new java.util.HashSet<>(
          java.util.Arrays.asList(
              "name",
              "parent",
              "modelType",
              "stereotypes",
              "documentation",
              "userIDLastNumericValue",
              "backlogActivityId",
              "qualityScore",
              "qualityReason"));

  /**
   * Generalization, Realization and Extend are stored by VP with "from" = general / supplier / base
   * element. The tools always speak UML direction (child -> parent), so these are flipped.
   */
  static boolean reversed(IModelElement rel) {
    return rel != null && DiagramBuilder.REVERSED_IN_VP.contains(rel.getModelType());
  }

  /** Non-empty model properties, as name → simple value (bookkeeping fields omitted). */
  static Map<String, Object> properties(IModelElement m) {
    Map<String, Object> props = new LinkedHashMap<>();
    IModelProperty[] arr = m.toModelPropertyArray();
    if (arr == null) {
      return props;
    }
    for (IModelProperty p : arr) {
      if (HIDDEN_PROPERTIES.contains(p.getName()) || p.getName().startsWith("pm")) {
        continue; // already reported elsewhere, or bookkeeping noise
      }
      try {
        Object v = propertyValue(p);
        if (v == null
            || (v instanceof String && ((String) v).isEmpty())
            || (v instanceof List && ((List<?>) v).isEmpty())) {
          continue;
        }
        props.put(p.getName(), v);
      } catch (RuntimeException e) {
        // some properties cannot be read in every state; skip them
      }
    }
    return props;
  }

  private static Object propertyValue(IModelProperty p) {
    switch (p.getType()) {
      case IModelProperty.TYPE_BOOLEAN:
        return p.getValueAsBoolean();
      case IModelProperty.TYPE_INT:
        return p.getValueAsInt();
      case IModelProperty.TYPE_STRING:
      case IModelProperty.TYPE_STRING_SELECTION:
      case IModelProperty.TYPE_HTML_STRING:
      case IModelProperty.TYPE_CODE_SIGNATURE:
        return truncate(p.getValueAsString());
      case IModelProperty.TYPE_STRING_ARRAY:
        String[] arr = p.getValueAsStringArray();
        List<Object> sl = new ArrayList<>();
        if (arr != null) {
          for (String s : arr) {
            sl.add(s);
          }
        }
        return sl;
      case IModelProperty.TYPE_MODEL:
      case IModelProperty.TYPE_COMPOSITE_MODEL:
      case IModelProperty.TYPE_PARENT_MODEL:
        IModelElement ref = p.getValueAsModel();
        return ref == null ? null : brief(ref);
      case IModelProperty.TYPE_MODEL_COLLECTION:
      case IModelProperty.TYPE_COMPOSITE_MODEL_COLLECTION:
        IModelElement[] items = p.getValueAsModelCollection();
        List<Object> list = new ArrayList<>();
        if (items != null) {
          for (int i = 0; i < items.length && i < MAX_COLLECTION_ITEMS; i++) {
            list.add(brief(items[i]));
          }
          if (items.length > MAX_COLLECTION_ITEMS) {
            list.add("... " + (items.length - MAX_COLLECTION_ITEMS) + " more");
          }
        }
        return list;
      default:
        return null;
    }
  }

  static Map<String, Object> describeView(IDiagramElement de) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("shapeId", de.getId());
    r.put("shapeType", de.getShapeType());
    IModelElement m = de.getModelElement();
    if (m != null) {
      r.put("modelId", m.getId());
      r.put("modelType", m.getModelType());
      r.put("name", m.getName());
    }
    if (de instanceof IConnectorUIModel) {
      IConnectorUIModel c = (IConnectorUIModel) de;
      IDiagramElement from = c.getFromShape() != null ? c.getFromShape() : c.getFromConnector();
      IDiagramElement to = c.getToShape() != null ? c.getToShape() : c.getToConnector();
      if (m != null && reversed(m)) {
        IDiagramElement tmp = from;
        from = to;
        to = tmp;
      }
      if (from != null) {
        r.put("fromShapeId", from.getId());
      }
      if (to != null) {
        r.put("toShapeId", to.getId());
      }
    } else {
      r.put("x", de.getX());
      r.put("y", de.getY());
      r.put("width", de.getWidth());
      r.put("height", de.getHeight());
      if (de instanceof IShapeUIModel) {
        IDiagramElement parent = ((IShapeUIModel) de).getParent();
        if (parent != null) {
          r.put("parentShapeId", parent.getId());
        }
      }
    }
    return r;
  }

  static Map<String, Object> describeDiagram(IDiagramUIModel d, boolean contents) {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("id", d.getId());
    r.put("type", d.getType());
    r.put("name", d.getName());
    IModelElement parent = d.getParentModel();
    if (parent != null) {
      r.put("parent", brief(parent));
    }
    if (!contents) {
      r.put("elementCount", d.diagramElementCount());
      return r;
    }
    putIfText(r, "documentation", d.getDocumentation());
    List<Object> shapes = new ArrayList<>();
    IShapeUIModel[] sa = d.toShapeUIModelArray();
    if (sa != null) {
      for (IShapeUIModel s : sa) {
        shapes.add(describeView(s));
      }
    }
    List<Object> connectors = new ArrayList<>();
    IConnectorUIModel[] ca = d.toConnectorUIModelArray();
    if (ca != null) {
      for (IConnectorUIModel c : ca) {
        connectors.add(describeView(c));
      }
    }
    r.put("shapes", shapes);
    r.put("connectors", connectors);
    return r;
  }

  private static void putIfText(Map<String, Object> r, String key, String text) {
    if (text != null && !text.isEmpty()) {
      r.put(key, truncate(text));
    }
  }

  private static String truncate(String s) {
    if (s == null || s.length() <= MAX_STRING) {
      return s;
    }
    return s.substring(0, MAX_STRING) + "...";
  }

  // ---------------------------------------------------------------- mutate

  // UML types that VP models as a Class with a stereotype (they are not separate model types)
  private static final Map<String, String> STEREOTYPED_CLASSES = new LinkedHashMap<>();

  static {
    STEREOTYPED_CLASSES.put("Interface", "Interface");
    STEREOTYPED_CLASSES.put("Enumeration", "enumeration");
    STEREOTYPED_CLASSES.put("DataType", "datatype");
    STEREOTYPED_CLASSES.put("Primitive", "primitive");
  }

  /** Creates a model element of the given VP model type (e.g. "UseCase"). */
  static IModelElement create(String modelType) {
    String stereotype = STEREOTYPED_CLASSES.get(modelType);
    if (stereotype != null) {
      IModelElement c = create("Class");
      c.addStereotype(stereotype);
      return c;
    }
    IModelElement m;
    try {
      m = factory().create(modelType);
    } catch (RuntimeException e) {
      throw new ToolException("Cannot create model type '" + modelType + "': " + e, e);
    }
    if (m == null) {
      throw new ToolException(
          "Unknown model type '" + modelType + "'. Use vp_list_types for valid types.");
    }
    return m;
  }

  /**
   * Applies name/documentation/stereotypes and arbitrary properties. Property keys are setter names
   * without "set" (e.g. "visibility", "multiplicity", "primaryKey") or VP model property names.
   * Keys prefixed with "from." / "to." target the ends of an association-like relationship.
   */
  static void apply(IModelElement m, Map<String, Object> props) throws Exception {
    if (props == null) {
      return;
    }
    List<String> errors = new ArrayList<>();
    for (Map.Entry<String, Object> e : props.entrySet()) {
      String key = e.getKey();
      Object value = e.getValue();
      try {
        setOne(m, key, value);
      } catch (ToolException ex) {
        errors.add(ex.getMessage());
      } catch (Exception ex) {
        errors.add(key + ": " + ex);
      }
    }
    if (!errors.isEmpty()) {
      throw new ToolException("Some properties were not set: " + String.join("; ", errors));
    }
  }

  private static void setOne(IModelElement m, String key, Object value) throws Exception {
    if ("documentation".equals(key)) {
      m.setDocumentation(value == null ? "" : String.valueOf(value));
      return;
    }
    if ("removeStereotypes".equals(key) || "removeStereotype".equals(key)) {
      if (value instanceof List) {
        for (Object s : (List<?>) value) {
          m.removeStereotype(String.valueOf(s));
        }
      } else if (value != null) {
        m.removeStereotype(String.valueOf(value));
      }
      return;
    }
    if ("stereotypes".equals(key) || "stereotype".equals(key)) {
      if (value instanceof List) {
        for (Object s : (List<?>) value) {
          m.addStereotype(String.valueOf(s));
        }
      } else if (value != null) {
        m.addStereotype(String.valueOf(value));
      }
      return;
    }
    int dot = key.indexOf('.');
    if (dot > 0 && m instanceof IEndRelationship) {
      String end = key.substring(0, dot).toLowerCase(Locale.ROOT);
      IRelationshipEnd target =
          "from".equals(end)
              ? ((IEndRelationship) m).getFromEnd()
              : "to".equals(end) ? ((IEndRelationship) m).getToEnd() : null;
      if (target == null) {
        throw new ToolException(key + ": use 'from.<prop>' or 'to.<prop>'");
      }
      setOne(target, key.substring(dot + 1), value);
      return;
    }
    if ("qualifier".equals(key) && m instanceof IAssociationEnd && value != null) {
      setQualifier((IAssociationEnd) m, String.valueOf(value));
      return;
    }
    if ("kind".equals(key) && m instanceof IMessage) {
      setMessageKind((IMessage) m, String.valueOf(value));
      return;
    }
    if ("type".equals(key) && m instanceof IDBColumn && value != null) {
      setColumnType((IDBColumn) m, String.valueOf(value));
      return;
    }
    if ("aggregationKind".equals(key) && value != null) {
      value = aggregationKind(String.valueOf(value));
    }
    if (Reflect.trySet(m, key, value, VpModel::resolveValue)) {
      return;
    }
    IModelProperty p = m.getModelPropertyByName(key);
    if (p == null) {
      throw new ToolException(
          "'"
              + key
              + "' is not a property of "
              + m.getModelType()
              + " (see vp_get_element for its properties)");
    }
    setModelProperty(p, value);
  }

  /** Qualifier of an association end, e.g. "isbn: String" or "row: int, col: int". */
  static void setQualifier(IAssociationEnd end, String spec) {
    IQualifier q = factory().createQualifier();
    for (String part : spec.split(",")) {
      String p = part.trim();
      if (p.isEmpty()) {
        continue;
      }
      IAttribute a = q.createAttribute();
      int colon = p.indexOf(':');
      a.setName((colon >= 0 ? p.substring(0, colon) : p).trim());
      if (colon >= 0) {
        a.setType(p.substring(colon + 1).trim());
      }
    }
    end.setQualifier(q);
  }

  /** Message kind: call (default), return (dashed), send/async, create, destroy. */
  static void setMessageKind(IMessage msg, String kind) {
    IModelElementFactory f = factory();
    switch (kind.trim().toLowerCase(Locale.ROOT)) {
      case "call":
      case "sync":
        msg.setActionType(f.createActionTypeCall());
        return;
      case "return":
      case "reply":
        msg.setActionType(f.createActionTypeReturn());
        return;
      case "send":
      case "async":
      case "asynchronous":
        msg.setActionType(f.createActionTypeSend());
        msg.setAsynchronous(true);
        return;
      case "create":
        msg.setActionType(f.createActionTypeCreate());
        return;
      case "destroy":
        msg.setActionType(f.createActionTypeDestroy());
        return;
      default:
        throw new ToolException(
            "Unknown message kind '" + kind + "' (call, return, send, create, destroy)");
    }
  }

  /** Sets an ER column type such as "varchar(255)", "decimal(10,2)" or "integer". */
  static void setColumnType(IDBColumn col, String spec) {
    String t = spec.trim();
    int length = 0;
    int scale = 0;
    int open = t.indexOf('(');
    if (open > 0 && t.endsWith(")")) {
      String[] nums = t.substring(open + 1, t.length() - 1).split(",");
      try {
        length = Integer.parseInt(nums[0].trim());
        if (nums.length > 1) {
          scale = Integer.parseInt(nums[1].trim());
        }
      } catch (NumberFormatException e) {
        throw new ToolException("Bad column type '" + spec + "'");
      }
      t = t.substring(0, open).trim();
    } else if (col.getLength() > 0) {
      length = col.getLength();
      scale = col.getScale();
    }
    if (!col.setType(t, length, scale)) {
      throw new ToolException(
          "Visual Paradigm does not know the column type '"
              + t
              + "' for the project's database (try e.g. integer, varchar, text, date,"
              + " timestamp, boolean, decimal)");
    }
  }

  /**
   * Sets presentation options of a shape or connector, e.g. displayStereotypeIcon=false (class box
   * with the stereotype text instead of the stereotype's icon), presentationOption, background.
   */
  static void applyView(IDiagramElement view, Map<String, Object> props) throws Exception {
    if (props == null || props.isEmpty()) {
      return;
    }
    List<String> errors = new ArrayList<>();
    for (Map.Entry<String, Object> e : props.entrySet()) {
      String key = e.getKey();
      Object value = e.getValue();
      try {
        if ("background".equals(key) || "foreground".equals(key)) {
          java.awt.Color c = java.awt.Color.decode(String.valueOf(value));
          if ("background".equals(key)) {
            view.setBackground(c);
          } else {
            view.setForeground(c);
          }
          continue;
        }
        if (!Reflect.trySet(view, key, value, null)) {
          errors.add("'" + key + "' is not a view property of " + view.getShapeType());
        }
      } catch (Exception ex) {
        errors.add(key + ": " + ex);
      }
    }
    if ("false".equals(String.valueOf(props.get("displayStereotypeIcon")))) {
      // entity/boundary/control are drawn as robustness icons by a separate class shape flag
      for (String flag :
          new String[] {
            "displayAsRobustnessAnalysisIcon", "overrideAppearanceWithStereotypeIcon"
          }) {
        try {
          Reflect.trySet(view, flag, Boolean.FALSE, null);
        } catch (Exception ex) {
          // not every shape has it
        }
      }
    }
    if (view instanceof IShapeUIModel
        && !props.containsKey("width")
        && !props.containsKey("height")) {
      IShapeUIModel shape = (IShapeUIModel) view;
      int w = shape.getWidth();
      int h = shape.getHeight();
      try {
        shape.fitSize(); // a different presentation needs a different size
      } catch (RuntimeException ex) {
        // cosmetic
      }
      if (shape.getWidth() != w || shape.getHeight() != h) {
        for (IConnectorUIModel c : Geometry.attached(shape)) {
          Geometry.reroute(c);
        }
      }
    }
    view.setRequestResetCaption(true);
    if (!errors.isEmpty()) {
      throw new ToolException("Some view properties were not set: " + String.join("; ", errors));
    }
  }

  /** Accepts UML spellings (composite, shared, ...) for VP's aggregation kind values. */
  static String aggregationKind(String v) {
    switch (v.trim().toLowerCase(Locale.ROOT)) {
      case "composite":
      case "composited":
      case "composition":
        return "Composited";
      case "shared":
      case "aggregation":
      case "aggregate":
        return "Aggregation";
      case "none":
      case "":
        return "None";
      default:
        return v;
    }
  }

  private static void setModelProperty(IModelProperty p, Object value) {
    switch (p.getType()) {
      case IModelProperty.TYPE_BOOLEAN:
        p.setValue(value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean("" + value));
        return;
      case IModelProperty.TYPE_INT:
        p.setValue(
            value instanceof Number
                ? ((Number) value).intValue()
                : Integer.parseInt(String.valueOf(value)));
        return;
      case IModelProperty.TYPE_MODEL:
      case IModelProperty.TYPE_COMPOSITE_MODEL:
        p.setValue(value == null ? null : model(String.valueOf(value)));
        return;
      case IModelProperty.TYPE_STRING_ARRAY:
        if (value instanceof List) {
          List<?> l = (List<?>) value;
          String[] arr = new String[l.size()];
          for (int i = 0; i < arr.length; i++) {
            arr[i] = String.valueOf(l.get(i));
          }
          p.setValue(arr);
        } else {
          p.setValue(new String[] {String.valueOf(value)});
        }
        return;
      default:
        p.setValue(value == null ? null : String.valueOf(value));
    }
  }

  /**
   * Names a newly created element. Visual Paradigm silently rejects a name that another element of
   * the same type already has in the same namespace (the element keeps its type name).
   *
   * @param owner namespace of the element ({@code null} = project root)
   * @return {@code null} if the name was applied, otherwise the existing element with that name
   */
  static IModelElement rename(IModelElement m, String name, IModelElement owner) {
    m.setName(name);
    if (name.equals(m.getName())) {
      return null;
    }
    IModelElement existing =
        owner != null
            ? childNamed(owner, m.getModelType(), name)
            : rootNamed(m.getModelType(), name, m.getId());
    if (existing == null) {
      throw new ToolException(
          "Visual Paradigm rejected the name '"
              + name
              + "' for a "
              + m.getModelType()
              + " (it kept '"
              + m.getName()
              + "')");
    }
    return existing;
  }

  /** Direct child of {@code parent} with the given type and exact name. */
  static IModelElement childNamed(IModelElement parent, String type, String name) {
    IModelElement[] kids = parent.toChildArray();
    if (kids != null) {
      for (IModelElement k : kids) {
        if (type.equals(k.getModelType()) && name.equals(k.getName())) {
          return k;
        }
      }
    }
    return null;
  }

  private static IModelElement rootNamed(String type, String name, String excludeId) {
    IModelElement[] roots = project().toModelElementArray(type);
    if (roots != null) {
      for (IModelElement r : roots) {
        if (name.equals(r.getName()) && !r.getId().equals(excludeId)) {
          return r;
        }
      }
    }
    for (IModelElement r : project().toAllLevelModelElementArray(type)) {
      if (name.equals(r.getName()) && !r.getId().equals(excludeId)) {
        return r;
      }
    }
    return null;
  }

  /**
   * Creates a child element inside {@code parent} (attribute in a class, column in a table,
   * parameter in an operation, ...). Prefers a typed {@code create<Type>()} method of the parent.
   */
  static IModelElement createChild(IModelElement parent, String childType) throws Exception {
    Object made = Reflect.invokeNoArg(parent, "create" + childType);
    if (made instanceof IModelElement) {
      return (IModelElement) made;
    }
    IModelElement child;
    try {
      child = parent.createChild(childType);
    } catch (RuntimeException e) {
      child = null;
    }
    if (child != null) {
      return child;
    }
    child = create(childType);
    parent.addChild(child);
    return child;
  }
}
