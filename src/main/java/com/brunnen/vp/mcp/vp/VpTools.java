package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.Args;
import com.brunnen.vp.mcp.protocol.Schema;
import com.brunnen.vp.mcp.protocol.ToolException;
import com.brunnen.vp.mcp.protocol.ToolRegistry;
import com.brunnen.vp.mcp.protocol.ToolResult;
import com.vp.plugin.DiagramManager;
import com.vp.plugin.ExportDiagramAsImageOption;
import com.vp.plugin.ModelConvertionManager;
import com.vp.plugin.ProjectManager;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IActor;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.IProject;
import com.vp.plugin.model.IProjectTransaction;
import com.vp.plugin.model.IStep;
import com.vp.plugin.model.IStepContainer;
import com.vp.plugin.model.IUseCase;
import java.awt.Dimension;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/** All MCP tools backed by the Visual Paradigm Open API. */
public final class VpTools {

  /** Guidance sent to the client in the initialize response. */
  public static final String INSTRUCTIONS =
      String.join(
          "\n",
          "Tools to read and edit the model open in Visual Paradigm (VP).",
          "Workflow: vp_create_diagram -> vp_build_diagram (many shapes+connectors in one call)"
              + " or vp_add_shape/vp_add_connector -> vp_layout_diagram -> vp_export_diagram_image"
              + " to look at the result. Use vp_get_diagram to see ids of what is on a diagram.",
          "References: shapes/connectors can be referenced by shape id, model element id, or the"
              + " exact element name on that diagram; in vp_build_diagram also by your own 'key'.",
          "Types are VP model type names, e.g.:",
          "- Use case: Actor, UseCase, System (boundary; put use cases in it via parent),"
              + " Package, NOTE; connectors Association, Include (from base to included),"
              + " Extend (from extension to base; no extension point unless 'extensionPoint'"
              + " names one), Generalization (from child to parent),"
              + " Dependency.",
          "- Activity: InitialNode, ActivityAction, DecisionNode, MergeNode, ForkNode, JoinNode,"
              + " ActivityFinalNode, FlowFinalNode, ActivityObject; connectors ControlFlow"
              + " (property guard), ObjectFlow. Swimlanes: one element {type:"
              + " ActivitySwimlane2, partitions: [{key, name}, ...], orientation?: vertical|"
              + "horizontal}; put nodes into a lane with parent = partition key (or, later, the"
              + " partition name/id).",
          "- Sequence: prefer vp_build_sequence (activations, fragments, refs in one call);"
              + " otherwise InteractionActor, InteractionLifeLine; connector"
              + " Message (ordered top-down by creation; property kind: call|return|send|"
              + "create|destroy).",
          "- Class: Class, Interface (use stereotype), Package, Enumeration; members via"
              + " children/vp_add_child: Attribute, Operation (children: Parameter);"
              + " connectors Association (properties from./to.multiplicity,"
              + " from./to.aggregationKind),"
              + " Generalization, Realization, Dependency.",
          "- ER: DBTable with children DBColumn (properties type, length, primaryKey, nullable);"
              + " connector DBForeignKey.",
          "- State machine: InitialPseudoState, State2, FinalState2, Choice, Fork, Join;"
              + " connector Transition2.",
          "vp_list_types(diagram) returns the exact shape types a diagram accepts.",
          "Properties: any setter of the VP model object without 'set' (visibility, type,"
              + " multiplicity, abstract, primaryKey, ...) or a property name shown by"
              + " vp_get_element. Int enum setters accept constant names (e.g. 'TYPE_CALL').",
          "Changes are not saved automatically: call vp_save_project when done.",
          "If a tool says VP is busy, a dialog is open: read it with vp_list_dialogs and answer it"
              + " with vp_press_dialog_button.");

  private VpTools() {}

  /** Registers all Visual Paradigm tools. */
  public static ToolRegistry register(ToolRegistry r) {
    registerProject(r);
    registerDialogs(r);
    registerRead(r);
    registerEdit(r);
    registerDiagramOps(r);
    registerUseCase(r);
    return r;
  }

  // ------------------------------------------------------------- helpers

  private static ToolResult read(Callable<Object> body) throws Exception {
    return ToolResult.json(Edt.call(body));
  }

  /** Runs a model change on the EDT as one undoable project transaction. */
  private static ToolResult write(Callable<Object> body) throws Exception {
    return ToolResult.json(Edt.call(() -> inTransaction(body), true));
  }

  private static Object inTransaction(Callable<Object> body) throws Exception {
    IProjectTransaction tx = null;
    try {
      tx = VpModel.project().startProjectTransaction();
    } catch (RuntimeException e) {
      tx = null; // transactions are an optimisation (single undo step)
    }
    try {
      return body.call();
    } finally {
      VpModel.BATCH_KEYS.remove();
      if (tx != null) {
        tx.endTransaction();
      }
    }
  }

