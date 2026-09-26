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

  @Transactional(readOnly = true)
  public String read() {
    return repository.findById(CURSOR_ID).map(MatrixEmailSyncCursor::getBatchToken).orElse(null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void write(String token) {
    var cursor = repository.findById(CURSOR_ID).orElseGet(MatrixEmailSyncCursor::new);
    cursor.setId(CURSOR_ID);
    cursor.setBatchToken(token);
    repository.saveAndFlush(cursor);
  }
}
