package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.ConsultantMessageStat;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantMessageStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantMessageStatServiceTest {

  private static final String CONSULTANT_ID = "consultant-1";
  private static final String CONSULTANT_HMAC = "hmac-of-consultant-1";

  @Mock private ConsultantMessageStatRepository consultantMessageStatRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private ConsultantIdentityHasher consultantIdentityHasher;

  private ConsultantMessageStatService service;

  @BeforeEach
  void setUp() {
    service =
        new ConsultantMessageStatService(
            consultantMessageStatRepository, sessionRepository, consultantIdentityHasher);
  }

  @Test
  void recordMessageSentShouldPersistHmacNotPlainConsultantId() {
    when(consultantIdentityHasher.hash(CONSULTANT_ID)).thenReturn(CONSULTANT_HMAC);
    var session = new Session();
    session.setTenantId(3L);
    session.setAgencyId(9L);
    when(sessionRepository.findById(100L)).thenReturn(Optional.of(session));

    service.recordMessageSent(CONSULTANT_ID, 100L);

    var captor = ArgumentCaptor.forClass(ConsultantMessageStat.class);
    verify(consultantMessageStatRepository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getConsultantHmac()).isEqualTo(CONSULTANT_HMAC);
    assertThat(saved.getTenantId()).isEqualTo(3L);
    assertThat(saved.getAgencyId()).isEqualTo(9L);
    assertThat(saved.getSourceSessionId()).isEqualTo(100L);
    assertThat(saved.getSentDate()).isNotNull();
  }

  @Test
  void recordMessageSentShouldToleratePersistFailureWithoutThrowing() {
    when(consultantIdentityHasher.hash(CONSULTANT_ID)).thenReturn(CONSULTANT_HMAC);
    when(sessionRepository.findById(100L)).thenReturn(Optional.empty());
    when(consultantMessageStatRepository.save(any())).thenThrow(new RuntimeException("db down"));

    assertThatCode(() -> service.recordMessageSent(CONSULTANT_ID, 100L)).doesNotThrowAnyException();
  }

  @Test
  void countForConsultantShouldHashBeforeQuerying() {
    var from = LocalDateTime.of(2026, 7, 1, 0, 0);
    var to = LocalDateTime.of(2026, 8, 1, 0, 0);
    when(consultantIdentityHasher.hash(CONSULTANT_ID)).thenReturn(CONSULTANT_HMAC);
    when(consultantMessageStatRepository.countByConsultantHmacAndTenantIdInPeriod(
            CONSULTANT_HMAC, 3L, from, to))
        .thenReturn(4L);

    var count = service.countForConsultant(CONSULTANT_ID, 3L, from, to);

    assertThat(count).isEqualTo(4L);
    verifyNoInteractions(sessionRepository);
  }
}
