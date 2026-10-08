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
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.PlatformSmtpSettingsProvider;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
            java.util.UUID.fromString(mail.getCorrelationId())))
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
            java.util.UUID.fromString(mail.getCorrelationId())))
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

  @Test
  void missingSavedPlatformSettingsRemainPendingBeforeSmtpClaim() {
    var platform =
        new GroupAppointmentMailComposer.Composed(
            composed.tenantId(),
            new TenantSystemEmailRouteService.Route(
                TenantSystemEmailRouteService.Mode.PLATFORM, null),
            composed.purpose(),
            composed.recipient(),
            composed.email());
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(platform));
    org.mockito.Mockito.lenient()
        .doThrow(new IllegalStateException("SMTP_DISABLED_OR_INCOMPLETE"))
        .when(delivery)
        .requireConfigured(platform.route());
    org.mockito.Mockito.lenient()
        .when(claims.deferConfigurationFailure(eq(11L), any(LocalDateTime.class)))
        .thenReturn(true);

    worker.dispatchDue();

    verify(claims).deferConfigurationFailure(eq(11L), any(LocalDateTime.class));
    verify(claims, never()).claim(11L);
    verify(claims, never()).finish(anyLong(), any());
    verify(delivery, never()).sendConfirmed(anyLong(), any(), any(), any(), any(), any());
  }

  static Stream<RuntimeException> platformValidationFailures() {
    return Stream.of(
        new PlatformSmtpSettingsProvider.ConfigurationException("Missing saved settings"),
        new ApplicationSettingsService.SmtpSettingsUnavailableException());
  }

  @ParameterizedTest
  @MethodSource("platformValidationFailures")
  void settingsChangeAfterPreflightDoesNotBecomeAnUncertainSmtpAttempt(RuntimeException failure) {
    var platform =
        new GroupAppointmentMailComposer.Composed(
            composed.tenantId(),
            new TenantSystemEmailRouteService.Route(
                TenantSystemEmailRouteService.Mode.PLATFORM, null),
            composed.purpose(),
            composed.recipient(),
            composed.email());
    var settings = org.mockito.Mockito.mock(PlatformSmtpSettingsProvider.class);
    var dispatcher = org.mockito.Mockito.mock(OrisoEmailDispatcher.class);
    var client = org.mockito.Mockito.mock(TenantSystemEmailClient.class);
    var actualDelivery = new TenantSystemEmailDelivery(client, settings, dispatcher);
    var actualWorker =
        new GroupAppointmentMailWorker(outbox, eligibility, composer, claims, actualDelivery);
    when(settings.requireConfigured())
        .thenReturn(
            new PlatformSmtpSettingsProvider.Settings(
                "smtp.example.org", 587, false, "account", "secret", "sender@example.org", null))
        .thenThrow(failure);
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(platform));
    when(claims.claim(11L)).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(claims.deferConfigurationFailure(eq(11L), any(LocalDateTime.class)))
        .thenReturn(true);

    actualWorker.dispatchDue();

    var order = org.mockito.Mockito.inOrder(settings, claims);
    order.verify(settings).requireConfigured();
    order.verify(claims).claim(11L);
    order.verify(settings).requireConfigured();
    order.verify(claims).releaseBeforeHandoff(11L);
    order.verify(claims).deferConfigurationFailure(eq(11L), any(LocalDateTime.class));
    verify(claims, never()).finish(anyLong(), any());
    org.mockito.Mockito.verifyNoInteractions(dispatcher, client);
  }

  @Test
  void ownRelayValidationRejectionIsDeferredWithoutReadingPlatformSettings() {
    var settings = org.mockito.Mockito.mock(PlatformSmtpSettingsProvider.class);
    var dispatcher = org.mockito.Mockito.mock(OrisoEmailDispatcher.class);
    var client = org.mockito.Mockito.mock(TenantSystemEmailClient.class);
    var actualDelivery = new TenantSystemEmailDelivery(client, settings, dispatcher);
    var actualWorker =
        new GroupAppointmentMailWorker(outbox, eligibility, composer, claims, actualDelivery);
    org.mockito.Mockito.doThrow(
            new TenantSystemEmailRouteService.ConfigurationException(
                "OWN tenant SMTP configuration is invalid"))
        .when(client)
        .deliver(
            7L,
            composed.purpose().name(),
            composed.recipient(),
            composed.email(),
            UUID.fromString(mail.getCorrelationId()));
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(composed));
    when(claims.claim(11L)).thenReturn(true);
    org.mockito.Mockito.lenient()
        .when(claims.deferConfigurationFailure(eq(11L), any(LocalDateTime.class)))
        .thenReturn(true);

    actualWorker.dispatchDue();

    verify(claims).releaseBeforeHandoff(11L);
    verify(claims).deferConfigurationFailure(eq(11L), any(LocalDateTime.class));
    verify(claims, never()).finish(anyLong(), any());
    org.mockito.Mockito.verifyNoInteractions(settings, dispatcher);
  }

  @Test
  void falseTransportAcknowledgementRemainsUncertainWithoutAutomaticReplay() {
    when(eligibility.resolve(mail)).thenReturn(Optional.of(eligible));
    when(composer.compose(mail, eligible)).thenReturn(Optional.of(composed));
    when(claims.claim(11L)).thenReturn(true);

    worker.dispatchDue();

    verify(claims).finish(11L, Status.UNCERTAIN);
    verify(claims, never()).releaseBeforeHandoff(anyLong());
    verify(claims, never()).deferConfigurationFailure(anyLong(), any());
  }
}
