package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.Args;
import com.brunnen.vp.mcp.protocol.ToolException;
import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IDiagramUIModel;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.model.IActivation;
import com.vp.plugin.model.ICombinedFragment;
import com.vp.plugin.model.IInteractionConstraint;
import com.vp.plugin.model.IInteractionOccurrence;
import com.vp.plugin.model.IInteractionOperand;
import com.vp.plugin.model.IMessage;
import com.vp.plugin.model.IModelElement;
import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a complete sequence diagram from participants and an ordered list of steps (messages,
 * combined fragments, interaction uses). Geometry is planned first - message rows, activation bars
 * from call/return pairs, fragment and operand bounds - and then created in Visual Paradigm, so
 * messages attach to activation bars as in a hand-drawn diagram.
 */
final class SequenceBuilder {

  private static final int LEFT = 60;
  private static final int TOP = 40;
  private static final int HEAD = 40;
  private static final int ROW = 40;
  private static final int ACT_W = 10;
  private static final int SELF_W = 40;

  /** A lifeline or actor. */
  private static final class Part {
    final String key;
    boolean actor;
    IShapeUIModel shape;
    IModelElement model;
    int cx;
    int headY = TOP;
    Integer destroyedAt;
    final Deque<Act> open = new ArrayDeque<>();
    final List<Act> acts = new ArrayList<>();

    Part(String key) {
      this.key = key;
    }
  }

  /** An activation bar (execution specification). */
  private static final class Act {
    final Part part;
    final int start;
    int end;
    final int depth;
    IShapeUIModel shape;

    Act(Part part, int start, int depth) {
      this.part = part;
      this.start = start;
      this.end = start + 30;
      this.depth = depth;
    }
  }

  private static final class Msg {
    Part from;
    Part to;
    String kind;
    Args spec;
    int rowY;
    Act fromAct;
    Act toAct;
    IDiagramElement view;
    IMessage model;
    Operand operand;
  }

  private static final class Operand {
    String guard;
    int y0;
    int y1;
    final List<Msg> messages = new ArrayList<>();
  }

  private static final class Frag {
    String operator;
    int y0;
    int y1;
    int depth;
    final List<Operand> operands = new ArrayList<>();
    final Set<Part> covers = new LinkedHashSet<>();
    IShapeUIModel shape;
  }

  private static final class Ref {
    String name;
    int y0;
    int y1;
    final Set<Part> covers = new LinkedHashSet<>();
  }

  private final IDiagramUIModel diagram;
  private final DiagramBuilder shapes;
  private final Map<String, Part> parts = new LinkedHashMap<>();
  private final List<Msg> messages = new ArrayList<>();
  private final List<Frag> frags = new ArrayList<>();
  private final List<Ref> refs = new ArrayList<>();
  private final List<String> notes = new ArrayList<>();
  private final Deque<Frag> fragStack = new ArrayDeque<>();
  private Operand currentOperand;
  private boolean frame;
  private int cursor;

  SequenceBuilder(IDiagramUIModel diagram) {
    this.diagram = diagram;
    this.shapes = new DiagramBuilder(diagram);
  }

  DiagramBuilder shapes() {
    return shapes;
  }

  Map<String, Object> build(
      List<Object> participants,
      List<Object> steps,
      boolean activations,
      boolean sequenceNumbers,
      boolean frame)
      throws Exception {
    if (diagram instanceof com.vp.plugin.diagram.IInteractionDiagramUIModel) {
      com.vp.plugin.diagram.IInteractionDiagramUIModel id =
          (com.vp.plugin.diagram.IInteractionDiagramUIModel) diagram;
      id.setShowSequenceNumbers(sequenceNumbers);
    }
    this.frame = frame;
    createParticipants(participants);
    cursor = TOP + HEAD + 30;
    walk(steps);
    cursor += 20;
    for (Part p : parts.values()) {
      while (!p.open.isEmpty()) {
        p.open.pop().end = cursor; // activations still open end with the interaction
      }
    }
    int bottom = cursor + 40;
    if (activations) {
      createActivations();
    }
    createMessages();
    createFragments();
    createRefs();
    for (Part p : parts.values()) {
      int end = p.destroyedAt != null ? p.destroyedAt : bottom;
      p.shape.setHeight(Math.max(HEAD + 20, end - p.shape.getY()));
      if (p.destroyedAt != null) {
        try {
          Reflect.trySet(p.model, "stopped", Boolean.TRUE, null);
        } catch (Exception e) {
          notes.add("could not mark " + p.key + " as destroyed: " + e);
        }
      }
    }
    if (frame) {
      createFrame(bottom);
    }
    shapes.finish();
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("diagram", VpModel.describeDiagram(diagram, false));
    Map<String, Object> keys = new LinkedHashMap<>();
    for (Part p : parts.values()) {
      keys.put(p.key, p.shape.getId());
    }
    r.put("participants", keys);
    r.put("messages", messages.size());
    int acts = 0;
    for (Part p : parts.values()) {
      acts += p.acts.size();
    }
    r.put("activations", activations ? acts : 0);
    r.put("fragments", frags.size());
    r.put("refs", refs.size());
    if (!notes.isEmpty()) {
      r.put("notes", notes);
    }
    return r;
  }