  /**
   * Removes a shape (with its nested shapes and attached connectors) or a connector from its
   * diagram, keeping the model. Connectors go first so that VP does not ask about them.
   */
  private static int removeView(IDiagramElement de) {
    IDiagramUIModel d = de.getDiagramUIModel();
    List<IDiagramElement> doomed = new ArrayList<>();
    if (de instanceof IShapeUIModel) {
      List<IShapeUIModel> shapes = new ArrayList<>(Geometry.descendants((IShapeUIModel) de));
      for (IShapeUIModel s : shapes) {
        doomed.addAll(Geometry.attached(s));
      }
      doomed.addAll(Geometry.attached(de));
      java.util.Collections.reverse(shapes); // innermost first
      doomed.addAll(shapes);
    }
    doomed.add(de);
    java.util.Set<String> done = new java.util.HashSet<>();
    int n = 0;
    for (IDiagramElement x : doomed) {
      if (done.add(x.getId()) && d.getDiagramElementById(x.getId()) != null) {
        x.deleteViewOnly();
        n++;
      }
    }
    if (d.getDiagramElementById(de.getId()) != null) {
      throw new ToolException("Visual Paradigm did not remove " + de.getId() + " from the diagram");
    }
    return n;
  }

  private static boolean sameFile(File a, File b) {
    if (b == null) {
      return false;
    }
    try {
      return a.getCanonicalFile().equals(b.getCanonicalFile());
    } catch (java.io.IOException e) {
      return a.getAbsoluteFile().equals(b.getAbsoluteFile());
    }
  }

  private static Map<String, Object> ok(String key, Object value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(key, value);
    return m;
  }

  // ------------------------------------------------------------- project

  private static void registerProject(ToolRegistry r) {
    r.addReadOnly(
        "vp_get_project_info",
        "Information about the Visual Paradigm project that is currently open: name, file,"
            + " unsaved changes, VP version and number of diagrams.",
        Schema.object(),
        a ->
            read(
                () -> {
                  IProject p = VpModel.project();
                  Map<String, Object> m = new LinkedHashMap<>();
                  m.put("id", p.getId());
                  m.put("name", p.getName());
                  File f = p.getProjectFile();
                  m.put("file", f == null ? null : f.getAbsolutePath());
                  m.put("modified", p.isModified());
                  m.put("diagramCount", p.toDiagramArray().length);
                  m.put("modelElementCount", p.allLevelModelElementCount());
                  try {
                    m.put(
                        "vpVersion",
                        VpModel.app().getProductInfo().getName()
                            + " "
                            + VpModel.app().getProductInfo().getVersion()
                            + " build "
                            + VpModel.app().getBuildNo());
                  } catch (RuntimeException e) {
                    m.put("vpVersion", "unknown");
                  }
                  return m;
                }));

    r.add(
        "vp_save_project",
        "Saves the project. Without 'path' saves to the current file (a project that was never"
            + " saved needs 'path', e.g. C:/work/model.vpp).",
        Schema.object().str("path", "Target .vpp file (save as)", false),
        a ->
            // never inside a project transaction: VP's file save fails with
            // "table LAST_PROJECT_INFO already exists" when a transaction is open
            ToolResult.json(
                Edt.call(
                    () -> {
                      ProjectManager pm = VpModel.app().getProjectManager();
                      File current = VpModel.project().getProjectFile();
                      boolean done;
                      if (a.has("path") && !sameFile(new File(a.str("path")), current)) {
                        File f = new File(a.str("path")).getAbsoluteFile();
                        File dir = f.getParentFile();
                        if (dir != null && !dir.exists() && !dir.mkdirs()) {
                          throw new ToolException("Cannot create directory " + dir);
                        }
                        done = pm.saveProjectAs(f);
                      } else {
                        if (current == null) {
                          throw new ToolException(
                              "The project has never been saved: pass 'path' to choose a file");
                        }
                        done = pm.saveProject();
                      }
                      File f = VpModel.project().getProjectFile();
                      Map<String, Object> m = ok("saved", done);
                      m.put("file", f == null ? null : f.getAbsolutePath());
                      m.put("modified", VpModel.project().isModified());
                      return m;
                    },
                    true)));

    r.add(
        "vp_open_project",
        "Opens a .vpp project file in Visual Paradigm (VP may ask the user to save the current"
            + " project first).",
        Schema.object().str("path", "Path of the .vpp file", true),
        a ->
            ToolResult.json(
                Edt.call(
                    () -> {
                      File f = new File(a.str("path"));
                      if (!f.isFile()) {
                        throw new ToolException("File not found: " + f.getAbsolutePath());
                      }
                      boolean done = VpModel.app().getProjectManager().openProject(f);
                      Map<String, Object> m = ok("opened", done);
                      m.put("name", VpModel.project().getName());
                      return m;
                    },
                    true)));

    r.add(
        "vp_new_project",
        "Creates a new empty project (VP may ask the user to save the current project first).",
        Schema.object(),
        a ->
            ToolResult.json(
                Edt.call(
                    () -> {
                      boolean done = VpModel.app().getProjectManager().newProject();
                      Map<String, Object> m = ok("created", done);
                      m.put("name", VpModel.project().getName());
                      return m;
                    },
                    true)));
  }

  // ------------------------------------------------------------- dialogs

