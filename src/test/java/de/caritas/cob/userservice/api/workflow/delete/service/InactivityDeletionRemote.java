package de.caritas.cob.userservice.api.workflow.delete.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class InactivityDeletionRemote implements AutoCloseable {
  final AtomicBoolean enabled = new AtomicBoolean(true);
  final AtomicBoolean locked = new AtomicBoolean();
  final AtomicBoolean unsafeDestructiveCall = new AtomicBoolean();
  final AtomicInteger deactivations = new AtomicInteger();
  final AtomicInteger logouts = new AtomicInteger();
  private final HttpServer server;

  InactivityDeletionRemote() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          var method = exchange.getRequestMethod();
          var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String response = "{}";
          int status = 200;
          if (path.endsWith("/login")) response = "{\"access_token\":\"test-token\"}";
          else if (path.contains("/_synapse/admin/v2/users/")) {
            if (method.equals("PUT"))
              locked.set(body.matches("(?s).*\\\"locked\\\"\\s*:\\s*true.*"));
            response = "{\"locked\":" + locked.get() + "}";
          } else if (path.contains("/_synapse/admin/v1/deactivate/")) {
            deactivations.incrementAndGet();
            if (enabled.get() || !locked.get() || logouts.get() == 0)
              unsafeDestructiveCall.set(true);
            status = 503;
          } else if (path.endsWith("/logout")) {
            logouts.incrementAndGet();
            status = 204;
          } else if (path.endsWith("/role-mappings/realm/composite"))
            response = "[{\"name\":\"user\"}]";
          else if (path.endsWith("/clients") || path.endsWith("/sessions")) response = "[]";
          else if (path.endsWith("/users/inactivity-lock-first")) {
            if (method.equals("PUT")) {
              enabled.set(body.matches("(?s).*\\\"enabled\\\"\\s*:\\s*true.*"));
              status = 204;
            }
            response = "{\"id\":\"inactivity-lock-first\",\"enabled\":" + enabled.get() + "}";
          } else status = 404;
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          if (status == 204) exchange.sendResponseHeaders(status, -1);
          else {
            var bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
          exchange.close();
        });
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void close() {
    server.stop(0);
  }
}