  // ------------------------------------------------------------ participants

  private void createParticipants(List<Object> list) throws Exception {
    if (list.isEmpty()) {
      throw new ToolException("At least one participant is required");
    }
    int x = LEFT;
    for (Object o : list) {
      Args a = Args.of(o, "participant");
      String key = a.str("key");
      if (parts.containsKey(key)) {
        throw new ToolException("Duplicate participant key '" + key + "'");
      }
      String kind = a.str("kind", "lifeline").toLowerCase(Locale.ROOT);
      Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("key", key);
      spec.put("type", "actor".equals(kind) ? "InteractionActor" : "InteractionLifeLine");
      spec.put("name", a.str("name", key));
      spec.put("x", x);
      spec.put("y", TOP);
      Map<String, Object> props = new LinkedHashMap<>(a.map("properties"));
      if (a.has("classifier")) {
        props.put("baseClassifier", a.str("classifier"));
      }
      spec.put("properties", props);
      if (a.has("view")) {
        spec.put("view", a.map("view"));
      }
      shapes.addShape(new Args(spec));
      Part p = new Part(key);
      p.actor = "actor".equals(kind);
      p.shape = shapes.resolveShape(key);
      p.model = p.shape.getModelElement();
      p.cx = p.shape.getX() + p.shape.getWidth() / 2;
      parts.put(key, p);
      int width = Math.max(p.shape.getWidth(), 7 * a.str("name", key).length() + 20);
      x += Math.max(170, width + 70);
    }
  }

  private Part part(String key) {
    Part p = parts.get(key);
    if (p == null) {
      throw new ToolException("Unknown participant '" + key + "' (use a participant key)");
    }
    return p;
  }

  // ------------------------------------------------------------------ plan

  private void walk(List<Object> steps) {
    for (Object o : steps) {
      Args s = Args.of(o, "step");
      if (s.has("fragment")) {
        planFragment(s);
      } else if (s.has("ref")) {
        planRef(s);
      } else {
        planMessage(s);
      }
    }
  }

  private void planMessage(Args s) {
    Msg m = new Msg();
    m.spec = s;
    m.from = part(s.str("from"));
    m.to = part(s.str("to", s.str("from")));
    m.kind = s.str("kind", "call").toLowerCase(Locale.ROOT);
    boolean self = m.from == m.to;
    cursor += ROW;
    m.rowY = cursor;
    m.fromAct = m.from.open.peek();
    switch (m.kind) {
      case "return":
      case "reply":
        if (!m.from.open.isEmpty()) {
          Act a = m.from.open.pop();
          a.end = cursor;
          m.fromAct = a;
        }
        m.toAct = m.to.open.peek();
        break;
      case "create":
        ensureCaller(m);
        m.to.headY = cursor - HEAD / 2;
        m.toAct = shortAct(m.to, cursor + HEAD / 2, 20);
        cursor += HEAD / 2;
        break;
      case "destroy":
        m.toAct = m.to.open.peek();
        m.to.destroyedAt = cursor;
        while (!m.to.open.isEmpty()) {
          m.to.open.pop().end = cursor;
        }
        break;
      case "async":
      case "send":
        ensureCaller(m);
        if (m.to.actor) {
          m.toAct = null;
        } else if (m.to.open.isEmpty()) {
          m.toAct = shortAct(m.to, cursor, 20);
        } else {
          m.toAct = m.to.open.peek();
        }
        break;
      default: // call
        ensureCaller(m);
        if (self) {
          m.toAct = shortAct(m.to, cursor + 20, 25);
          cursor += 25;
        } else if (m.to.actor) {
          m.toAct = null;
        } else {
          m.toAct = openAct(m.to, cursor);
        }
    }
    if (currentOperand != null) {
      m.operand = currentOperand;
      currentOperand.messages.add(m);
      for (Frag f : fragStack) {
        f.covers.add(m.from);
        f.covers.add(m.to);
      }
    }
    messages.add(m);
  }