  private static void registerDialogs(ToolRegistry r) {
    r.addReadOnly(
        "vp_list_dialogs",
        "Dialogs currently open in Visual Paradigm (title, text, buttons). Use it when a tool"
            + " reports that VP is busy.",
        Schema.object(),
        a -> read(Dialogs::list));
    r.add(
        "vp_press_dialog_button",
        "Answers a Visual Paradigm dialog by pressing one of its buttons (label as listed by"
            + " vp_list_dialogs) or 'close'. Read the dialog text first and only choose an answer"
            + " that matches what the user wants (e.g. do not discard unsaved work).",
        Schema.object()
            .str("button", "Button label, or 'close' to close the window", true)
            .integer(
                "index", "Dialog index from vp_list_dialogs (default: top modal dialog)", false),
        a -> read(() -> Dialogs.press(a.str("button"), a.intOrNull("index"))));
  }

  // ---------------------------------------------------------------- read

  private static void registerRead(ToolRegistry r) {
    r.addReadOnly(
        "vp_list_diagrams",
        "Lists the diagrams of the project (id, type, name, element count).",
        Schema.object()
            .str("type", "Only this diagram type, e.g. UseCaseDiagram, ClassDiagram", false),
        a ->
            read(
                () -> {
                  List<Object> out = new ArrayList<>();
                  String type = a.str("type", null);
                  for (IDiagramUIModel d : VpModel.project().toDiagramArray()) {
                    if (type == null || type.equalsIgnoreCase(d.getType())) {
                      out.add(VpModel.describeDiagram(d, false));
                    }
                  }
                  return out;
                }));

    r.addReadOnly(
        "vp_get_diagram",
        "Contents of a diagram: every shape (shapeId, modelId, type, name, bounds, parent) and"
            + " connector (from/to shape ids).",
        Schema.object().str("diagram", "Diagram id or name", true),
        a -> read(() -> VpModel.describeDiagram(VpModel.diagram(a.str("diagram")), true)));

    r.addReadOnly(
        "vp_find_elements",
        "Searches model elements of the whole project by type and/or name.",
        Schema.object()
            .str("type", "Model type, e.g. UseCase, Actor, Class, DBTable", false)
            .str("name", "Exact name", false)
            .str("nameContains", "Case-insensitive substring of the name", false)
            .integer("limit", "Maximum results (default 200)", false),
        a ->
            read(
                () -> {
                  IProject p = VpModel.project();
                  String type = a.str("type", null);
                  String name = a.str("name", null);
                  String part = a.str("nameContains", null);
                  String partLc = part == null ? null : part.toLowerCase(Locale.ROOT);
                  int limit = a.integer("limit", 200);
                  Iterator<?> it =
                      type == null
                          ? p.allLevelModelElementIterator()
                          : p.allLevelModelElementIterator(type);
                  List<Object> out = new ArrayList<>();
                  int total = 0;
                  while (it.hasNext()) {
                    IModelElement m = (IModelElement) it.next();
                    String n = m.getName();
                    if (name != null && !name.equals(n)) {
                      continue;
                    }
                    if (partLc != null
                        && (n == null || !n.toLowerCase(Locale.ROOT).contains(partLc))) {
                      continue;
                    }
                    total++;
                    if (out.size() < limit) {
                      out.add(VpModel.describeModel(m, false));
                    }
                  }
                  Map<String, Object> res = ok("total", total);
                  res.put("elements", out);
                  return res;
                }));

    r.addReadOnly(
        "vp_get_element",
        "Full details of a model element (or of a shape's element): properties, children,"
            + " relationships and the diagrams it appears on.",
        Schema.object().str("id", "Model element id or shape id", true),
        a -> read(() -> VpModel.describeModel(VpModel.model(a.str("id")), true)));

    r.addReadOnly(
        "vp_get_view",
        "Presentation properties of a shape, connector or diagram (the keys usable in 'view').",
        Schema.object().str("id", "Shape, connector or diagram id", true),
        a ->
            read(
                () -> {
                  String id = a.str("id");
                  IDiagramUIModel d = VpModel.project().getDiagramById(id);
                  if (d != null) {
                    return VpModel.viewProperties(d);
                  }
                  IDiagramElement de = VpModel.project().getDiagramElementById(id);
                  if (de == null) {
                    throw new ToolException("No shape, connector or diagram with id '" + id + "'");
                  }
                  Map<String, Object> m = VpModel.describeView(de);
                  m.put("properties", VpModel.viewProperties(de));
                  return m;
                }));

    r.addReadOnly(
        "vp_list_types",
        "Shape types that may be placed on the given diagram (use them as 'type').",
        Schema.object().str("diagram", "Diagram id or name", true),
        a ->
            read(
                () -> {
                  IDiagramUIModel d = VpModel.diagram(a.str("diagram"));
                  String[] types = d.getAllowShapeTypes();
                  List<String> list = types == null ? new ArrayList<>() : Arrays.asList(types);
                  Map<String, Object> m = ok("diagramType", d.getType());
                  m.put("shapeTypes", list);
                  return m;
                }));
  }

  // ---------------------------------------------------------------- edit

