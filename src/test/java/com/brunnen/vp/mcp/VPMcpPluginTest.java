package com.brunnen.vp.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VPMcpPluginTest {

  @Test
  void parsesPort() {
    assertEquals(1234, VPMcpPlugin.parsePort(" 1234 "));
    assertEquals(VPMcpPlugin.DEFAULT_PORT, VPMcpPlugin.parsePort(null));
    assertEquals(VPMcpPlugin.DEFAULT_PORT, VPMcpPlugin.parsePort("abc"));
    assertEquals(VPMcpPlugin.DEFAULT_PORT, VPMcpPlugin.parsePort("70000"));
  }

  @Test
  void readsPortFromPluginDirectory(@TempDir Path dir) throws Exception {
    Files.write(dir.resolve("mcp.properties"), "port=9999\n".getBytes(StandardCharsets.UTF_8));
    assertEquals(9999, VPMcpPlugin.readPort(dir.toFile()));
    assertEquals(VPMcpPlugin.DEFAULT_PORT, VPMcpPlugin.readPort(new File(dir.toFile(), "none")));
    assertEquals(VPMcpPlugin.DEFAULT_PORT, VPMcpPlugin.readPort(null));
  }
}
