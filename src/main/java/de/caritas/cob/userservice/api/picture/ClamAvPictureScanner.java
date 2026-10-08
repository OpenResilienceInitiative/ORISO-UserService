package de.caritas.cob.userservice.api.picture;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Loopback-only clamd INSTREAM. Only the exact clean verdict permits persistence. */
@Component
@RequiredArgsConstructor
public class ClamAvPictureScanner {
  private final PictureScannerProperties properties;
  private final ScheduledExecutorService deadlines =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            var thread = new Thread(r, "picture-scan-deadline");
            thread.setDaemon(true);
            return thread;
          });

  public void scan(byte[] bytes) {
    if (!properties.isEnabled()
        || properties.getPort() < 1
        || properties.getPort() > 65535
        || properties.getTimeoutMillis() < 100
        || properties.getTimeoutMillis() > 30000
        || bytes.length > PictureIntake.MAX_BYTES) throw PictureException.unavailable();
    try (var socket = new Socket()) {
      // Socket read timeouts alone cannot bound blocked writes or a trickling response.
      var deadline =
          deadlines.schedule(
              () -> close(socket), properties.getTimeoutMillis(), TimeUnit.MILLISECONDS);
      try {
        socket.connect(
            new InetSocketAddress("127.0.0.1", properties.getPort()),
            properties.getTimeoutMillis());
        socket.setSoTimeout(properties.getTimeoutMillis());
        var out = new DataOutputStream(socket.getOutputStream());
        out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
        for (int offset = 0; offset < bytes.length; offset += 8192) {
          int count = Math.min(8192, bytes.length - offset);
          out.writeInt(count);
          out.write(bytes, offset, count);
        }
        out.writeInt(0);
        out.flush();
        var response = new ByteArrayOutputStream();
        var input = socket.getInputStream();
        while (true) {
          int value = input.read();
          if (value < 0 || response.size() >= 1024 || value > 127)
            throw PictureException.unavailable();
          if (value == 0) break;
          response.write(value);
        }
        // A single command outside IDSESSION ends with the peer closing the connection.
        // Extra bytes or a second verdict are not an unambiguous clean scan.
        if (input.read() != -1) throw PictureException.unavailable();
        String verdict = response.toString(StandardCharsets.US_ASCII);
        if (verdict.equals("stream: OK")) return;
        if (verdict.matches("stream: [A-Za-z0-9_.() -]{1,200} FOUND"))
          throw PictureException.rejected();
        throw PictureException.unavailable();
      } finally {
        deadline.cancel(false);
      }
    } catch (IOException ex) {
      throw PictureException.unavailable();
    }
  }

  private static void close(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      /* Already closed; fail-closed caller handles I/O. */
    }
  }

  @PreDestroy
  public void close() {
    deadlines.shutdownNow();
  }
}
