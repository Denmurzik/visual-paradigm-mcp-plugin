package com.brunnen.vp.mcp.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MiniHttpServerTest {

  private MiniHttpServer server;

  @BeforeEach
  void start() throws Exception {
    server =
        new MiniHttpServer(
            InetAddress.getLoopbackAddress(),
            0,
            req -> {
              if ("/boom".equals(req.path)) {
                throw new IllegalStateException("boom");
              }
              return MiniHttpServer.Response.text(
                  200, req.method + " " + req.path + " " + req.bodyAsString());
            });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
    assertFalse(server.isRunning());
  }

  @Test
  void servesPostWithContentLength() throws Exception {
    URL url = new URL("http://127.0.0.1:" + server.getPort() + "/mcp?x=1");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    c.setRequestMethod("POST");
    c.setDoOutput(true);
    try (OutputStream out = c.getOutputStream()) {
      out.write("тест".getBytes(StandardCharsets.UTF_8));
    }
    assertEquals(200, c.getResponseCode());
    assertEquals("POST /mcp тест", read(c));
  }

  @Test
  void servesChunkedAndKeepAlive() throws Exception {
    try (Socket s = new Socket(InetAddress.getLoopbackAddress(), server.getPort())) {
      OutputStream out = s.getOutputStream();
      String req =
          "POST /a HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n"
              + "3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n"
              + "POST /b HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
      out.write(req.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String all = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(all.contains("POST /a abcde"), all);
      assertTrue(all.contains("POST /b ok"), all);
      assertTrue(all.contains("Connection: close"), all);
    }
  }

  @Test
  void handlerExceptionIs500() throws Exception {
    URL url = new URL("http://127.0.0.1:" + server.getPort() + "/boom");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    assertEquals(500, c.getResponseCode());
  }

  private static String read(HttpURLConnection c) throws Exception {
    try (BufferedReader r =
        new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
      return r.readLine();
    }
  }
}
