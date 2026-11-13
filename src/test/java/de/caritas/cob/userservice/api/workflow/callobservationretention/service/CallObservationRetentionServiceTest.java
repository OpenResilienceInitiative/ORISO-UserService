package de.caritas.cob.userservice.api.workflow.callobservationretention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.port.out.MatrixCallObservationRepository;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CallObservationRetentionServiceTest {
  private final MatrixCallObservationRepository repo = mock(MatrixCallObservationRepository.class);
  private final CallObservationRetentionService service = new CallObservationRetentionService(repo);

  @Test
  void purgesWithACutoffOfTheConfiguredNumberOfDays() {
    setField(service, "retentionDays", 30);
    when(repo.deleteFinishedBefore(anyLong())).thenReturn(3);
    long before = System.currentTimeMillis();

    int purged = service.purgeExpired();

    long after = System.currentTimeMillis();
    var cutoff = ArgumentCaptor.forClass(Long.class);
    verify(repo).deleteFinishedBefore(cutoff.capture());
    long period = Duration.ofDays(30).toMillis();
    assertThat(purged).isEqualTo(3);
    assertThat(cutoff.getValue()).isBetween(before - period, after - period);
  }

  @Test
  void aNonPositivePeriodDisablesThePurgeInsteadOfDeletingEverything() {
    setField(service, "retentionDays", 0);
    assertThat(service.purgeExpired()).isZero();
    setField(service, "retentionDays", -5);
    assertThat(service.purgeExpired()).isZero();
    verifyNoInteractions(repo);
  }
}
