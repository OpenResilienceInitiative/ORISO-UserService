package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.*;
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
}