  private static Schema shapeFields(Schema s) {
    return s.str("name", "Element name", false)
        .integer(
            "x",
            "Left, absolute diagram coordinate also inside a parent (auto when omitted)",
            false)
        .integer(
            "y", "Top, absolute diagram coordinate also inside a parent (auto when omitted)", false)
        .integer("width", "Width", false)
        .integer("height", "Height", false)
        .str("parent", "Container shape (System boundary, swimlane, package, frame...)", false)
        .str("modelId", "Show an existing model element instead of creating a new one", false)
        .obj("properties", "Properties to set, e.g. {\"visibility\":\"public\"}", false)
        .obj(
            "view",
            "Presentation of the shape, e.g. {\"displayStereotypeIcon\":false} or"
                + " {\"background\":\"#FFE0B2\"}",
            false)
        .objArray(
            "children",
            "Model children, e.g. [{\"type\":\"Attribute\",\"name\":\"id\","
                + "\"properties\":{\"type\":\"int\"}}]",
            false);
  }

  private static void registerEdit(ToolRegistry r) {
    r.add(
        "vp_create_diagram",
        "Creates a diagram. Types: UseCaseDiagram, ActivityDiagram, InteractionDiagram"
            + " (sequence), ClassDiagram, ERDiagram, StateDiagram, ComponentDiagram,"
            + " DeploymentDiagram, PackageDiagram, ObjectDiagram, CommunicationDiagram,"
            + " CompositeStructureDiagram, TimingDiagram, InteractionOverviewDiagram.",
        Schema.object()
            .str("type", "Diagram type", true)
            .str("name", "Diagram name", true)
            .str("documentation", "Diagram description", false)
            .bool("open", "Open it in VP (default true)", false),
        a ->
            write(
                () -> {
                  DiagramManager dm = VpModel.diagrams();
                  IDiagramUIModel d = dm.createDiagram(a.str("type"));
                  if (d == null) {
                    throw new ToolException("Unknown diagram type '" + a.str("type") + "'");
                  }
                  d.setName(a.str("name"));
                  if (a.has("documentation")) {
                    d.setDocumentation(a.str("documentation"));
                  }
                  if (a.bool("open", true)) {
                    dm.openDiagram(d);
                  }
                  return VpModel.describeDiagram(d, false);
                }));

    r.add(
        "vp_add_shape",
        "Creates a model element and shows it on a diagram (or shows an existing element via"
            + " modelId). Returns shapeId and modelId.",
        shapeFields(
            Schema.object()
                .str("diagram", "Diagram id or name", true)
                .str("type", "Model type, e.g. Actor, UseCase, Class, DBTable", false)),
        a ->
            write(
                () -> {
                  if (!a.has("type") && !a.has("modelId")) {
                    throw new ToolException("Pass 'type' (new element) or 'modelId'");
                  }
                  DiagramBuilder b = new DiagramBuilder(VpModel.diagram(a.str("diagram")));
                  Map<String, Object> res = b.addShape(a);
                  b.finish();
                  return res;
                }));

    r.add(
        "vp_add_connector",
        "Connects two shapes on a diagram with a relationship (Association, Include, Extend,"
            + " Generalization, ControlFlow, Message, Transition2, DBForeignKey, ...).",
        Schema.object()
            .str("diagram", "Diagram id or name", true)
            .str("type", "Relationship type (not needed with modelId)", false)
            .str("from", "Source shape (shape id, model id or name)", true)
            .str("to", "Target shape (shape id, model id or name)", true)
            .str("name", "Label, e.g. a message or guard name", false)
            .integer("y", "Sequence diagrams: vertical position of the message", false)
            .str("modelId", "Show an existing relationship instead of creating a new one", false)
            .str(
                "extensionPoint",
                "Extend only: name of the extension point to keep in the base use case"
                    + " (default: VP's automatic extension point is removed)",
                false)
            .obj(
                "properties", "e.g. {\"to.multiplicity\":\"0..*\"} or {\"guard\":\"[ok]\"}", false),
        a ->
            write(
                () -> {
                  DiagramBuilder b = new DiagramBuilder(VpModel.diagram(a.str("diagram")));
                  Map<String, Object> res = b.addConnector(a);
                  b.finish();
                  return res;
                }));

    r.add(
        "vp_add_child",
        "Adds a model child to an element: Attribute/Operation to a Class, Parameter to an"
            + " Operation, DBColumn to a DBTable, EnumerationLiteral, ... (nested 'children'"
            + " allowed).",
        Schema.object()
            .str("parentId", "Owner element id (or shape id)", true)
            .str("type", "Child model type", true)
            .str("name", "Child name", false)
            .obj("properties", "e.g. {\"type\":\"String\",\"visibility\":\"private\"}", false)
            .objArray("children", "Nested children", false),
        a -> write(() -> DiagramBuilder.addModelChild(VpModel.model(a.str("parentId")), a)));

    r.add(
        "vp_update_element",
        "Renames an element or diagram and/or sets its documentation and properties.",
        Schema.object()
            .str("id", "Model element, shape or diagram id", true)
            .str("name", "New name", false)
            .str("documentation", "Description text", false)
            .obj("properties", "Properties to set (see vp_get_element)", false)
            .obj(
                "view",
                "Presentation of a shape/connector (id must be a shape or connector id),"
                    + " e.g. {\"displayStereotypeIcon\":false}",
                false),
        a ->
            write(
                () -> {
                  String id = a.str("id");
                  IDiagramUIModel d = VpModel.project().getDiagramById(id);
                  if (d != null) {
                    if (a.has("name")) {
                      d.setName(a.str("name"));
                    }
                    if (a.has("documentation")) {
                      d.setDocumentation(a.str("documentation"));
                    }
                    for (Map.Entry<String, Object> e : a.map("view").entrySet()) {
                      if (!Reflect.trySet(d, e.getKey(), e.getValue(), null)) {
                        throw new ToolException(
                            "'" + e.getKey() + "' is not an option of a " + d.getType());
                      }
                    }
                    return VpModel.describeDiagram(d, false);
                  }
                  if (!a.map("view").isEmpty()) {
                    IDiagramElement view = VpModel.project().getDiagramElementById(id);
                    if (view == null) {
                      throw new ToolException("'view' needs a shape or connector id");
                    }
                    VpModel.applyView(view, a.map("view"));
                  }
                  IModelElement m = VpModel.model(id);
                  if (a.has("name")) {
                    m.setName(a.str("name"));
                  }
                  if (a.has("documentation")) {
                    m.setDocumentation(a.str("documentation"));
                  }
                  VpModel.apply(m, a.map("properties"));
                  return VpModel.describeModel(m, false);
                }));

    r.add(
        "vp_delete",
        "Deletes a diagram, a model element (with all its views) or, with viewOnly=true, just"
            + " one shape/connector from its diagram.",
        Schema.object()
            .str("id", "Diagram (id or name), model element, shape or connector id", true)
            .bool(
                "viewOnly",
                "Remove only the shape (with nested shapes and its connectors) from the diagram,"
                    + " keep the model",
                false),
        a ->
            write(
                () -> {
                  String id = a.str("id");
                  IProject p = VpModel.project();
                  IDiagramUIModel d = p.getDiagramById(id);
                  if (d != null) {
                    String name = d.getName();
                    d.delete();
                    return ok("deletedDiagram", name);
                  }
                  IDiagramElement de = p.getDiagramElementById(id);
                  if (de != null && a.bool("viewOnly", false)) {
                    return ok("deletedViews", removeView(de));
                  }
                  IModelElement m = de != null ? de.getModelElement() : VpModel.findModel(id);
                  if (m == null && de == null) {
                    d = VpModel.diagram(id); // diagram name; throws if nothing matches
                    String name = d.getName();
                    d.delete();
                    return ok("deletedDiagram", name);
                  }
                  if (m == null && de != null) {
                    return ok("deletedViews", removeView(de));
                  }
                  Map<String, Object> info = VpModel.brief(m);
                  m.delete();
                  return ok("deletedElement", info);
                }));
  }

