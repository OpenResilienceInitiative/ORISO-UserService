package de.caritas.cob.userservice.api.service.matrix;

import de.caritas.cob.userservice.api.model.MatrixEmailSyncCursor;
import de.caritas.cob.userservice.api.port.out.MatrixEmailSyncCursorRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists the Matrix cursor only after all reply-email intake rows exist. */
@Service
@RequiredArgsConstructor
public class MatrixEmailSyncCursorStore {
  private static final long CURSOR_ID = 1L;
  private final @NonNull MatrixEmailSyncCursorRepository repository;

  /** Freeze the activation instant before observing Matrix events for the first time. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Start readOrCreateActivation() {
    var cursor = repository.findById(CURSOR_ID).orElse(null);
    if (cursor == null) {
      cursor = new MatrixEmailSyncCursor();
      cursor.setId(CURSOR_ID);
      cursor.setActivationEpochMillis(System.currentTimeMillis());
      repository.saveAndFlush(cursor);
    }
    return new Start(cursor.getBatchToken(), cursor.getActivationEpochMillis());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(String token) {
    var cursor = repository.findById(CURSOR_ID).orElseThrow();
    cursor.setBatchToken(token);
    repository.saveAndFlush(cursor);
  }

  public record Start(String batchToken, long activationEpochMillis) {}
}
