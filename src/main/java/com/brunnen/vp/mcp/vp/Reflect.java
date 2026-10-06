package com.brunnen.vp.mcp.vp;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Reflection helpers to call {@code setXxx}/{@code createXxx} methods of Visual Paradigm model
 * objects with loosely typed JSON values.
 *
 * <p>VP's implementation classes are not public, so methods are always looked up on the public
 * interfaces/superclasses they implement. This class has no VP dependency and is unit-tested.
 */
final class Reflect {

  private Reflect() {}

  /** All public types (interfaces and classes) that {@code c} is assignable to. */
  static List<Class<?>> publicTypes(Class<?> c) {
    Set<Class<?>> seen = new LinkedHashSet<>();
    Deque<Class<?>> todo = new ArrayDeque<>();
    todo.add(c);
    while (!todo.isEmpty()) {
      Class<?> k = todo.poll();
      if (k == null || !seen.add(k)) {
        continue;
      }
      if (k.getSuperclass() != null) {
        todo.add(k.getSuperclass());
      }
      for (Class<?> i : k.getInterfaces()) {
        todo.add(i);
      }
    }
    List<Class<?>> out = new ArrayList<>();
    for (Class<?> k : seen) {
      if (Modifier.isPublic(k.getModifiers())) {
        out.add(k);
      }
    }
    return out;
  }

  /** Public methods with the given name and parameter count, declared on public types. */
  static List<Method> methods(Class<?> c, String name, int paramCount) {
    List<Method> out = new ArrayList<>();
    Set<String> signatures = new LinkedHashSet<>();
    for (Class<?> t : publicTypes(c)) {
      for (Method m : t.getDeclaredMethods()) {
        if (m.getName().equals(name)
            && m.getParameterCount() == paramCount
            && Modifier.isPublic(m.getModifiers())
            && !Modifier.isStatic(m.getModifiers())) {
          String sig = m.getName() + java.util.Arrays.toString(m.getParameterTypes());
          if (signatures.add(sig)) {
            out.add(m);
          }
        }
      }
    }
    return out;
  }

  /** Invokes a public no-arg method if present; returns {@code NOT_FOUND} otherwise. */
  static Object invokeNoArg(Object target, String name) throws Exception {
    List<Method> ms = methods(target.getClass(), name, 0);
    if (ms.isEmpty()) {
      return NOT_FOUND;
    }
    return invoke(ms.get(0), target);
  }

  static final Object NOT_FOUND = new Object();

  /**
   * Calls {@code set<Key>(value)} converting the JSON value to the parameter type.
   *
   * @param resolver maps a string (e.g. an element id) to an object reference, or {@code null}
   * @return {@code true} if a matching setter was found and invoked
   */
  static boolean trySet(Object target, String key, Object value, Function<String, Object> resolver)
      throws Exception {
    if (key == null || key.isEmpty()) {
      return false;
    }
    String setter = "set" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
    List<Method> candidates = methods(target.getClass(), setter, 1);
    if (candidates.isEmpty()) {
      return false;
    }
    Object resolved = null;
    if (value instanceof String && resolver != null) {
      resolved = resolver.apply((String) value);
    }
    // 1st pass: exact-ish matches, 2nd pass: conversions
    for (int pass = 0; pass < 2; pass++) {
      for (Method m : candidates) {
        Class<?> p = m.getParameterTypes()[0];
        Object arg = convert(value, resolved, p, m, target, pass == 1);
        if (arg != NO_MATCH) {
          invoke(m, target, arg);
          return true;
        }
      }
    }
    StringBuilder sigs = new StringBuilder();
    for (Method m : candidates) {
      sigs.append(' ').append(m.getParameterTypes()[0].getSimpleName());
    }
    throw new IllegalArgumentException(
        "Cannot convert value '" + value + "' for " + setter + "(" + sigs.toString().trim() + ")");
  }

  private static final Object NO_MATCH = new Object();

  private static Object convert(
      Object value, Object resolved, Class<?> p, Method m, Object target, boolean loose) {
    if (value == null) {
      return p.isPrimitive() ? NO_MATCH : null;
    }
    if (!loose) {
      if (resolved != null && !p.isPrimitive() && p.isInstance(resolved)) {
        return resolved;
      }
      if (value instanceof Boolean && (p == boolean.class || p == Boolean.class)) {
        return value;
      }
      if (value instanceof Number) {
        Number n = (Number) value;
        if (p == int.class || p == Integer.class) {
          return n.intValue();
        }
        if (p == long.class || p == Long.class) {
          return n.longValue();
        }
        if (p == double.class || p == Double.class) {
          return n.doubleValue();
        }
        if (p == float.class || p == Float.class) {
          return n.floatValue();
        }
      }
      if (value instanceof String && p == String.class) {
        return value;
      }
      if (value instanceof List && p == String[].class) {
        List<?> l = (List<?>) value;
        String[] arr = new String[l.size()];
        for (int i = 0; i < arr.length; i++) {
          arr[i] = String.valueOf(l.get(i));
        }
        return arr;
      }
      return NO_MATCH;
    }
    // loose conversions
    String s = String.valueOf(value);
    if (p == String.class) {
      return s;
    }
    if (p == boolean.class || p == Boolean.class) {
      if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
        return Boolean.parseBoolean(s);
      }
      return NO_MATCH;
    }
    if (p == int.class || p == Integer.class) {
      try {
        return (int) Double.parseDouble(s);
      } catch (NumberFormatException e) {
        Integer c = intConstant(target.getClass(), m, s);
        return c == null ? NO_MATCH : c;
      }
    }
    if (p == double.class || p == Double.class) {
      try {
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        return NO_MATCH;
      }
    }
    if (p == String[].class) {
      return new String[] {s};
    }
    return NO_MATCH;
  }

  /**
   * Resolves a symbolic int constant such as {@code "TYPE_CALL"} or {@code "call"} for an int
   * setter, looking at static final int fields of the target's public types.
   */
  static Integer intConstant(Class<?> targetClass, Method setter, String name) {
    String wanted = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    String prop = setter.getName().substring(3).toUpperCase(Locale.ROOT);
    List<Class<?>> types = new ArrayList<>();
    types.add(setter.getDeclaringClass());
    types.addAll(publicTypes(targetClass));
    Field exact = null;
    Field prefixed = null;
    Field suffix = null;
    for (Class<?> t : types) {
      for (Field f : t.getFields()) {
        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
          continue;
        }
        String fn = f.getName();
        if (fn.equals(wanted)) {
          exact = exact == null ? f : exact;
        } else if (fn.equals(prop + "_" + wanted)) {
          prefixed = prefixed == null ? f : prefixed;
        } else if (fn.endsWith("_" + wanted)) {
          suffix = suffix == null ? f : suffix;
        }
      }
    }
    Field f = exact != null ? exact : prefixed != null ? prefixed : suffix;
    if (f == null) {
      return null;
    }
    try {
      return f.getInt(null);
    } catch (IllegalAccessException e) {
      return null;
    }
  }

  static Object invoke(Method m, Object target, Object... args) throws Exception {
    try {
      return m.invoke(target, args);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw e;
    }
  }
}