  // -------------------------------------------------------- diagram ops

  private static final Map<String, Integer> LAYOUTS = new LinkedHashMap<>();

  static {
    LAYOUTS.put("hierarchic", DiagramManager.LAYOUT_HIERARCHIC);
    LAYOUTS.put("orthogonal", DiagramManager.LAYOUT_ORTHOGONAL);
    LAYOUTS.put("tree", DiagramManager.LAYOUT_TREE_STYLE);
    LAYOUTS.put("directed-tree", DiagramManager.LAYOUT_DIRECTED_TREE);
    LAYOUTS.put("compact-tree", DiagramManager.LAYOUT_COMPACT_TREE);
    LAYOUTS.put("balloon-tree", DiagramManager.LAYOUT_BALLOON_TREE);
    LAYOUTS.put("circular", DiagramManager.LAYOUT_CIRCULAR);
    LAYOUTS.put("organic", DiagramManager.LAYOUT_ORGANIC);
    LAYOUTS.put("smart-organic", DiagramManager.LAYOUT_SMART_ORGANIC_LAYOUT);
    LAYOUTS.put("compact", DiagramManager.LAYOUT_COMPACT);
    LAYOUTS.put("route-orthogonal", DiagramManager.LAYOUT_ROUTE_CONNECTORS_ORTHOGONAL);
    LAYOUTS.put("route-organic", DiagramManager.LAYOUT_ROUTE_CONNECTORS_ORGANIC);
  }

  /**
   * VP keeps connector geometry of a diagram that is not open in an editor in a stale state: moving
   * shapes or routing connectors there leaves lines ending at shape corners. Such a diagram is
   * opened first and the work runs in the next event, after VP has set it up.
   */
  private static Object layout(IDiagramUIModel d, String style) {
    if (d.isOpened()) {
      return doLayout(d, style);
    }
    VpModel.diagrams().openDiagram(d);
    javax.swing.SwingUtilities.invokeLater(
        () -> {
          try {
            doLayout(d, style);
          } catch (RuntimeException e) {
            System.out.println("[vp-mcp] layout failed: " + e);
          }
        });
    Map<String, Object> r = ok("layout", style == null ? "auto" : style);
    r.put("note", "diagram was opened in VP; layout applied right after");
    return r;
  }

