package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClamAvPictureScannerTest {
  @Test
  void disabledRefuses() {
    assertThatThrownBy(
            () -> new ClamAvPictureScanner(new PictureScannerProperties()).scan(new byte[] {1}))
        .hasMessage("PICTURE_SCAN_UNAVAILABLE");
  }

  @Test
  void cleanScansExactBytes() throws Exception {
    scan("stream: OK\0", null);
  }

  @Test
  void infectedRefuses() throws Exception {
    scan("stream: Synthetic-Test FOUND\0", "PICTURE_REJECTED");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "stream: ERROR\0",
        "stream: OK",
        "other: OK\0",
        "stream: garbage OK\0",
        "stream: OK\0unexpected second response\0",
        "\0",
        "stream: size limit exceeded ERROR\0"
      })
  void unexpectedResponsesRefuse(String response) throws Exception {
    scan(response, "PICTURE_SCAN_UNAVAILABLE");
  }

  @Test
  void oversizedResponseRefuses() throws Exception {
    scan("x".repeat(1025) + "\0", "PICTURE_SCAN_UNAVAILABLE");
  }

  @Test
  void timeoutRefuses() throws Exception {
    scan(null, "PICTURE_SCAN_UNAVAILABLE");
  }

  @Test
  void blockedWriteHasATotalDeadline() throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var executor = Executors.newSingleThreadExecutor()) {
      var accepted =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  socket.setReceiveBufferSize(1024);
                  Thread.sleep(400);
                }
                return null;
              });
      var properties = new PictureScannerProperties();
      properties.setEnabled(true);
      properties.setPort(server.getLocalPort());
      properties.setTimeoutMillis(150);
      var scanner = new ClamAvPictureScanner(properties);
      long started = System.nanoTime();
      try {
        assertThatThrownBy(() -> scanner.scan(new byte[PictureIntake.MAX_BYTES]))
            .hasMessage("PICTURE_SCAN_UNAVAILABLE");
      } finally {
        scanner.close();
      }
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
      accepted.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void tricklingResponseCannotExtendTotalDeadline() throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var executor = Executors.newSingleThreadExecutor()) {
      server.setSoTimeout(2000);
      var accepted =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  var in = new DataInputStream(socket.getInputStream());
                  in.readNBytes(10);
                  int size;
                  while ((size = in.readInt()) != 0) in.readNBytes(size);
                  for (byte value : "stream: OK\0".getBytes(StandardCharsets.US_ASCII)) {
                    socket.getOutputStream().write(value);
                    socket.getOutputStream().flush();
                    Thread.sleep(80);
                  }
                } catch (IOException expectedClosedClient) {
                  // The total deadline closes the client while the peer is still trickling bytes.
                }
                return null;
              });
      var properties = new PictureScannerProperties();
      properties.setEnabled(true);
      properties.setPort(server.getLocalPort());
      properties.setTimeoutMillis(150);
      var scanner = new ClamAvPictureScanner(properties);
      long started = System.nanoTime();
      try {
        assertThatThrownBy(() -> scanner.scan(new byte[] {1}))
            .hasMessage("PICTURE_SCAN_UNAVAILABLE");
      } finally {
        scanner.close();
      }
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(700);
      accepted.get(2, TimeUnit.SECONDS);
    }
  }

  private void scan(String response, String error) throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var executor = Executors.newSingleThreadExecutor()) {
      server.setSoTimeout(2000);
      byte[] bytes = new byte[] {1, 2, 3, 4};
      Future<byte[]> received =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  var in = new DataInputStream(socket.getInputStream());
                  assertThat(in.readNBytes(10))
                      .isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                  var payload = new ByteArrayOutputStream();
                  int size;
                  while ((size = in.readInt()) != 0) payload.write(in.readNBytes(size));
                  if (response == null) Thread.sleep(400);
                  else socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                  return payload.toByteArray();
                }
              });
      var properties = new PictureScannerProperties();
      properties.setEnabled(true);
      properties.setPort(server.getLocalPort());
      properties.setTimeoutMillis(150);
      var scanner = new ClamAvPictureScanner(properties);
      try {
        if (error == null) scanner.scan(bytes);
        else assertThatThrownBy(() -> scanner.scan(bytes)).hasMessage(error);
      } finally {
        scanner.close();
      }
      assertThat(received.get(2, TimeUnit.SECONDS)).isEqualTo(bytes);
    }
  }
}
