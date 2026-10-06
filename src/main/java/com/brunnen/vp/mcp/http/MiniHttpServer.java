package com.brunnen.vp.mcp.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tiny HTTP/1.1 server on top of {@link ServerSocket}.
 *
 * <p>Visual Paradigm's bundled runtime does not contain the {@code jdk.httpserver} module, so this
 * class implements the small subset of HTTP that the MCP Streamable HTTP transport needs:
 * Content-Length and chunked request bodies, keep-alive, and fixed-length responses.
 */
public final class MiniHttpServer {

  private static final int MAX_BODY = 32 * 1024 * 1024;
  private static final int MAX_LINE = 64 * 1024;
  private static final int IDLE_TIMEOUT_MS = 120_000;

  /** A parsed HTTP request. */
  public static final class Request {
    public final String method;
    public final String path;
    /** Header names are lower-cased. */
    public final Map<String, String> headers;

    public final byte[] body;

    Request(String method, String path, Map<String, String> headers, byte[] body) {
      this.method = method;
      this.path = path;
      this.headers = headers;
      this.body = body;
    }

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  /** An HTTP response. */
  public static final class Response {
    public final int status;
    public final String contentType;
    public final byte[] body;
    public final Map<String, String> headers = new LinkedHashMap<>();

    /** Response with the given status, content type (may be null) and body. */
    public Response(int status, String contentType, byte[] body) {
      this.status = status;
      this.contentType = contentType;
      this.body = body == null ? new byte[0] : body;
    }

