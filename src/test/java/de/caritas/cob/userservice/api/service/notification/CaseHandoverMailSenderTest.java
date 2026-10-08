package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class CaseHandoverMailSenderTest {
  private final TenantSystemEmailRouteService routes = mock(TenantSystemEmailRouteService.class);
  private final TenantSystemEmailDelivery delivery = mock(TenantSystemEmailDelivery.class);
  private final TenantService tenants = mock(TenantService.class);
  private final TenantTemplateSupplier urls = mock(TenantTemplateSupplier.class);
  private final CaseHandoverMailComposer composer = mock(CaseHandoverMailComposer.class);
  private final CaseHandoverRequestRepository requests = mock(CaseHandoverRequestRepository.class);
  private final ReleaseToggleService toggles = mock(ReleaseToggleService.class);
  private final AccountInactivityService lifecycle = mock(AccountInactivityService.class);
  private final SessionRepository sessions = mock(SessionRepository.class);
  private CaseHandoverRequest currentRequest;
  private final CaseHandoverMailSender sender =
      new CaseHandoverMailSender(
          routes,
          delivery,
          tenants,
          urls,
          composer,
          new CaseHandoverGrantedMailEligibility(requests, toggles, lifecycle),
          new CaseHandoverRequiredConsentMailEligibility(requests, lifecycle),
          sessions,
          toggles);

  @BeforeEach
  void currentGrant() {
    var recipient =
        Consultant.builder()
            .id("incoming-id")
            .username("incoming")
            .firstName("Incoming")
            .lastName("Counsellor")
            .email("incoming@example.test")
            .tenantId(40L)
            .build();
    recipient.setNotificationsEnabled(true);
    recipient.setNotificationsSettings("{\"reassignmentNotificationEnabled\":true}");
    var session =
        Session.builder()
            .id(77L)
            .tenantId(40L)
            .matrixRoomId("!room:example.test")
            .consultant(recipient)
            .registrationType(Session.RegistrationType.REGISTERED)
            .postcode("12345")
            .status(Session.SessionStatus.IN_PROGRESS)
            .build();
    currentRequest =
        CaseHandoverRequest.builder()
            .id(12L)
            .tenantId(40L)
            .session(session)
            .requesterConsultant(recipient)
            .status(CaseHandoverRequest.Status.GRANTED)
            .accessType(CaseHandoverRequest.AccessType.TAKEOVER)
            .build();
    when(requests.findById(12L)).thenReturn(Optional.of(currentRequest));
    when(lifecycle.snapshot("incoming-id")).thenReturn(Optional.empty());
  }

  private final Session session = new Session();

  @BeforeEach
  void currentRecipient() {
    var user = new User();
    user.setUserId("asker-id");
    user.setTenantId(40L);
    user.setEmail("asker@example.test");
    user.setNotificationsEnabled(true);
    user.setNotificationsSettings("{\"reassignmentNotificationEnabled\":true}");
    session.setUser(user);
    session.setTenantId(40L);
    session.setId(77L);
    session.setMatrixRoomId("!room");
    session.setStatus(Session.SessionStatus.IN_PROGRESS);
    session.setRegistrationType(Session.RegistrationType.REGISTERED);
    when(sessions.findById(77L)).thenReturn(Optional.of(session));
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(true);
  }

  @Test
  void sendsThroughTheTenantPlatformRouteAndCorrectOutcomePurpose() {
    currentRequest.setStatus(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT);
    currentRequest.setClientConsent(
        de.caritas.cob.userservice.api.model.CaseHandoverConsentMode.OPT_IN);
    currentRequest.setPreviousConsultant(currentRequest.getSession().getConsultant());
    var seeker = new de.caritas.cob.userservice.api.model.User();
    seeker.setUserId("asker-id");
    seeker.setTenantId(40L);
    seeker.setEmail("asker@example.test");
    currentRequest.getSession().setUser(seeker);
    var mail =
        mail(40L, CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED, "asker@example.test");
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant = mock(RestrictedTenantDTO.class);
    var content =
        new OrisoEmailRenderer.RenderedEmail("New notification", "<p>Sign in</p>", "Sign in");
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(tenant.getId()).thenReturn(40L);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(mail, "https://tenant.example.test")).thenReturn(content);

    when(sessions.findById(77L)).thenReturn(Optional.of(currentRequest.getSession()));
    sender.send(mail);

    verify(delivery)
        .sendConfirmed(
            40L,
            route,
            TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED,
            "asker@example.test",
            content);
  }

  @Test
  void disabledSystemMailDoesNotRenderOrSend() {
    when(routes.resolve(40L)).thenReturn(Optional.empty());
    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));
    verifyNoInteractions(tenants, urls, composer, delivery);
  }

  @Test
  void wrongTenantCannotComposeOrSend() {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.OWN, null);
    var otherTenant = mock(RestrictedTenantDTO.class);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(otherTenant);
    when(otherTenant.getId()).thenReturn(41L);
    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));
    verifyNoInteractions(urls, composer, delivery);
  }

  @Test
  void missingTenantSubdomainCannotGenerateARecipientLink() {
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant = mock(RestrictedTenantDTO.class);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(tenant.getId()).thenReturn(40L);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://null.example.test");

    sender.send(mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test"));

    verifyNoInteractions(composer, delivery);
  }

  @Test
  void aLaterTakeoverPreventsDeliveryToTheFormerOwner() {
    var mail = grantReadyForDelivery();
    var replacement = new Consultant();
    replacement.setId("replacement-id");
    currentRequest.getSession().setConsultant(replacement);

    sender.send(mail);

    verifyNoInteractions(composer, delivery);
  }

  @ParameterizedTest
  @EnumSource(InvalidGrant.class)
  void queuedGrantMustStillMatchTheCurrentAuthorizedRecipient(InvalidGrant change) {
    var mail = grantReadyForDelivery();
    switch (change) {
      case DELETED_RECIPIENT ->
          currentRequest.getRequesterConsultant().setDeleteDate(LocalDateTime.now());
      case CHANGED_EMAIL ->
          currentRequest.getRequesterConsultant().setEmail("replacement@example.test");
      case REQUEST_TENANT -> currentRequest.setTenantId(41L);
      case SESSION_TENANT -> currentRequest.getSession().setTenantId(41L);
      case RECIPIENT_TENANT -> currentRequest.getRequesterConsultant().setTenantId(41L);
      case REQUEST_ID -> currentRequest.setId(13L);
      case SESSION_ID -> currentRequest.getSession().setId(78L);
      case ROOM -> currentRequest.getSession().setMatrixRoomId("!other:example.test");
      case DECLINED -> currentRequest.setStatus(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED);
      case PENDING -> currentRequest.setStatus(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT);
      case CO_ACCESS -> currentRequest.setAccessType(CaseHandoverRequest.AccessType.CO_ACCESS);
    }
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  @ParameterizedTest
  @EnumSource(
      value = CaseHandoverRequest.Status.class,
      names = {"GRANTED", "GRANTED_PENDING_CLIENT_OPTOUT"})
  void authorizedGrantStillSendsTheImmutableSnapshotForCompletedCases(
      CaseHandoverRequest.Status status) {
    var mail = grantReadyForDelivery();
    currentRequest.setStatus(status);
    currentRequest.getSession().setStatus(Session.SessionStatus.DONE);
    currentRequest.getRequesterConsultant().setLanguageCode(LanguageCode.de);
    sender.send(mail);
    verify(composer).compose(mail, "https://tenant.example.test");
    verify(delivery)
        .sendConfirmed(
            eq(40L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.HANDOVER_CONFIRMED),
            eq(mail.recipient()),
            any());
  }

  @Test
  void currentReassignmentOptOutSuppressesTheQueuedGrant() {
    var mail = grantReadyForDelivery();
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(true);
    currentRequest
        .getRequesterConsultant()
        .setNotificationsSettings("{\"reassignmentNotificationEnabled\":false}");
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  @Test
  void legacyReleaseToggleKeepsItsExistingPreferenceBehavior() {
    var mail = grantReadyForDelivery();
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(false);
    currentRequest.getRequesterConsultant().setNotificationsEnabled(false);
    currentRequest
        .getRequesterConsultant()
        .setNotificationsSettings("{\"reassignmentNotificationEnabled\":false}");
    sender.send(mail);
    verify(delivery)
        .sendConfirmed(
            eq(40L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.HANDOVER_CONFIRMED),
            eq(mail.recipient()),
            any());
  }

  @Test
  void missingCurrentGrantCannotRenderOrSend() {
    var mail = grantReadyForDelivery();
    when(requests.findById(12L)).thenReturn(Optional.empty());
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  @Test
  void unavailableEligibilityCannotSendOrFailTheCommittedOutcome() {
    var mail = grantReadyForDelivery();
    when(requests.findById(12L)).thenThrow(new IllegalStateException("sensitive provider details"));
    org.assertj.core.api.Assertions.assertThatCode(() -> sender.send(mail))
        .doesNotThrowAnyException();
    verifyNoInteractions(composer, delivery);
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountInactivityService.Status.class,
      names = "ACTIVE",
      mode = EnumSource.Mode.EXCLUDE)
  void inactiveAccountWithNoDeleteDateCannotReceiveTheQueuedGrant(
      AccountInactivityService.Status status) {
    var mail = grantReadyForDelivery();
    org.assertj.core.api.Assertions.assertThat(
            currentRequest.getRequesterConsultant().getDeleteDate())
        .isNull();
    when(lifecycle.snapshot("incoming-id"))
        .thenReturn(
            Optional.of(
                new AccountInactivityService.Snapshot(
                    "incoming-id",
                    40L,
                    12,
                    1L,
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1000),
                    status,
                    0,
                    null)));
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  @Test
  void activeLifecycleAccountRemainsEligible() {
    var mail = grantReadyForDelivery();
    when(lifecycle.snapshot("incoming-id"))
        .thenReturn(
            Optional.of(
                new AccountInactivityService.Snapshot(
                    "incoming-id",
                    40L,
                    12,
                    1L,
                    Instant.EPOCH,
                    Instant.EPOCH.plusSeconds(1000),
                    AccountInactivityService.Status.ACTIVE,
                    0,
                    null)));
    sender.send(mail);
    verify(delivery)
        .sendConfirmed(
            eq(40L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.HANDOVER_CONFIRMED),
            eq(mail.recipient()),
            any());
  }

  @Test
  void unavailableLifecycleReadCannotSend() {
    var mail = grantReadyForDelivery();
    when(lifecycle.snapshot("incoming-id"))
        .thenThrow(new IllegalStateException("sensitive lifecycle details"));
    org.assertj.core.api.Assertions.assertThatCode(() -> sender.send(mail))
        .doesNotThrowAnyException();
    verifyNoInteractions(composer, delivery);
  }

  private enum InvalidGrant {
    DELETED_RECIPIENT,
    CHANGED_EMAIL,
    REQUEST_TENANT,
    SESSION_TENANT,
    RECIPIENT_TENANT,
    REQUEST_ID,
    SESSION_ID,
    ROOM,
    DECLINED,
    PENDING,
    CO_ACCESS
  }

  private CaseHandoverEmailNotification.Mail grantReadyForDelivery() {
    var mail = mail(40L, CaseHandoverEmailNotification.Outcome.GRANTED, "incoming@example.test");
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant = new RestrictedTenantDTO().id(40L);
    var content = new OrisoEmailRenderer.RenderedEmail("Notice", "<p>Sign in</p>", "Sign in");
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(mail, "https://tenant.example.test")).thenReturn(content);
    return mail;
  }

  @Test
  void newlyDisabledConversationEmailPolicyPreventsTheQueuedCoAccessEmail() {
    var route =
        new TenantSystemEmailRouteService.Route(
            TenantSystemEmailRouteService.Mode.PLATFORM, "#123456");
    var tenant =
        new RestrictedTenantDTO()
            .id(40L)
            .settings(new Settings().featureAskerEmailAgencyCounsellingEnabled(false));
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    sender.send(
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            "!room",
            CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
            40L,
            "asker@example.test",
            LanguageCode.en,
            null,
            "asker-id",
            CaseHandoverRequest.AccessType.CO_ACCESS));
    verifyNoInteractions(composer, delivery);
  }

  @Test
  void coAccessCannotRedirectTheOriginalRecipientOrSendAfterRejection() {
    var mail =
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            "!room",
            CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
            40L,
            "asker@example.test",
            LanguageCode.en,
            null,
            "asker-id",
            CaseHandoverRequest.AccessType.CO_ACCESS);
    when(routes.resolve(40L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    var tenant =
        new RestrictedTenantDTO()
            .id(40L)
            .settings(new Settings().featureAskerEmailAgencyCounsellingEnabled(true));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    sender.send(mail);
    verify(composer).compose(mail, "https://tenant.example.test");
    org.mockito.Mockito.clearInvocations(composer, delivery);
    session.getUser().setUserId("replacement-id");
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
    session.getUser().setUserId("asker-id");
    session.setStatus(Session.SessionStatus.REJECTED);
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType.class)
  void requiredTakeoverBypassesOptionalAccountGatesWhileCoAccessRetainsThem(
      de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType type) {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant =
        new RestrictedTenantDTO()
            .id(40L)
            .settings(new Settings().featureAskerEmailAgencyCounsellingEnabled(true));
    var content = new OrisoEmailRenderer.RenderedEmail("Notice", "<p>Sign in</p>", "Sign in");
    var mail =
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            "!room",
            CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
            40L,
            "asker@example.test",
            LanguageCode.en,
            null,
            "asker-id",
            type);
    currentRequest.setSession(session);
    currentRequest.setAccessType(type);
    currentRequest.setStatus(CaseHandoverRequest.Status.PENDING_CLIENT_CONSENT);
    currentRequest.setClientConsent(
        de.caritas.cob.userservice.api.model.CaseHandoverConsentMode.OPT_IN);
    currentRequest.setPreviousConsultant(null);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(mail, "https://tenant.example.test")).thenReturn(content);
    sender.send(mail);
    verify(delivery)
        .sendConfirmed(
            40L,
            route,
            TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED,
            "asker@example.test",
            content);
    org.mockito.Mockito.clearInvocations(delivery, composer);
    session.getUser().setNotificationsEnabled(false);
    sender.send(mail);
    assertOptionalAccountOutcome(type);
    org.mockito.Mockito.clearInvocations(delivery, composer);
    session.getUser().setNotificationsEnabled(true);
    tenant.getSettings().setFeatureAskerEmailAgencyCounsellingEnabled(false);
    sender.send(mail);
    assertOptionalAccountOutcome(type);
    org.mockito.Mockito.clearInvocations(delivery, composer);
    tenant.getSettings().setFeatureAskerEmailAgencyCounsellingEnabled(true);
    tenant.getSettings().setFeatureAskerEmailEnabled(false);
    sender.send(mail);
    assertOptionalAccountOutcome(type);
    org.mockito.Mockito.clearInvocations(delivery, composer);
    tenant.getSettings().setFeatureAskerEmailEnabled(true);
    session.getUser().setNotificationsSettings("{\"reassignmentNotificationEnabled\":false}");
    sender.send(mail);
    assertOptionalAccountOutcome(type);
  }

  private void assertOptionalAccountOutcome(CaseHandoverRequest.AccessType type) {
    if (type == CaseHandoverRequest.AccessType.CO_ACCESS) verifyNoInteractions(delivery, composer);
    else
      verify(delivery)
          .sendConfirmed(
              eq(40L),
              any(),
              eq(TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED),
              eq("asker@example.test"),
              any());
  }

  @Test
  void legacyAnonymousSessionUsesLiveChatDefaultsAndFreshExplicitOverride() {
    var route =
        new TenantSystemEmailRouteService.Route(TenantSystemEmailRouteService.Mode.PLATFORM, null);
    var tenant = new RestrictedTenantDTO().id(40L);
    var mail =
        new CaseHandoverEmailNotification.Mail(
            12L,
            77L,
            "!room",
            CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED,
            40L,
            "asker@example.test",
            LanguageCode.en,
            null,
            "asker-id",
            CaseHandoverRequest.AccessType.CO_ACCESS);
    session.setRegistrationType(Session.RegistrationType.ANONYMOUS);
    when(routes.resolve(40L)).thenReturn(Optional.of(route));
    when(tenants.getRestrictedTenantDataFresh(40L)).thenReturn(tenant);
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
    tenant.setSettings(new Settings().featureAskerEmailLiveChatEnabled(true));
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    sender.send(mail);
    verify(composer).compose(mail, "https://tenant.example.test");
    org.mockito.Mockito.clearInvocations(composer, delivery);
    tenant.getSettings().setFeatureAskerEmailEnabled(false);
    sender.send(mail);
    verifyNoInteractions(composer, delivery);
  }

  private static CaseHandoverEmailNotification.Mail mail(
      long tenantId, CaseHandoverEmailNotification.Outcome outcome, String recipient) {
    return new CaseHandoverEmailNotification.Mail(
        12L,
        77L,
        "!room:example.test",
        outcome,
        tenantId,
        recipient,
        LanguageCode.en,
        null,
        outcome == CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED ? "asker-id" : null);
  }
}
