package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.DeletedAskerSession;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.DeletedAskerSessionRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ORISO-Frontend#1159: placeholder for a deleted advice-seeker chat. */
@ExtendWith(MockitoExtension.class)
class DeletedAskerSessionServiceTest {

  @InjectMocks private DeletedAskerSessionService underTest;
  @Mock private DeletedAskerSessionRepository repository;
  @Mock private EventNotificationService eventNotificationService;

  @BeforeEach
  void setUp() {
    setField(underTest, "retentionDays", 30);
  }

  private static Session assignedSession() {
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    var session = new Session();
    session.setId(77L);
    session.setConsultant(consultant);
    session.setAgencyId(5L);
    session.setTenantId(3L);
    return session;
  }

  @Test
  void recordDeletion_savesAPlaceholderWithoutPersonalDataAndOneTimelineEntry() {
    var captor = ArgumentCaptor.forClass(DeletedAskerSession.class);

    underTest.recordDeletion(assignedSession());

    verify(repository).save(captor.capture());
    var saved = captor.getValue();
    assertThat(saved.getConsultantId()).isEqualTo("consultant-1");
    assertThat(saved.getFormerSessionId()).isEqualTo(77L);
    assertThat(saved.getAgencyId()).isEqualTo(5L);
    assertThat(saved.getTenantId()).isEqualTo(3L);
    assertThat(saved.getDeletedAt()).isNotNull();
    verify(eventNotificationService)
        .createEventOnce(
            eq("session.asker_deleted:77"),
            eq("consultant-1"),
            eq("session.asker_deleted"),
            eq(EventNotificationService.CATEGORY_SYSTEM),
            anyString(),
            anyString(),
            isNull(),
            isNull(),
            isNull(),
            eq(3L));
  }

  @Test
  void recordDeletion_addsNoSecondPlaceholder_When_theDeletionIsRetried() {
    when(repository.existsByConsultantIdAndFormerSessionId("consultant-1", 77L)).thenReturn(true);

    underTest.recordDeletion(assignedSession());

    verify(repository, never()).save(any());
  }

  @Test
  void recordDeletion_leavesNothing_When_noCounsellorHadAcceptedTheCase() {
    var session = assignedSession();
    session.setConsultant(null);

    underTest.recordDeletion(session);
    underTest.recordDeletion(null);

    verifyNoInteractions(repository, eventNotificationService);
  }

  @Test
  void recordDeletion_neverThrows() {
    when(repository.existsByConsultantIdAndFormerSessionId(any(), any()))
        .thenThrow(new RuntimeException("db down"));

    assertDoesNotThrow(() -> underTest.recordDeletion(assignedSession()));
  }

  @Test
  void findForConsultant_readsOnlyTheRetentionWindow() {
    var cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
    when(repository.findByConsultantIdAndDeletedAtAfterOrderByDeletedAtDesc(
            eq("consultant-1"), cutoff.capture()))
        .thenReturn(List.of());

    underTest.findForConsultant("consultant-1");

    var expected = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
    assertThat(cutoff.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
  }

  @Test
  void findForConsultant_returnsNothingWithoutAConsultant() {
    assertThat(underTest.findForConsultant(" ")).isEmpty();
    verifyNoInteractions(repository);
  }

  @Test
  void purgeExpired_isDisabledByANonPositiveSetting() {
    setField(underTest, "retentionDays", 0);

    assertThat(underTest.purgeExpired()).isZero();
    verifyNoInteractions(repository);
  }

  @Test
  void purgeExpired_deletesPlaceholdersOlderThanTheRetention() {
    when(repository.deleteDeletedBefore(any())).thenReturn(2);

    assertThat(underTest.purgeExpired()).isEqualTo(2);
  }
}