    public static Response json(int status, String json) {
      return new Response(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    public static Response text(int status, String text) {
      return new Response(
          status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    public static Response empty(int status) {
      return new Response(status, null, null);
    }
  }

  /** Request handler. */
  public interface Handler {
    Response handle(Request request) throws Exception;
  }

  private final InetAddress bindAddress;
  private final int port;
  private final Handler handler;
  private volatile ServerSocket serverSocket;
  private ExecutorService workers;
  private Thread acceptThread;

  /** Server bound to {@code bindAddress:port} (port 0 picks a free port). */
  public MiniHttpServer(InetAddress bindAddress, int port, Handler handler) {
    this.bindAddress = bindAddress;
    this.port = port;
    this.handler = handler;
  }

  /** Binds the socket and starts accepting connections in background daemon threads. */
  public synchronized void start() throws IOException {
    if (serverSocket != null) {
      return;
    }
    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(bindAddress, port));
    serverSocket = ss;
    AtomicInteger counter = new AtomicInteger();
    workers =
        Executors.newCachedThreadPool(
            r -> {
              Thread t = new Thread(r, "vp-mcp-http-" + counter.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    acceptThread = new Thread(this::acceptLoop, "vp-mcp-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  /** Actual bound port (useful when started with port 0). */
  public int getPort() {
    ServerSocket ss = serverSocket;
    return ss == null ? -1 : ss.getLocalPort();
  }

  public boolean isRunning() {
    ServerSocket ss = serverSocket;
    return ss != null && !ss.isClosed();
  }

  /** Stops accepting connections and releases the port. */
  public synchronized void stop() {
    ServerSocket ss = serverSocket;
    serverSocket = null;
    if (ss != null) {
      try {
        ss.close();
      } catch (IOException ignored) {
        // closing anyway
      }
    }
    if (workers != null) {
      workers.shutdownNow();
      workers = null;
    }
  }

  private void acceptLoop() {
    ServerSocket ss = serverSocket;
    while (ss != null && !ss.isClosed()) {
      try {
        Socket socket = ss.accept();
        ExecutorService pool = workers;
        if (pool == null) {
          socket.close();
          return;
        }
        pool.execute(() -> serve(socket));
      } catch (IOException e) {
        if (ss.isClosed()) {
          return;
        }
      }
    }
  }

  private void serve(Socket socket) {
    try (Socket s = socket) {
      s.setSoTimeout(IDLE_TIMEOUT_MS);
      InputStream in = new BufferedInputStream(s.getInputStream());
      OutputStream out = s.getOutputStream();
      while (true) {
        Request req;
        try {
          req = readRequest(in);
        } catch (SocketTimeoutException | SocketException e) {
          return;
        } catch (HttpException e) {
          write(out, Response.text(e.status, e.getMessage()), false);
          return;
        }
        if (req == null) {
          return;
        }
        Response resp;
        try {
          resp = handler.handle(req);
        } catch (Exception e) {
          resp = Response.text(500, "Internal error: " + e);
        }
        boolean keepAlive = !"close".equalsIgnoreCase(req.header("connection"));
        write(out, resp, keepAlive);
        if (!keepAlive) {
          return;
        }
      }
    } catch (IOException ignored) {
      // client went away
    }
  }

  static Request readRequest(InputStream in) throws IOException {
    String requestLine = readLine(in);
    if (requestLine == null) {
      return null;
    }
    while (requestLine.isEmpty()) { // tolerate stray CRLF between requests
      requestLine = readLine(in);
      if (requestLine == null) {
        return null;
      }
    }
    String[] parts = requestLine.split(" ");
    if (parts.length < 2) {
      throw new HttpException(400, "Bad request line");
    }
    Map<String, String> headers = new LinkedHashMap<>();
    String line;
    while ((line = readLine(in)) != null && !line.isEmpty()) {
      int colon = line.indexOf(':');
      if (colon > 0) {
        headers.put(
            line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
            line.substring(colon + 1).trim());
      }
    }
    byte[] body;
    String te = headers.get("transfer-encoding");
    if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
      body = readChunked(in);
    } else {
      String cl = headers.get("content-length");
      int len = 0;
      if (cl != null) {
        try {
          len = Integer.parseInt(cl.trim());
        } catch (NumberFormatException e) {
          throw new HttpException(400, "Bad Content-Length");
        }
      }
      if (len < 0 || len > MAX_BODY) {
        throw new HttpException(413, "Body too large");
      }
      body = in.readNBytes(len);
      if (body.length != len) {
        throw new HttpException(400, "Truncated body");
      }
    }
    String path = parts[1];
    int q = path.indexOf('?');
    if (q >= 0) {
      path = path.substring(0, q);
    }
    return new Request(parts[0].toUpperCase(Locale.ROOT), path, headers, body);
  }

  private static byte[] readChunked(InputStream in) throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    while (true) {
      String sizeLine = readLine(in);
      if (sizeLine == null) {
        throw new HttpException(400, "Truncated chunked body");
      }
      int semi = sizeLine.indexOf(';');
      String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
      int size;
      try {
        size = Integer.parseInt(hex, 16);
      } catch (NumberFormatException e) {
        throw new HttpException(400, "Bad chunk size");
      }
      if (size == 0) {
        String trailer;
        while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
          // ignore trailers
        }
        return buf.toByteArray();
      }
      if (buf.size() + size > MAX_BODY) {
        throw new HttpException(413, "Body too large");
      }
      byte[] chunk = in.readNBytes(size);
      if (chunk.length != size) {
        throw new HttpException(400, "Truncated chunk");
      }
      buf.write(chunk);
      readLine(in); // CRLF after chunk
    }
  }

  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) != -1) {
      if (b == '\n') {
        break;
      }
      if (b != '\r') {
        line.write(b);
      }
      if (line.size() > MAX_LINE) {
        throw new HttpException(431, "Header line too long");
      }
    }
    if (b == -1 && line.size() == 0) {
      return null;
    }
    return line.toString(StandardCharsets.ISO_8859_1);
  }

  private static void write(OutputStream out, Response resp, boolean keepAlive) throws IOException {
    StringBuilder head = new StringBuilder();
    head.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason(resp.status));
    head.append("\r\n");
    if (resp.contentType != null) {
      head.append("Content-Type: ").append(resp.contentType).append("\r\n");
    }
    head.append("Content-Length: ").append(resp.body.length).append("\r\n");
    for (Map.Entry<String, String> h : resp.headers.entrySet()) {
      head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
    }
    head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
    head.append("\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    out.write(resp.body);
    out.flush();
  }

  private static String reason(int status) {
    switch (status) {
      case 200:
        return "OK";
      case 202:
        return "Accepted";
      case 204:
        return "No Content";
      case 400:
        return "Bad Request";
      case 403:
        return "Forbidden";
      case 404:
        return "Not Found";
      case 405:
        return "Method Not Allowed";
      case 413:
        return "Payload Too Large";
      case 431:
        return "Request Header Fields Too Large";
      default:
        return status >= 500 ? "Server Error" : "Status";
    }
  }

  /** Protocol-level error that maps to an HTTP status. */
  static final class HttpException extends IOException {
    private static final long serialVersionUID = 1L;
    final int status;

    HttpException(int status, String message) {
      super(message);
      this.status = status;
    }
  }
}
