package com.brunnen.vp.mcp.vp;

import com.brunnen.vp.mcp.protocol.ToolException;
import java.awt.Dialog;
import java.awt.Window;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** Runs Visual Paradigm API calls on the Swing event dispatch thread, which VP requires. */
final class Edt {

  private static final long TIMEOUT_SECONDS = 180;

  private Edt() {}

  /** Number of tool bodies currently running on the EDT (more than one = nested in a dialog). */
  private static int running;

  /** Runs a read-only body. */
  static <T> T call(Callable<T> body) throws Exception {
    return call(body, false);
  }

  /**
   * Runs {@code body} on the EDT. An exclusive body (model change, save, open) is refused while a
   * modal dialog is open or another tool body is still running: VP runs queued events inside a
   * modal dialog's event loop, so it would otherwise execute in the middle of the other change
   * (that is how a save nested in an unfinished delete corrupted the project file).
   */
  static <T> T call(Callable<T> body, boolean exclusive) throws Exception {
    AtomicBoolean started = new AtomicBoolean();
    Callable<T> guarded =
        () -> {
          started.set(true);
          if (exclusive && (running > 0 || modalDialogOpen())) {
            throw new ToolException(
                "Visual Paradigm is busy: a dialog is open or another change is still running."
                    + " Close the dialog in VP (or wait) and try again.");
          }
          running++;
          try {
            return body.call();
          } finally {
            running--;
          }
        };
    if (SwingUtilities.isEventDispatchThread()) {
      return guarded.call();
    }
    FutureTask<T> task = new FutureTask<>(guarded);
    SwingUtilities.invokeLater(task);
    try {
      return task.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      // a FutureTask cancelled before it started never runs; one that started runs to its end
      boolean cancelled = task.cancel(false) && !started.get();
      throw new ToolException(
          "Visual Paradigm did not respond within "
              + TIMEOUT_SECONDS
              + "s (is a modal dialog open in VP?). "
              + (cancelled
                  ? "The request was cancelled and will not run later."
                  : "The request is still running inside VP; check the result before retrying."));
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw new IllegalStateException(cause);
    }
  }

  private static boolean modalDialogOpen() {
    for (Window w : Window.getWindows()) {
      if (w instanceof Dialog && w.isVisible() && ((Dialog) w).isModal()) {
        return true;
      }
    }
    return false;
  }
}