  private static Object doLayout(IDiagramUIModel d, String style) {
    DiagramManager dm = VpModel.diagrams();
    String used = style == null ? "auto" : style;
    if ("reroute".equals(used)) {
      // keep every shape where it is, only redraw connectors straight between shape borders
      for (com.vp.plugin.diagram.IConnectorUIModel c : d.toConnectorUIModelArray()) {
        Geometry.reroute(c);
      }
      return ok("layout", used);
    }
    if ("layered".equals(used)
        || ("auto".equals(used)
            && !Geometry.hasFilledContainers(d)
            && ("ClassDiagram".equals(d.getType()) || "ERDiagram".equals(d.getType())))) {
      // VP's own layouts do not reliably move the shapes of class diagrams through the API
      Geometry.layeredLayout(d);
      return ok("layout", "layered");
    }
    if ("boundary".equals(used) || ("auto".equals(used) && Geometry.hasFilledContainers(d))) {
      // VP's layout ignores System boundaries (content sticks out, connectors vanish)
      Geometry.containerLayout(d);
      used = "boundary";
    } else if ("auto".equals(used)) {
      dm.autoLayout(d);
      Geometry.repairAfterLayout(d);
    } else {
      Integer code = LAYOUTS.get(used);
      if (code == null) {
        throw new ToolException(
            "Unknown layout '" + style + "'. Use one of: auto, boundary, " + LAYOUTS.keySet());
      }
      dm.layout(d, code);
      Geometry.repairAfterLayout(d);
    }
    return ok("layout", used);
  }

