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
    assertDeadlineWhilePeerStaysOpen(false);
  }

  @Test
  void withheldEofCannotExtendTotalDeadline() throws Exception {
    assertDeadlineWhilePeerStaysOpen(true);
  }

  private void assertDeadlineWhilePeerStaysOpen(boolean sendDelayedVerdict) throws Exception {
    var releasePeer = new CountDownLatch(1);
    var connected = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    var peerSocket = new java.util.concurrent.atomic.AtomicReference<Socket>();
    try (var server = new ServerSocket()) {
      server.setReceiveBufferSize(1024);
      server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      server.setSoTimeout(5000);
      var peer =
          executor.submit(
              () -> {
                try (var socket = server.accept()) {
                  peerSocket.set(socket);
                  connected.countDown();
                  if (sendDelayedVerdict) {
                    var input = new DataInputStream(socket.getInputStream());
                    assertThat(input.readNBytes(10))
                        .isEqualTo("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                    int size;
                    while ((size = input.readInt()) != 0) input.readNBytes(size);
                    // Deliberate protocol pacing: the later EOF read must share the original
                    // budget.
                    if (!releasePeer.await(1500, TimeUnit.MILLISECONDS)) {
                      socket
                          .getOutputStream()
                          .write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                      socket.getOutputStream().flush();
                    }
                  }
                  // Neither a missing upload acknowledgement nor missing EOF may be rescued by peer
                  // close.
                  assertThat(releasePeer.await(10, TimeUnit.SECONDS)).isTrue();
                }
                return null;
              });
      var properties = new PictureScannerProperties();
      properties.setEnabled(true);
      properties.setPort(server.getLocalPort());
      properties.setTimeoutMillis(sendDelayedVerdict ? 2000 : 150);
      var scanner = new ClamAvPictureScanner(properties);
      try {
        var result =
            executor.submit(
                () ->
                    catchThrowable(
                        () ->
                            scanner.scan(
                                new byte[sendDelayedVerdict ? 1 : PictureIntake.MAX_BYTES])));
        assertThat(connected.await(2, TimeUnit.SECONDS)).isTrue();
        assertThatCode(
                () ->
                    assertThat(result.get(sendDelayedVerdict ? 3000 : 1500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(PictureException.class)
                        .hasMessage("PICTURE_SCAN_UNAVAILABLE"))
            .doesNotThrowAnyException();
        assertThat(peer.isDone())
            .as("peer must remain open until after the deadline assertion")
            .isFalse();
        assertThat(peerSocket.get().isClosed()).isFalse();
      } finally {
        releasePeer.countDown();
        var socket = peerSocket.get();
        if (socket != null) socket.close();
        scanner.close();
      }
      peer.get(3, TimeUnit.SECONDS);
    } finally {
      releasePeer.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
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
