package com.brunnen.vp.mcp.vp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReflectTest {

  /** Public API, like VP's model interfaces. */
  public interface Thing {
    int TYPE_CALL = 7;
    int TYPE_RETURN = 9;

    void setName(String name);

    void setAbstract(boolean value);

    void setLength(int length);

    void setType(int type);

    void setType(String type);

    void setOwner(Thing owner);

    void setTags(String[] tags);

    void setLabel(String label);

    void setLabel(Thing label);

    Thing createChild();
  }

  /** Non-public implementation, like VP's model classes. */
  static final class ThingImpl implements Thing {
    String name;
    boolean abstractFlag;
    int length;
    Object type;
    Thing owner;
    String[] tags;
    Object label;

    @Override
    public void setName(String name) {
      this.name = name;
    }

    @Override
    public void setAbstract(boolean value) {
      this.abstractFlag = value;
    }

    @Override
    public void setLength(int length) {
      this.length = length;
    }

    @Override
    public void setType(int type) {
      this.type = type;
    }

    @Override
    public void setType(String type) {
      this.type = type;
    }

    @Override
    public void setOwner(Thing owner) {
      this.owner = owner;
    }

    @Override
    public void setTags(String[] tags) {
      this.tags = tags;
    }

    @Override
    public void setLabel(String label) {
      this.label = label;
    }

    @Override
    public void setLabel(Thing label) {
      this.label = label;
    }

    @Override
    public Thing createChild() {
      return new ThingImpl();
    }
  }

  @Test
  void setsSimpleValues() throws Exception {
    ThingImpl t = new ThingImpl();
    assertTrue(Reflect.trySet(t, "name", "Order", null));
    assertTrue(Reflect.trySet(t, "abstract", true, null));
    assertTrue(Reflect.trySet(t, "length", 255L, null));
    assertTrue(Reflect.trySet(t, "tags", Arrays.asList("a", "b"), null));
    assertEquals("Order", t.name);
    assertTrue(t.abstractFlag);
    assertEquals(255, t.length);
    assertArrayEquals(new String[] {"a", "b"}, t.tags);
  }

  @Test
  void prefersMatchingOverload() throws Exception {
    ThingImpl t = new ThingImpl();
    Reflect.trySet(t, "type", "varchar", null);
    assertEquals("varchar", t.type);
    Reflect.trySet(t, "type", 3L, null);
    assertEquals(3, t.type);
  }

  @Test
  void looseConversionsAndConstants() throws Exception {
    ThingImpl t = new ThingImpl();
    Reflect.trySet(t, "length", "42", null);
    assertEquals(42, t.length);
    Reflect.trySet(t, "abstract", "true", null);
    assertTrue(t.abstractFlag);
    Reflect.trySet(t, "name", 12L, null);
    assertEquals("12", t.name);
    assertEquals(
        Integer.valueOf(7),
        Reflect.intConstant(
            ThingImpl.class, Thing.class.getMethod("setType", int.class), "TYPE_CALL"));
    assertEquals(
        Integer.valueOf(9),
        Reflect.intConstant(
            ThingImpl.class, Thing.class.getMethod("setType", int.class), "return"));
  }

  @Test
  void resolvesReferences() throws Exception {
    ThingImpl owner = new ThingImpl();
    ThingImpl t = new ThingImpl();
    assertTrue(Reflect.trySet(t, "owner", "id-1", id -> "id-1".equals(id) ? owner : null));
    assertSame(owner, t.owner);
  }

  @Test
  void reportsMissingOrUnconvertible() throws Exception {
    ThingImpl t = new ThingImpl();
    assertFalse(Reflect.trySet(t, "colour", "red", null));
    assertThrows(
        IllegalArgumentException.class, () -> Reflect.trySet(t, "abstract", "maybe", null));
  }

  @Test
  void invokesNoArgOnPublicInterface() throws Exception {
    Object child = Reflect.invokeNoArg(new ThingImpl(), "createChild");
    assertTrue(child instanceof Thing);
    assertSame(Reflect.NOT_FOUND, Reflect.invokeNoArg(new ThingImpl(), "createNothing"));
  }

  @Test
  void resolvedReferenceBeatsStringOverload() throws Exception {
    ThingImpl other = new ThingImpl();
    ThingImpl t = new ThingImpl();
    Reflect.trySet(t, "label", "Color", name -> "Color".equals(name) ? other : null);
    assertSame(other, t.label);
    Reflect.trySet(t, "label", "plain", name -> null);
    assertEquals("plain", t.label);
  }
}
