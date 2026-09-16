package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ConsultantPictureServiceTest {
  final ConsultantPictureAccess access = mock(ConsultantPictureAccess.class);
  final ConsultantPictureStore store = mock(ConsultantPictureStore.class);
  final ClamAvPictureScanner scanner = mock(ClamAvPictureScanner.class);
  final ConsultantPictureService service =
      new ConsultantPictureService(access, store, new PictureIntake(), scanner);

  @Test
  void authorizationPrecedesReadingAndScanning() {
    var body = mock(InputStream.class);
    doThrow(new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException("denied"))
        .when(access)
        .check("id", true);
    assertThatThrownBy(() -> service.put("id", body, "image/png"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    verifyNoInteractions(body, scanner, store);
  }

  @Test
  void cleanImageIsStoredOnlyAfterScannerAcceptsExactBytes() throws Exception {
    byte[] bytes = PictureIntakeTest.png(2, 2);
    service.put("id", new ByteArrayInputStream(bytes), "image/png");
    var order = inOrder(access, scanner, store);
    order.verify(access).check("id", true);
    order.verify(scanner).scan(bytes);
    order.verify(store).replace("id", bytes, "image/png");
  }

  @Test
  void failedReplacementNeverTouchesStorage() throws Exception {
    doThrow(PictureException.unavailable()).when(scanner).scan(any());
    assertThatThrownBy(
            () ->
                service.put(
                    "id", new ByteArrayInputStream(PictureIntakeTest.png(2, 2)), "image/png"))
        .hasMessage("PICTURE_SCAN_UNAVAILABLE");
    verifyNoInteractions(store);
  }

  @Test
  void invalidInputDoesNotScanOrWrite() {
    assertThatThrownBy(() -> service.put("id", new ByteArrayInputStream(new byte[0]), "image/png"))
        .hasMessage("PICTURE_INVALID_IMAGE");
    verifyNoInteractions(scanner, store);
  }

  @Test
  void refusedImagesNeverCreateImageIoTemporaryFiles(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path cache) throws Exception {
    byte[] png = PictureIntakeTest.png(2, 2);
    var previousDirectory = javax.imageio.ImageIO.getCacheDirectory();
    boolean previousUseCache = javax.imageio.ImageIO.getUseCache();
    try (var watcher = cache.getFileSystem().newWatchService()) {
      cache.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE);
      javax.imageio.ImageIO.setCacheDirectory(cache.toFile());
      javax.imageio.ImageIO.setUseCache(true);
      doThrow(PictureException.unavailable()).when(scanner).scan(any());
      assertThatThrownBy(() -> service.put("id", new ByteArrayInputStream(png), "image/png"))
          .hasMessage("PICTURE_SCAN_UNAVAILABLE");
      assertThatThrownBy(
              () -> service.put("id", new ByteArrayInputStream(new byte[] {1}), "image/png"))
          .hasMessage("PICTURE_INVALID_IMAGE");
      assertThat(watcher.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)).isNull();
      try (var files = java.nio.file.Files.list(cache)) {
        assertThat(files).isEmpty();
      }
      verifyNoInteractions(store);
    } finally {
      javax.imageio.ImageIO.setCacheDirectory(previousDirectory);
      javax.imageio.ImageIO.setUseCache(previousUseCache);
    }
  }

  @Test
  void twoBlockedScansRefuseThirdUploadAndBothPermitsRecover() throws Exception {
    byte[] bytes = PictureIntakeTest.png(2, 2);
    assertBothSlotsAvailableAndThirdRefused(bytes);
    assertBothSlotsAvailableAndThirdRefused(bytes);
    verify(store, times(4)).replace("id", bytes, "image/png");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"intake", "scan", "store"})
  void failuresReleaseBothPermits(String stage) throws Exception {
    byte[] bytes = PictureIntakeTest.png(2, 2);
    byte[] input = stage.equals("intake") ? new byte[0] : bytes;
    if (stage.equals("scan")) doThrow(PictureException.unavailable()).when(scanner).scan(any());
    if (stage.equals("store"))
      doThrow(new IllegalStateException("synthetic storage failure"))
          .when(store)
          .replace(anyString(), any(), anyString());
    // Two failures expose leaks of either slot, followed by two simultaneous successful uploads.
    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(() -> service.put("id", new ByteArrayInputStream(input), "image/png"))
          .hasMessage(
              stage.equals("intake")
                  ? "PICTURE_INVALID_IMAGE"
                  : stage.equals("scan")
                      ? "PICTURE_SCAN_UNAVAILABLE"
                      : "synthetic storage failure");
    }
    reset(scanner, store);
    assertBothSlotsAvailableAndThirdRefused(bytes);
    verify(store, times(2)).replace("id", bytes, "image/png");
  }

  private void assertBothSlotsAvailableAndThirdRefused(byte[] bytes) throws Exception {
    var scanning = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    doAnswer(
            invocation -> {
              scanning.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new AssertionError("scanner was not released");
              return null;
            })
        .when(scanner)
        .scan(any());
    try {
      var first =
          executor.submit(() -> service.put("id", new ByteArrayInputStream(bytes), "image/png"));
      var second =
          executor.submit(() -> service.put("id", new ByteArrayInputStream(bytes), "image/png"));
      assertThat(scanning.await(2, TimeUnit.SECONDS))
          .as("both upload slots reach scanner")
          .isTrue();
      var refusedBody = mock(InputStream.class);
      assertThatThrownBy(() -> service.put("id", refusedBody, "image/png"))
          .isInstanceOfSatisfying(
              PictureException.class,
              error -> {
                assertThat(error.getStatus())
                    .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error).hasMessage("PICTURE_SCAN_UNAVAILABLE");
              });
      verifyNoInteractions(refusedBody);
      release.countDown();
      first.get(2, TimeUnit.SECONDS);
      second.get(2, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