  /** A synchronous call starts from an active caller; open an activation if it has none. */
  private void ensureCaller(Msg m) {
    if (m.from.actor) {
      m.fromAct = null; // actors are drawn without activation bars
      return;
    }
    if (m.from.open.isEmpty()) {
      m.fromAct = openAct(m.from, m.rowY - 10);
    } else {
      m.fromAct = m.from.open.peek();
    }
  }

  /** An activation that ends by itself (self call, creation), not closed by a return. */
  private Act shortAct(Part p, int start, int length) {
    Act a = new Act(p, start, p.open.size());
    a.end = start + length;
    p.acts.add(a);
    return a;
  }

  private Act openAct(Part p, int start) {
    Act a = new Act(p, start, p.open.size());
    p.open.push(a);
    p.acts.add(a);
    return a;
  }

  private void planFragment(Args s) {
    Frag f = new Frag();
    f.operator = s.str("fragment").toLowerCase(Locale.ROOT);
    f.depth = fragStack.size();
    for (Object k : s.list("covers")) {
      f.covers.add(part(String.valueOf(k)));
    }
    cursor += 25;
    f.y0 = cursor - 10;
    fragStack.push(f);
    Operand outer = currentOperand;
    List<Object> ops = s.list("operands");
    if (ops.isEmpty()) {
      Map<String, Object> single = new LinkedHashMap<>();
      single.put("guard", s.str("guard", null));
      single.put("steps", s.list("steps"));
      ops = new ArrayList<>();
      ops.add(single);
    }
    for (Object o : ops) {
      Args op = Args.of(o, "operand");
      Operand operand = new Operand();
      operand.guard = op.str("guard", null);
      operand.y0 = cursor;
      cursor += 20; // guard text
      currentOperand = operand;
      walk(op.list("steps"));
      cursor += 25;
      operand.y1 = cursor;
      f.operands.add(operand);
    }
    currentOperand = outer;
    fragStack.pop();
    f.y1 = cursor + 5;
    cursor += 15;
    if (f.covers.isEmpty()) {
      f.covers.addAll(parts.values());
    }
    for (Frag outerFrag : fragStack) {
      outerFrag.covers.addAll(f.covers);
    }
    frags.add(f);
  }

  private void planRef(Args s) {
    Ref r = new Ref();
    r.name = s.str("ref");
    List<Object> covers = s.list("covers");
    if (covers.isEmpty()) {
      r.covers.addAll(parts.values());
    } else {
      for (Object k : covers) {
        r.covers.add(part(String.valueOf(k)));
      }
    }
    cursor += 25;
    r.y0 = cursor;
    cursor += 40;
    r.y1 = cursor;
    refs.add(r);
  }

  // ---------------------------------------------------------------- create

  private void createActivations() {
    for (Part p : parts.values()) {
      for (Act a : p.acts) {
        IActivation model = VpModel.factory().createActivation();
        List<java.lang.reflect.Method> add =
            Reflect.methods(p.model.getClass(), "addActivation", 1);
        if (!add.isEmpty()) { // lifelines own their activations; actors have none in the model
          try {
            Reflect.invoke(add.get(0), p.model, model);
          } catch (Exception e) {
            notes.add("activation not added to " + p.key + ": " + e);
          }
        }
        IDiagramElement de = VpModel.diagrams().createDiagramElement(diagram, model);
        if (!(de instanceof IShapeUIModel)) {
          notes.add("VP refused an activation on " + p.key);
          continue;
        }
        a.shape = (IShapeUIModel) de;
        p.shape.addChild(a.shape);
        int x = p.cx - ACT_W / 2 + a.depth * (ACT_W / 2);
        a.shape.setBounds(x, a.start, ACT_W, Math.max(10, a.end - a.start));
        shapes.track(a.shape);
      }
    }
  }

