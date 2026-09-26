package de.caritas.cob.userservice.api.service.notification;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.Status;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentMailWorkerTest {
  @Mock GroupAppointmentMailOutboxRepository outbox;
  @Mock GroupAppointmentMailEligibilityService eligibility;
  @Mock GroupAppointmentMailComposer composer;
  @Mock GroupAppointmentMailClaimService claims;
  @Mock TenantSystemEmailDelivery delivery;
  @InjectMocks GroupAppointmentMailWorker worker;

  private GroupAppointmentMailOutbox mail;
  private GroupAppointmentMailEligibilityService.Eligible eligible;
  private GroupAppointmentMailComposer.Composed composed;

  @BeforeEach
  void setUp() {
    var owner = new Consultant();
    owner.setTenantId(7L);
    var chat = new Chat();
    chat.setId(42L);
    chat.setChatOwner(owner);
    mail =
        GroupAppointmentMailOutbox.builder()
            .id(11L)
            .seriesId(42L)
            .correlationId("f7e8bbfe-7ca9-4e8e-8c55-54575ceca5a9")
            .build();
    eligible =
        new GroupAppointmentMailEligibilityService.Eligible(
            chat,
            new GroupAppointmentOccurrenceState(),
            new GroupAppointmentEmailRecipientService.Recipient(
                "person", "person@example.org", OrisoEmailRenderer.Tone.EN));
    composed =
        new GroupAppointmentMailComposer.Composed(
            7L,
            new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null),
            TenantSystemEmailDelivery.Purpose.SELF_HELP_APPOINTMENT_REMINDER,
            "person@example.org",
            new OrisoEmailRenderer.RenderedEmail("Date", "<p>Date</p>", "Date"));
    when(outbox
            .findTop100ByStatusAndDueAtUtcLessThanEqualAndNextAttemptAtUtcLessThanEqualOrderByDueAtUtcAsc(
                eq(Status.PENDING), any(LocalDateTime.class), any(LocalDateTime.class)))
        .thenReturn(List.of(mail));
  }

  @Test
  void supersededReminderIsSuppressedWithoutTransport() {
    when(eligibility.resolve(mail)).thenReturn(Optional.empty());
    when(claims.claim(11L)).thenReturn(true);

    worker.dispatchDue();

    verify(claims).finish(11L, Status.SUPPRESSED);
    verify(delivery, never()).sendConfirmed(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void currentClaimIsSentOnce() {
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(composed));
    when(claims.claim(11L)).thenReturn(true);
    when(delivery.sendConfirmed(
            7L,
            composed.route(),
            composed.purpose(),
            composed.recipient(),
            composed.email(),
            mail.getCorrelationId()))
        .thenReturn(true);

    worker.dispatchDue();

    verify(claims).finish(11L, Status.SENT);
  }

  @Test
  void ambiguousTransportFailureRemainsForOperatorReconciliation() {
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(composed));
    when(claims.claim(11L)).thenReturn(true);
    when(delivery.sendConfirmed(
            7L,
            composed.route(),
            composed.purpose(),
            composed.recipient(),
            composed.email(),
            mail.getCorrelationId()))
        .thenThrow(new IllegalStateException("relay timeout"));

    worker.dispatchDue();

    verify(claims).finish(11L, Status.UNCERTAIN);
  }

  @Test
  void changedRecipientBeforeHandoffReleasesTheClaim() {
    var changed =
        new GroupAppointmentMailEligibilityService.Eligible(
            eligible.series(),
            eligible.occurrence(),
            new GroupAppointmentEmailRecipientService.Recipient(
                "person", "new@example.org", OrisoEmailRenderer.Tone.EN));
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible), Optional.of(changed));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(composed));
    when(claims.claim(11L)).thenReturn(true);

    worker.dispatchDue();

    verify(claims).releaseBeforeHandoff(11L);
    verify(delivery, never()).sendConfirmed(anyLong(), any(), any(), any(), any(), any());
  }

  @Test
  void invalidTenantConfigurationIsDeferredBeforeClaim() {
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenThrow(new IllegalStateException("missing origin"));
    when(claims.deferConfigurationFailure(eq(11L), any(LocalDateTime.class))).thenReturn(true);

    worker.dispatchDue();

    var nextAttempt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(claims).deferConfigurationFailure(eq(11L), nextAttempt.capture());
    assertTrue(nextAttempt.getValue().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(4)));
    verify(claims, never()).claim(11L);
    verify(delivery, never()).sendConfirmed(anyLong(), any(), any(), any(), any(), any());
  }
}
