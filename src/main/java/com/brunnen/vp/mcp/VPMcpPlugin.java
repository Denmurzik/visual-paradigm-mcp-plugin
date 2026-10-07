package com.brunnen.vp.mcp;

import com.brunnen.vp.mcp.http.MiniHttpServer;
import com.brunnen.vp.mcp.protocol.McpHandler;
import com.brunnen.vp.mcp.protocol.ToolRegistry;
import com.brunnen.vp.mcp.vp.VpTools;
import com.vp.plugin.ApplicationManager;
import com.vp.plugin.VPPlugin;
import com.vp.plugin.VPPluginInfo;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Properties;
import javax.swing.SwingUtilities;

/** Visual Paradigm plugin that serves the open project over MCP (Streamable HTTP). */
public final class VPMcpPlugin implements VPPlugin {

  public static final String SERVER_NAME = "visual-paradigm";
  public static final String VERSION = "0.4.0";
  static final int DEFAULT_PORT = 8931;

  private MiniHttpServer server;

  @Override
  public void loaded(VPPluginInfo info) {
    File dir = info == null ? null : info.getPluginDir();
    int port = readPort(dir);
    try {
      ToolRegistry tools = VpTools.register(new ToolRegistry());
      McpHandler handler = new McpHandler(SERVER_NAME, VERSION, VpTools.INSTRUCTIONS, tools);
      server = new MiniHttpServer(InetAddress.getLoopbackAddress(), port, handler);
      server.start();
      log("MCP server listening on http://127.0.0.1:" + port + "/mcp (" + tools.size() + " tools)");
    } catch (IOException | RuntimeException e) {
      log("MCP server could not start on port " + port + ": " + e);
      server = null;
    }
  }

  @Override
  public void unloaded() {
    if (server != null) {
      server.stop();
      server = null;
      log("MCP server stopped");
    }
  }

  static int readPort(File pluginDir) {
    String sys = System.getProperty("vp.mcp.port");
    if (sys != null) {
      return parsePort(sys);
    }
    if (pluginDir != null) {
      File f = new File(pluginDir, "mcp.properties");
      if (f.isFile()) {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(f.toPath())) {
          p.load(in);
          return parsePort(p.getProperty("port"));
        } catch (IOException e) {
          log("Cannot read " + f + ": " + e);
        }
      }
    }
    return DEFAULT_PORT;
  }

  static int parsePort(String value) {
    if (value == null) {
      return DEFAULT_PORT;
    }
    try {
      int p = Integer.parseInt(value.trim());
      return p > 0 && p < 65536 ? p : DEFAULT_PORT;
    } catch (NumberFormatException e) {
      return DEFAULT_PORT;
    }
  }

  private static void log(String message) {
    System.out.println("[vp-mcp] " + message);
    SwingUtilities.invokeLater(
        () -> {
          try {
            ApplicationManager app = ApplicationManager.instance();
            if (app != null && app.getViewManager() != null) {
              app.getViewManager().showMessage("[MCP] " + message);
            }
          } catch (RuntimeException e) {
            // message pane is optional
          }
        });
  }
}