  private void createMessages() throws Exception {
    int seq = 0;
    for (Msg m : messages) {
      IMessage msg = (IMessage) VpModel.create("Message");
      msg.setFrom(m.from.model);
      msg.setTo(m.to.model);
      if (m.spec.has("name")) {
        msg.setName(m.spec.str("name"));
      }
      String kind = "reply".equals(m.kind) ? "return" : "async".equals(m.kind) ? "send" : m.kind;
      if (!"self".equals(kind)) {
        VpModel.setMessageKind(msg, "self".equals(kind) ? "call" : kind);
      }
      if ("send".equals(kind)) {
        msg.setAsynchronous(true);
      }
      // UML numbers the calls; replies carry no sequence number
      msg.setSequenceNumber("return".equals(kind) ? "" : String.valueOf(++seq));
      VpModel.apply(msg, m.spec.map("properties"));
      if (m.fromAct != null && m.fromAct.shape != null) {
        msg.setFromActivation((IActivation) m.fromAct.shape.getModelElement());
      }
      if (m.toAct != null && m.toAct.shape != null) {
        msg.setToActivation((IActivation) m.toAct.shape.getModelElement());
      }
      // the sending end is attached to the lifeline: VP stores a source end on an activation
      // relative to it when the project is reopened, which breaks the line in the editor
      IDiagramElement fromView = m.from.shape;
      IDiagramElement toView =
          m.toAct != null && m.toAct.shape != null ? m.toAct.shape : m.to.shape;
      if ("create".equals(kind)) {
        toView = m.to.shape; // a create message points at the new object's head
        m.to.shape.setY(m.to.headY);
      }
      Point[] pts = points(m);
      IDiagramElement view =
          VpModel.diagrams().createConnector(diagram, msg, fromView, toView, pts);
      if (view == null) {
        throw new ToolException(
            "Visual Paradigm refused message '"
                + m.spec.str("name", "")
                + "' from "
                + m.from.key
                + " to "
                + m.to.key);
      }
      m.view = view;
      m.model = msg;
      shapes.track(view);
      if (m.spec.has("key")) {
        shapes.register(m.spec.str("key"), view, msg);
      }
      DiagramBuilder.resetCaption(view);
    }
  }

  private Point[] points(Msg m) {
    int fx = m.from.cx;
    int tx = m.to.cx;
    if (m.from == m.to) {
      int edge = fx + ACT_W / 2 + (m.fromAct == null ? 0 : m.fromAct.depth * ACT_W / 2);
      return new Point[] {
        new Point(edge, m.rowY),
        new Point(edge + SELF_W, m.rowY),
        new Point(edge + SELF_W, m.rowY + 20),
        new Point(edge + ACT_W / 2, m.rowY + 20)
      };
    }
    int dir = tx > fx ? 1 : -1;
    int fromEdge = fx + dir * ACT_W / 2;
    int toEdge = tx - dir * ACT_W / 2;
    if ("create".equals(m.kind)) {
      IShapeUIModel head = m.to.shape;
      toEdge = dir > 0 ? head.getX() : head.getX() + head.getWidth();
    }
    return new Point[] {new Point(fromEdge, m.rowY), new Point(toEdge, m.rowY)};
  }

