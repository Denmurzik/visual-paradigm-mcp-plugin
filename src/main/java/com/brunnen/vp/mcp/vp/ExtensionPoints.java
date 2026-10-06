package com.brunnen.vp.mcp.vp;

import com.vp.plugin.diagram.IDiagramElement;
import com.vp.plugin.diagram.IShapeUIModel;
import com.vp.plugin.diagram.shape.IUseCaseUIModel;
import com.vp.plugin.model.IExtend;
import com.vp.plugin.model.IExtensionPoint;
import com.vp.plugin.model.IModelElement;
import com.vp.plugin.model.property.IModelProperty;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extension points of use cases. VP creates one named "ExtensionPoint" in the base use case for
 * every Extend; they are not reachable through the project's element iterators, so they are
 * collected from the use case (children / properties) and from the Extend relationships.
 */
final class ExtensionPoints {

  static final String DEFAULT_NAME = "ExtensionPoint";

  private ExtensionPoints() {}

  /** Extension points owned by a use case. */
  static List<IExtensionPoint> of(IModelElement useCase) {
    Map<String, IExtensionPoint> found = new LinkedHashMap<>();
    IModelElement[] kids = useCase.toChildArray();
    if (kids != null) {
      for (IModelElement k : kids) {
        if (k instanceof IExtensionPoint) {
          found.put(k.getId(), (IExtensionPoint) k);
        }
      }
    }
    IModelProperty[] props = useCase.toModelPropertyArray();
    if (props != null) {
      for (IModelProperty p : props) {
        if (p.getType() != IModelProperty.TYPE_MODEL_COLLECTION
            && p.getType() != IModelProperty.TYPE_COMPOSITE_MODEL_COLLECTION) {
          continue;
        }
        try {
          IModelElement[] items = p.getValueAsModelCollection();
          if (items != null) {
            for (IModelElement i : items) {
              if (i instanceof IExtensionPoint) {
                found.put(i.getId(), (IExtensionPoint) i);
              }
            }
          }
        } catch (RuntimeException e) {
          // unreadable property
        }
      }
    }
    // extension points are not registered in the project; they hang off the Extends whose
    // VP-internal "from" end is the base use case
    for (IExtend ext : allExtends()) {
      IExtensionPoint ep = ext.getExtensionPoint();
      if (ep != null && useCase.getId().equals(baseOf(ext))) {
        found.put(ep.getId(), ep);
      }
    }
    return new ArrayList<>(found.values());
  }

  /** Id of the base use case of an Extend (VP stores it as the "from" end). */
  static String baseOf(IExtend ext) {
    IModelElement base = ext.getFrom();
    return base == null ? null : base.getId();
  }

  static List<IExtend> allExtends() {
    List<IExtend> out = new ArrayList<>();
    Iterator<?> it = VpModel.project().allLevelModelElementIterator("Extend");
    while (it.hasNext()) {
      Object o = it.next();
      if (o instanceof IExtend) {
        out.add((IExtend) o);
      }
    }
    return out;
  }

  /**
   * Deletes extension points (only those named "ExtensionPoint" unless {@code all}) of one use case
   * or of every use case, unlinking them from their Extends.
   *
   * @return number of deleted extension points per use case name
   */
  static Map<String, Object> cleanup(IModelElement onlyUseCase, boolean all) {
    List<IModelElement> useCases = new ArrayList<>();
    if (onlyUseCase != null) {
      useCases.add(onlyUseCase);
    } else {
      Iterator<?> it = VpModel.project().allLevelModelElementIterator("UseCase");
      while (it.hasNext()) {
        useCases.add((IModelElement) it.next());
      }
    }
    List<IExtend> extendsList = allExtends();
    Map<String, Object> result = new LinkedHashMap<>();
    int total = 0;
    for (IModelElement uc : useCases) {
      int n = 0;
      for (IExtensionPoint ep : of(uc)) {
        if (!all && !DEFAULT_NAME.equals(ep.getName())) {
          continue;
        }
        for (IExtend ext : extendsList) {
          IExtensionPoint linked = ext.getExtensionPoint();
          if (linked != null && linked.getId().equals(ep.getId())) {
            ext.setExtensionPoint(null);
          }
        }
        try {
          ep.delete();
        } catch (RuntimeException e) {
          // not a registered element; unlinking it from its Extends removes it
        }
        n++;
      }
      if (n > 0) {
        refreshViews(uc);
        result.put(uc.getName() + " (" + uc.getId() + ")", n);
        total += n;
      }
    }
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("deleted", total);
    r.put("useCases", result);
    return r;
  }

  /** Shows the extension point compartment only when there is something in it; refits shapes. */
  static void refreshViews(IModelElement useCase) {
    boolean any = !of(useCase).isEmpty();
    IDiagramElement[] views = useCase.getDiagramElements();
    if (views == null) {
      return;
    }
    for (IDiagramElement v : views) {
      if (v instanceof IUseCaseUIModel) {
        ((IUseCaseUIModel) v).setShowExtensionPoint(any);
      }
      if (v instanceof IShapeUIModel) {
        int cx = v.getX() + v.getWidth() / 2;
        int cy = v.getY() + v.getHeight() / 2;
        try {
          ((IShapeUIModel) v).fitSize();
        } catch (RuntimeException e) {
          // cosmetic
        }
        // keep the shape centred where it was so connectors still meet it
        Geometry.moveShape(
            (IShapeUIModel) v,
            cx - v.getWidth() / 2,
            cy - v.getHeight() / 2,
            v.getWidth(),
            v.getHeight());
      }
    }
  }

  /** Names of a use case's extension points, for vp_get_element. */
  static List<Object> describe(IModelElement useCase) {
    List<Object> out = new ArrayList<>();
    for (IExtensionPoint ep : of(useCase)) {
      out.add(VpModel.brief(ep));
    }
    return out;
  }
}
