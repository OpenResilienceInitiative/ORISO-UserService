package de.caritas.cob.userservice.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Private test-only pipe to the actual frozen client API modules in one owned browser. */
final class CurrentClientBrowserBridge implements AutoCloseable {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Process process;
  private final BufferedWriter input;
  private final BufferedReader output;
  private final Path scratch;
  private final ExecutorService reader =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
  private boolean closed;

  static boolean required() {
    String mode = System.getenv("STARTUP_CURRENT_CLIENT_BROWSER");
    if (mode == null) return false;
    if (!"true".equals(mode)) throw new IllegalStateException("Invalid browser proof mode");
    return true;
  }

  CurrentClientBrowserBridge(int port, JsonNode fixture) throws Exception {
    if (!required() || port < 1024 || port > 65535)
      throw new IllegalStateException("Actual browser fixture is required");
    scratch =
        Files.createTempDirectory(
            Path.of(System.getenv("STARTUP_PRIVATE_EXCHANGE")), "client-browser-");
    try {
      process =
          new ProcessBuilder(
                  "node",
                  "--max-old-space-size=256",
                  Path.of("scripts/ci/client-browser-bridge.mjs").toAbsolutePath().toString())
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
    } catch (Exception failure) {
      removeScratch();
      reader.shutdownNow();
      throw new IllegalStateException("Owned browser process could not start");
    }
    input =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    output =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    try {
      var config = fixture.path("browserConfig").deepCopy();
      if (!config.isObject())
        throw new IllegalStateException("Private browser configuration absent");
      ((com.fasterxml.jackson.databind.node.ObjectNode) config)
          .put("backendOrigin", "http://127.0.0.1:" + port)
          .put("privateRoot", scratch.toString());
      require(
          exchange(Map.of("command", "open", "config", config)).path("opened").asBoolean(false),
          "Owned browser did not open");
    } catch (Exception failure) {
      stop();
      throw new IllegalStateException("Owned browser setup failed");
    }
  }

  void seed(String client, String actor, JsonNode tokens) throws Exception {
    require(
        exchange(Map.of("command", "seed", "client", client, "actor", actor, "tokens", tokens))
            .path("seeded")
            .asBoolean(false),
        "Native browser actor was not seeded");
  }

  JsonNode send(
      String client,
      String actor,
      String kind,
      String target,
      String operation,
      Map<String, Object> payload,
      boolean dropAfterSuccess)
      throws Exception {
    return exchange(
        Map.of(
            "command", "send",
            "client", client,
            "actor", actor,
            "kind", kind,
            "target", target,
            "operationId", operation,
            "payload", payload,
            "dropAfterSuccess", dropAfterSuccess));
  }

  private JsonNode exchange(Map<String, ?> command) throws Exception {
    require(!closed && process.isAlive(), "Owned browser process is unavailable");
    // No command, token, raw response or exception message is written to logs or evidence.
    var pending =
        reader.submit(
            () -> {
              input.write(JSON.writeValueAsString(command));
              input.newLine();
              input.flush();
              var line = new StringBuilder();
              for (int value; (value = output.read()) != -1; ) {
                if (value == '\n') return line.toString();
                if (line.length() == 131072)
                  throw new IllegalStateException("Browser receipt exceeded bound");
                line.append((char) value);
              }
              throw new IllegalStateException("Browser receipt was absent");
            });
    try {
      JsonNode response = JSON.readTree(pending.get(30, TimeUnit.SECONDS));
      require(response != null && response.isObject(), "Browser receipt was invalid");
      return response;
    } catch (Exception failure) {
      pending.cancel(true);
      stop();
      throw new IllegalStateException("Bounded browser exchange failed");
    }
  }

  @Override
  public void close() throws Exception {
    if (closed) return;
    try {
      var result = exchange(Map.of("command", "close"));
      require(
          result.path("sourceUnchanged").asBoolean(false), "Client source changed during proof");
      input.close();
      require(
          process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0,
          "Owned browser process did not close");
    } finally {
      stop();
    }
  }

  private void stop() {
    closed = true;
    var descendants = process.descendants().toList();
    process.destroy();
    descendants.forEach(ProcessHandle::destroy);
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException interrupted) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    reader.shutdownNow();
    removeScratch();
  }

  private void removeScratch() {
    // This one private subtree belongs to this bridge. Never follow browser-created symlinks.
    if (Files.notExists(scratch, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
    try (var files = Files.walk(scratch)) {
      for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Owned browser scratch cleanup failed");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
