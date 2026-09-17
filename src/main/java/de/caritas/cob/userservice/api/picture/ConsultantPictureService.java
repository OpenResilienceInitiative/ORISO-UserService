package de.caritas.cob.userservice.api.picture;

import java.io.InputStream;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConsultantPictureService {
  private final ConsultantPictureAccess access;
  private final ConsultantPictureStore store;
  private final PictureIntake intake;
  private final ClamAvPictureScanner scanner;
  private final Semaphore uploads = new Semaphore(2);

  /** Never hold a database transaction while receiving, decoding or scanning personal bytes. */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void put(String id, InputStream body, String contentType) {
    access.check(id, true);
    scanAndStore(id, body, contentType, store::replace);
  }

  /**
   * Issue #1049: the onboarding wizard's upload. The caller has already resolved the consultant
   * from the raw invite token, which is the credential there. Intake, bounded concurrency and the
   * fail-closed scan are identical to the administrative path.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void putForOnboarding(String id, InputStream body, String contentType) {
    scanAndStore(id, body, contentType, store::replaceForOnboarding);
  }

  private void scanAndStore(String id, InputStream body, String contentType, PictureWriter write) {
    if (!uploads.tryAcquire()) throw PictureException.unavailable();
    try {
      byte[] bytes = intake.read(body, contentType);
      scanner.scan(bytes);
      write.write(id, bytes, contentType);
    } finally {
      uploads.release();
    }
  }

  @FunctionalInterface
  private interface PictureWriter {
    void write(String id, byte[] bytes, String contentType);
  }
}
