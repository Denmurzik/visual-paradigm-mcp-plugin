package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.ToolException;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

/**
 * Lets the AI see and answer the dialogs Visual Paradigm opens (project recovery, save changes,
 * confirmations). While a modal dialog is open VP refuses model changes, so without this the user
 * has to click it away.
 */
final class Dialogs {

  private static final int MAX_TEXT = 1500;

  private Dialogs() {}

  /** Visible dialogs with their title, text and buttons. Runs on the EDT. */
  static List<Object> list() {
    List<Object> out = new ArrayList<>();
    int index = 0;
    for (Dialog d : visibleDialogs()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("index", index++);
      m.put("title", d.getTitle());
      m.put("modal", d.isModal());
      StringBuilder text = new StringBuilder();
      List<String> buttons = new ArrayList<>();
      collect(d, text, buttons);
      String t = text.toString().trim();
      m.put("text", t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "..." : t);
      m.put("buttons", buttons);
      out.add(m);
    }
    return out;
  }

  /**
   * Presses the button with the given label (case-insensitive, exact match preferred) in the dialog
   * at {@code index} (default: the top-most modal dialog), or closes the dialog when {@code button}
   * is "close". The click runs after this call returns, so a follow-up dialog cannot block the tool
   * call.
   */
  static Map<String, Object> press(String button, Integer index) {
    List<Dialog> dialogs = visibleDialogs();
    if (dialogs.isEmpty()) {
      throw new ToolException("No dialog is open in Visual Paradigm");
    }
    Dialog d;
    if (index != null) {
      if (index < 0 || index >= dialogs.size()) {
        throw new ToolException("No dialog with index " + index + " (see vp_list_dialogs)");
      }
      d = dialogs.get(index);
    } else {
      d = dialogs.get(dialogs.size() - 1);
      for (int i = dialogs.size() - 1; i >= 0; i--) {
        if (dialogs.get(i).isModal()) {
          d = dialogs.get(i);
          break;
        }
      }
    }
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("dialog", d.getTitle());
    final Dialog target = d;
    if ("close".equalsIgnoreCase(button.trim())) {
      SwingUtilities.invokeLater(
          () -> target.dispatchEvent(new WindowEvent(target, WindowEvent.WINDOW_CLOSING)));
      r.put("action", "closed");
      return r;
    }
    AbstractButton found = findButton(d, button);
    if (found == null) {
      List<String> buttons = new ArrayList<>();
      collect(d, new StringBuilder(), buttons);
      throw new ToolException(
          "No button '" + button + "' in dialog '" + d.getTitle() + "'. Buttons: " + buttons);
    }
    final AbstractButton click = found;
    SwingUtilities.invokeLater(click::doClick);
    r.put("pressed", label(found));
    return r;
  }

  static List<Dialog> visibleDialogs() {
    List<Dialog> out = new ArrayList<>();
    for (Window w : Window.getWindows()) {
      if (w instanceof Dialog && w.isVisible()) {
        out.add((Dialog) w);
      }
    }
    return out;
  }

  private static AbstractButton findButton(Container root, String wanted) {
    String w = normalise(wanted);
    List<AbstractButton> all = new ArrayList<>();
    buttons(root, all);
    for (AbstractButton b : all) {
      if (normalise(label(b)).equals(w)) {
        return b;
      }
    }
    for (AbstractButton b : all) {
      if (normalise(label(b)).contains(w)) {
        return b;
      }
    }
    return null;
  }

  private static void buttons(Container c, List<AbstractButton> out) {
    for (Component k : c.getComponents()) {
      if (k instanceof AbstractButton && k.isShowing() && k.isEnabled()) {
        out.add((AbstractButton) k);
      }
      if (k instanceof Container) {
        buttons((Container) k, out);
      }
    }
  }

  private static void collect(Container c, StringBuilder text, List<String> buttons) {
    for (Component k : c.getComponents()) {
      if (!k.isShowing()) {
        continue;
      }
      if (k instanceof AbstractButton) {
        String l = label((AbstractButton) k);
        if (!l.isEmpty() && k.isEnabled()) {
          buttons.add(l);
        }
      } else if (k instanceof JLabel) {
        append(text, ((JLabel) k).getText());
      } else if (k instanceof JTextComponent) {
        append(text, ((JTextComponent) k).getText());
      } else if (k instanceof JOptionPane) {
        Object msg = ((JOptionPane) k).getMessage();
        if (msg instanceof String) {
          append(text, (String) msg);
        }
      }
      if (k instanceof Container) {
        collect((Container) k, text, buttons);
      }
    }
  }

  private static void append(StringBuilder sb, String s) {
    if (s == null) {
      return;
    }
    String plain = s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    if (!plain.isEmpty()) {
      sb.append(plain).append('\n');
    }
  }

  private static String label(AbstractButton b) {
    String t = b.getText();
    if (t == null || t.isEmpty()) {
      t = b.getToolTipText();
    }
    return t == null ? "" : t.replaceAll("<[^>]+>", "").trim();
  }

  private static String normalise(String s) {
    return s.replace("&", "").trim().toLowerCase(Locale.ROOT);
  }
}