  private static void registerDiagramOps(ToolRegistry r) {
    List<String> layoutNames = new ArrayList<>();
    layoutNames.add("auto");
    layoutNames.add("boundary");
    layoutNames.add("reroute");
    layoutNames.add("layered");
    layoutNames.addAll(LAYOUTS.keySet());

    r.add(
        "vp_build_diagram",
        "Creates many shapes and connectors in one step (one undo step). Each element:"
            + " {key, type, name, x?, y?, width?, height?, parent?: key|id, properties?,"
            + " children?: [{type, name, properties, children}]}. Each connector: {type, from:"
            + " key|id|name, to: key|id|name, name?, properties?}. Elements are created in order"
            + " (containers before their content). x/y are absolute diagram coordinates, also"
            + " for shapes inside a parent; omit them for automatic placement. An element whose"
            + " name already exists in the same container is reused (VP forbids duplicate"
            + " names), reported with reused=true. Pass 'diagram' to extend an existing diagram,"
            + " or diagramType+name to create a new one. Returns the ids for every key.",
        Schema.object()
            .str("diagram", "Existing diagram id or name", false)
            .str("diagramType", "Type of a new diagram, e.g. UseCaseDiagram", false)
            .str("name", "Name of the new diagram", false)
            .objArray("elements", "Shapes to create", false)
            .objArray("connectors", "Connectors to create", false)
            .enumStr(
                "layout",
                "Automatic layout afterwards (default none)",
                false,
                layoutNames.toArray(new String[0])),
        a ->
            write(
                () -> {
                  IDiagramUIModel d;
                  if (a.has("diagram")) {
                    d = VpModel.diagram(a.str("diagram"));
                  } else {
                    d = VpModel.diagrams().createDiagram(a.str("diagramType"));
                    if (d == null) {
                      throw new ToolException(
                          "Unknown diagram type '" + a.str("diagramType") + "'");
                    }
                    d.setName(a.str("name", a.str("diagramType")));
                    VpModel.diagrams().openDiagram(d);
                  }
                  DiagramBuilder b = new DiagramBuilder(d);
                  List<Object> elements = new ArrayList<>();
                  List<Object> connectors = new ArrayList<>();
                  List<String> errors = new ArrayList<>();
                  int i = 0;
                  for (Object e : a.list("elements")) {
                    try {
                      elements.add(b.addShape(Args.of(e, "element")));
                    } catch (Exception ex) {
                      errors.add("elements[" + i + "]: " + message(ex));
                    }
                    i++;
                  }
                  i = 0;
                  for (Object c : a.list("connectors")) {
                    try {
                      connectors.add(b.addConnector(Args.of(c, "connector")));
                    } catch (Exception ex) {
                      errors.add("connectors[" + i + "]: " + message(ex));
                    }
                    i++;
                  }
                  if (a.has("layout")) {
                    layout(d, a.str("layout"));
                  }
                  b.finish();
                  Map<String, Object> res = VpModel.describeDiagram(d, false);
                  res.put("keys", b.keyMap());
                  res.put("elements", elements);
                  res.put("connectors", connectors);
                  if (!errors.isEmpty()) {
                    res.put("errors", errors);
                  }
                  return res;
                }));

    r.add(
        "vp_build_sequence",
        "Creates a complete sequence diagram: participants left to right and steps top to"
            + " bottom. Activation bars are derived from call/return pairs. Participant: {key,"
            + " name, kind: lifeline|actor, classifier?: class name/id/@key}. Step: a message"
            + " {from, to, name, kind: call|return|async|create|destroy, properties?} (from=to"
            + " for a self call), a combined fragment {fragment: alt|opt|loop|par|break|critical|"
            + "neg|strict|seq|ignore|consider|assert, operands: [{guard, steps}]} (or guard+steps"
            + " for a single operand), or an interaction use {ref: name, covers?: [keys]}.",
        Schema.object()
            .str("name", "Name of the new sequence diagram", true)
            .objArray("participants", "Lifelines and actors, left to right", true)
            .objArray("steps", "Messages, fragments and refs in order", true)
            .bool("activations", "Draw activation bars (default true)", false)
            .bool("sequenceNumbers", "Number the messages 1, 2, 3... (default false)", false)
            .bool(
                "frame", "Draw an 'sd <name>' frame around the interaction (default false)", false),
        a ->
            write(
                () -> {
                  IDiagramUIModel d = VpModel.diagrams().createDiagram("InteractionDiagram");
                  d.setName(a.str("name"));
                  VpModel.diagrams().openDiagram(d);
                  SequenceBuilder sb = new SequenceBuilder(d);
                  return sb.build(
                      a.list("participants"),
                      a.list("steps"),
                      a.bool("activations", true),
                      a.bool("sequenceNumbers", false),
                      a.bool("frame", false));
                }));

    r.add(
        "vp_layout_diagram",
        "Automatically arranges a diagram. 'auto' uses 'boundary' when the diagram has a System"
            + " boundary/package with shapes inside (actors left, contained shapes in a grid);"
            + " class/ER diagrams get 'layered' (inheritance top-down); otherwise VP's automatic"
            + " layout. 'reroute' keeps shapes in place and only redraws"
            + " the connectors.",
        Schema.object()
            .str("diagram", "Diagram id or name", true)
            .enumStr(
                "layout", "Layout style (default auto)", false, layoutNames.toArray(new String[0])),
        a -> write(() -> layout(VpModel.diagram(a.str("diagram")), a.str("layout", null))));

    r.add(
        "vp_set_bounds",
        "Moves and/or resizes a shape (absolute diagram coordinates). Shapes inside it move"
            + " along and attached connectors are re-routed.",
        Schema.object()
            .str("diagram", "Diagram id or name", true)
            .str("shape", "Shape id, model id or name", true)
            .integer("x", "Left", false)
            .integer("y", "Top", false)
            .integer("width", "Width", false)
            .integer("height", "Height", false),
        a ->
            write(
                () -> {
                  IDiagramUIModel d = VpModel.diagram(a.str("diagram"));
                  if (!d.isOpened()) {
                    throw new ToolException(
                        "Open the diagram first (vp_open_diagram): VP does not update connectors"
                            + " of a diagram that is not open");
                  }
                  IShapeUIModel s = VpModel.shape(d, a.str("shape"));
                  Geometry.moveShape(
                      s,
                      a.integer("x", s.getX()),
                      a.integer("y", s.getY()),
                      a.integer("width", s.getWidth()),
                      a.integer("height", s.getHeight()));
                  return VpModel.describeView(s);
                }));

    r.add(
        "vp_open_diagram",
        "Opens (shows) a diagram in the Visual Paradigm window.",
        Schema.object().str("diagram", "Diagram id or name", true),
        a ->
            ToolResult.json(
                Edt.call(
                    () -> {
                      IDiagramUIModel d = VpModel.diagram(a.str("diagram"));
                      VpModel.diagrams().openDiagram(d);
                      return ok("opened", d.getName());
                    })));

    r.add(
        "vp_export_diagram_image",
        "Renders a diagram to an image. Without 'path' (or with returnImage=true) the PNG is"
            + " returned so you can look at the diagram.",
        Schema.object()
            .str("diagram", "Diagram id or name", true)
            .str("path", "Write the image to this file", false)
            .enumStr("format", "Image format (default png)", false, "png", "jpg", "svg", "pdf")
            .bool("returnImage", "Return the PNG in the result (default: true if no path)", false),
        VpTools::exportImage);
  }

  /** Longest side of an image returned inline (keeps tool results within client limits). */
  private static final int MAX_INLINE_IMAGE = 2000;

  private static ToolResult exportImage(Args a) throws Exception {
    String format = a.str("format", "png").toLowerCase(Locale.ROOT);
    boolean hasPath = a.has("path");
    boolean returnImage = a.bool("returnImage", !hasPath);
    if (returnImage && !"png".equals(format) && !"jpg".equals(format)) {
      throw new ToolException("returnImage only works with png or jpg");
    }
    File out = null;
    if (hasPath) {
      out = new File(a.str("path")).getAbsoluteFile();
      File dir = out.getParentFile();
      if (dir != null && !dir.exists() && !dir.mkdirs()) {
        throw new ToolException("Cannot create directory " + dir);
      }
    }
    File inline = returnImage ? File.createTempFile("vp-diagram-", "." + format) : null;
    int type = imageType(format);
    final File outFile = out;
    String name =
        Edt.call(
            () -> {
              IDiagramUIModel d = VpModel.diagram(a.str("diagram"));
              ModelConvertionManager mcm = VpModel.app().getModelConvertionManager();
              if (outFile != null) {
                mcm.exportDiagramAsImage(d, outFile, type);
              }
              if (inline != null) {
                ExportDiagramAsImageOption opt = new ExportDiagramAsImageOption(type);
                opt.setMaxSize(new Dimension(MAX_INLINE_IMAGE, MAX_INLINE_IMAGE));
                mcm.exportDiagramAsImage(d, inline, opt);
              }
              return d.getName();
            });
    try {
      File check = outFile != null ? outFile : inline;
      if (!check.isFile() || check.length() == 0) {
        throw new ToolException("Visual Paradigm did not produce an image (empty diagram?)");
      }
      ToolResult result =
          ToolResult.text(
              "Diagram '" + name + "' exported" + (hasPath ? " to " + outFile.getPath() : ""));
      if (inline != null) {
        byte[] bytes = Files.readAllBytes(inline.toPath());
        result.addImage(
            Base64.getEncoder().encodeToString(bytes),
            "jpg".equals(format) ? "image/jpeg" : "image/png");
      }
      return result;
    } finally {
      if (inline != null && !inline.delete()) {
        inline.deleteOnExit();
      }
    }
  }