  private void createFragments() {
    for (Frag f : frags) {
      ICombinedFragment cf = VpModel.factory().createCombinedFragment();
      IDiagramElement de = null;
      cf.setInteractionOperator(f.operator);
      for (Part p : f.covers) {
        cf.addCoveredLifeLine(p.model);
      }
      IInteractionOperand[] existing = cf.toOperandArray();
      List<IInteractionOperand> operands = new ArrayList<>();
      for (int i = 0; i < f.operands.size(); i++) {
        Operand o = f.operands.get(i);
        IInteractionOperand io;
        if (existing != null && i < existing.length) {
          io = existing[i];
        } else {
          io = cf.createInteractionOperand();
          cf.addOperand(io);
        }
        if (o.guard != null && !o.guard.isEmpty()) {
          IInteractionConstraint c = VpModel.factory().createInteractionConstraint();
          c.setConstraint(o.guard);
          io.setGuard(c);
        }
        for (Msg m : o.messages) {
          if (m.model != null) {
            io.addMessage(m.model);
          }
        }
        operands.add(io);
      }
      if (de == null) {
        de = VpModel.diagrams().createDiagramElement(diagram, cf);
      }
      if (!(de instanceof IShapeUIModel)) {
        notes.add("VP refused a combined fragment " + f.operator);
        continue;
      }
      f.shape = (IShapeUIModel) de;
      int minX = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      for (Part p : f.covers) {
        minX = Math.min(minX, p.cx);
        maxX = Math.max(maxX, p.cx);
      }
      int pad = 60 - f.depth * 12;
      int x0 = minX - pad;
      int x1 = maxX + pad + SELF_W;
      f.shape.setBounds(x0, f.y0, x1 - x0, f.y1 - f.y0);
      f.shape.sendToBack();
      // VP may create operand shapes itself; otherwise create them inside the fragment
      IShapeUIModel[] kids = f.shape.toChildArray();
      int existingShapes = kids == null ? 0 : kids.length;
      for (int i = 0; i < operands.size(); i++) {
        Operand o = f.operands.get(i);
        IShapeUIModel opShape = null;
        if (i < existingShapes) {
          opShape = kids[i];
        } else {
          IDiagramElement od = VpModel.diagrams().createDiagramElement(diagram, operands.get(i));
          if (od instanceof IShapeUIModel) {
            opShape = (IShapeUIModel) od;
            f.shape.addChild(opShape);
          }
        }
        if (opShape != null) {
          opShape.setBounds(x0, o.y0, x1 - x0, o.y1 - o.y0);
          DiagramBuilder.resetCaption(opShape);
          shapes.track(opShape);
        } else {
          notes.add("no shape for operand " + i + " of " + f.operator);
        }
      }
      DiagramBuilder.resetCaption(f.shape);
      shapes.track(f.shape);
    }
  }

  /** The "sd name" frame around the whole interaction. */
  private void createFrame(int bottom) {
    int right = 0;
    for (Part p : parts.values()) {
      right = Math.max(right, p.shape.getX() + p.shape.getWidth());
    }
    for (Frag f : frags) {
      if (f.shape != null) {
        right = Math.max(right, f.shape.getX() + f.shape.getWidth());
      }
    }
    com.vp.plugin.model.IFrame model = VpModel.factory().createFrame();
    model.setName(diagram.getName());
    IDiagramElement de = VpModel.diagrams().createDiagramElement(diagram, model);
    if (!(de instanceof IShapeUIModel)) {
      notes.add("VP refused the diagram frame");
      return;
    }
    de.setBounds(LEFT - 50, TOP - 30, right - LEFT + 90, bottom - TOP + 50);
    ((IShapeUIModel) de).sendToBack();
    DiagramBuilder.resetCaption(de);
    shapes.track(de);
  }

  private void createRefs() {
    for (Ref r : refs) {
      IInteractionOccurrence io = VpModel.factory().createInteractionOccurrence();
      io.setName(r.name);
      try {
        // the "ref" box shows the name of the interaction it refers to
        com.vp.plugin.model.IFrame frame = VpModel.factory().createFrame();
        frame.setName(r.name);
        io.setRefersTo(frame);
      } catch (RuntimeException e) {
        notes.add("ref '" + r.name + "' has no referred frame: " + e);
      }
      for (Part p : r.covers) {
        io.addCoveredLifeLine(p.model);
      }
      IDiagramElement de = VpModel.diagrams().createDiagramElement(diagram, io);
      if (!(de instanceof IShapeUIModel)) {
        notes.add("VP refused the interaction use " + r.name);
        continue;
      }
      int minX = Integer.MAX_VALUE;
      int maxX = Integer.MIN_VALUE;
      for (Part p : r.covers) {
        minX = Math.min(minX, p.cx);
        maxX = Math.max(maxX, p.cx);
      }
      de.setBounds(minX - 50, r.y0, maxX - minX + 100, r.y1 - r.y0);
      DiagramBuilder.resetCaption(de);
      shapes.track(de);
    }
  }
}
