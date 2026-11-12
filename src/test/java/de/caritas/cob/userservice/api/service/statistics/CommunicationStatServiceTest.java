package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.CommunicationStat;
import de.caritas.cob.userservice.api.model.CommunicationStat.Kind;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.CommunicationStatRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.statistics.settings.StatisticsSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CommunicationStatServiceTest {

  private static final long SESSION_ID = 10L;
  private static final long TENANT_ID = 3L;
  private static final long AGENCY_ID = 7L;

  @Mock private CommunicationStatRepository repository;
  @Mock private SessionRepository sessionRepository;
  @Mock private StatisticsSettingsService settings;
  @Mock private StatisticsFeatureGate featureGate;

  private CommunicationStatService service;

  @BeforeEach
  void setUp() {
    service =
        new CommunicationStatService(
            repository,
            sessionRepository,
            settings,
            featureGate,
            Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"), ZoneOffset.UTC));
    var session = new Session();
    session.setId(SESSION_ID);
    session.setTenantId(TENANT_ID);
    session.setAgencyId(AGENCY_ID);
    when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
    when(settings.isMessageCountTrackingEnabled()).thenReturn(true);
    when(featureGate.isEnabledForTenant(TENANT_ID)).thenReturn(true);
  }

  private CommunicationStat saved() {
    var captor = ArgumentCaptor.forClass(CommunicationStat.class);
    verify(repository).save(captor.capture());
    return captor.getValue();
  }

  @Test
  void adviceSeekerMessage_storesOnlyPlaceKindAndTime() {
    service.recordAdviceSeekerMessage(SESSION_ID);

    var row = saved();
    assertThat(row.getKind()).isEqualTo(Kind.ADVICE_SEEKER_MESSAGE);
    assertThat(row.getTenantId()).isEqualTo(TENANT_ID);
    assertThat(row.getAgencyId()).isEqualTo(AGENCY_ID);
    assertThat(row.getSessionId()).isEqualTo(SESSION_ID);
    assertThat(row.getOccurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 13, 9, 0));
    assertThat(row.getCallKey()).isNull();
  }

  @Test
  void call_isStoredWithItsKind_andAHashedCallKey() {
    service.recordCall(SESSION_ID, "call-1", true);

    var row = saved();
    assertThat(row.getKind()).isEqualTo(Kind.VIDEO_CALL);
    assertThat(row.getCallKey()).hasSize(64).isNotEqualTo("call-1");
  }

  @Test
  void audioCall_isStoredAsAudio() {
    service.recordCall(SESSION_ID, "call-2", false);

    assertThat(saved().getKind()).isEqualTo(Kind.AUDIO_CALL);
  }

  @Test
  void call_alreadyCounted_isNotCountedAgain() {
    when(repository.existsByCallKey(CommunicationStatService.callKey("call-1"))).thenReturn(true);

    service.recordCall(SESSION_ID, "call-1", false);

    verify(repository, never()).save(any());
  }

  @Test
  void nothingIsStored_whenMessageCountingIsSwitchedOff() {
    when(settings.isMessageCountTrackingEnabled()).thenReturn(false);

    service.recordAdviceSeekerMessage(SESSION_ID);
    service.recordCall(SESSION_ID, "call-1", true);

    verify(repository, never()).save(any());
  }

  @Test
  void nothingIsStored_whenTheOrganisationDidNotSwitchStatisticsOn() {
    when(featureGate.isEnabledForTenant(TENANT_ID)).thenReturn(false);

    service.recordAdviceSeekerMessage(SESSION_ID);

    verify(repository, never()).save(any());
  }

  @Test
  void nothingIsStored_withoutSessionOrCallId() {
    service.recordAdviceSeekerMessage(null);
    service.recordCall(SESSION_ID, " ", true);
    service.recordAdviceSeekerMessage(99L);

    verify(repository, never()).save(any());
  }

  @Test
  void aFailingDatabase_isSwallowed() {
    when(repository.save(any())).thenThrow(new IllegalStateException("db down"));

    assertThatCode(() -> service.recordAdviceSeekerMessage(SESSION_ID)).doesNotThrowAnyException();
  }
}