  private static int imageType(String format) {
    switch (format) {
      case "jpg":
        return ModelConvertionManager.IMAGE_TYPE_JPG;
      case "svg":
        return ModelConvertionManager.IMAGE_TYPE_SVG;
      case "pdf":
        return ModelConvertionManager.IMAGE_TYPE_PDF;
      default:
        return ModelConvertionManager.IMAGE_TYPE_PNG;
    }
  }

  // ------------------------------------------------------------- use case

  private static void registerUseCase(ToolRegistry r) {
    r.add(
        "vp_cleanup_extension_points",
        "Deletes the extension points VP adds automatically for every Extend (named"
            + " 'ExtensionPoint') and hides the empty 'extension points' compartment. Without"
            + " useCase: whole project. all=true deletes every extension point, also renamed ones.",
        Schema.object()
            .str("useCase", "Only this use case (id, shape id)", false)
            .bool("all", "Delete all extension points, not only 'ExtensionPoint'", false),
        a ->
            write(
                () ->
                    ExtensionPoints.cleanup(
                        a.has("useCase") ? VpModel.model(a.str("useCase")) : null,
                        a.bool("all", false))));

    r.add(
        "vp_set_use_case_details",
        "Fills the use case specification: pre/post conditions, primary/supporting actors and"
            + " flows of events (each flow is a named list of steps). Existing flows with the"
            + " same name are replaced.",
        Schema.object()
            .str("useCase", "Use case id (or shape id)", true)
            .str("preConditions", "Preconditions", false)
            .str("postConditions", "Postconditions", false)
            .strArray("primaryActors", "Actor ids", false)
            .strArray("supportingActors", "Actor ids", false)
            .objArray(
                "flows",
                "e.g. [{\"name\":\"Main flow\",\"steps\":[\"User opens ...\",\"System ...\"]}]",
                false)
            .obj("properties", "Other use case properties (rank, status, justification...)", false),
        a ->
            write(
                () -> {
                  IModelElement m = VpModel.model(a.str("useCase"));
                  if (!(m instanceof IUseCase)) {
                    throw new ToolException("'" + m.getName() + "' is not a UseCase");
                  }
                  IUseCase uc = (IUseCase) m;
                  if (a.has("preConditions")) {
                    uc.setPreConditions(a.str("preConditions"));
                  }
                  if (a.has("postConditions")) {
                    uc.setPostConditions(a.str("postConditions"));
                  }
                  for (Object id : a.list("primaryActors")) {
                    uc.addPrimaryActor(actor(String.valueOf(id)));
                  }
                  for (Object id : a.list("supportingActors")) {
                    uc.addSupportingActor(actor(String.valueOf(id)));
                  }
                  VpModel.apply(uc, a.map("properties"));
                  List<Object> flows = new ArrayList<>();
                  for (Object f : a.list("flows")) {
                    Args flow = Args.of(f, "flow");
                    String flowName = flow.str("name", "Main flow");
                    IStepContainer[] existing = uc.toStepContainerArray();
                    if (existing != null) {
                      for (IStepContainer old : existing) {
                        if (flowName.equals(old.getName())) {
                          uc.removeStepContainer(old);
                        }
                      }
                    }
                    IStepContainer sc = VpModel.factory().createStepContainer();
                    sc.setName(flowName);
                    uc.addStepContainer(sc);
                    int n = 0;
                    for (Object s : flow.list("steps")) {
                      IStep step = VpModel.factory().createStep();
                      step.setName(String.valueOf(s));
                      sc.addStep(step);
                      n++;
                    }
                    Map<String, Object> fr = VpModel.brief(sc);
                    fr.put("steps", n);
                    flows.add(fr);
                  }
                  Map<String, Object> res = VpModel.describeModel(uc, false);
                  res.put("flows", flows);
                  return res;
                }));
  }

  private static IActor actor(String id) {
    IModelElement m = VpModel.model(id);
    if (!(m instanceof IActor)) {
      throw new ToolException("'" + m.getName() + "' is not an Actor");
    }
    return (IActor) m;
  }

  private static String message(Exception e) {
    if (e instanceof ToolException) {
      return e.getMessage();
    }
    return e.toString();
  }
}
