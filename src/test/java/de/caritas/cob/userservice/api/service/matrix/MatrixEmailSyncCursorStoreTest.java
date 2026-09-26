package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.MatrixEmailSyncCursor;
import de.caritas.cob.userservice.api.port.out.MatrixEmailSyncCursorRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MatrixEmailSyncCursorStoreTest {
  @InjectMocks private MatrixEmailSyncCursorStore store;
  @Mock private MatrixEmailSyncCursorRepository repository;

  @Test
  void persistsFirstActivationBeforeAnyBatchCanBeProcessed() {
    when(repository.findById(1L)).thenReturn(Optional.empty());
    long before = System.currentTimeMillis();

    var start = store.readOrCreateActivation();

    long after = System.currentTimeMillis();
    var saved = ArgumentCaptor.forClass(MatrixEmailSyncCursor.class);
    verify(repository).saveAndFlush(saved.capture());
    assertThat(start.batchToken()).isNull();
    assertThat(start.activationEpochMillis()).isBetween(before, after);
    assertThat(saved.getValue().getActivationEpochMillis())
        .isEqualTo(start.activationEpochMillis());
  }

  @Test
  void restartUsesPersistedActivationAndCursor() {
    var cursor = new MatrixEmailSyncCursor();
    cursor.setId(1L);
    cursor.setActivationEpochMillis(123L);
    cursor.setBatchToken("persisted-batch");
    when(repository.findById(1L)).thenReturn(Optional.of(cursor));

    var start = store.readOrCreateActivation();

    assertThat(start.batchToken()).isEqualTo("persisted-batch");
    assertThat(start.activationEpochMillis()).isEqualTo(123L);
  }
}
