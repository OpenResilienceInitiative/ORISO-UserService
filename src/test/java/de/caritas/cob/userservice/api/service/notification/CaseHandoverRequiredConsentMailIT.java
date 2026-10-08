package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.CaseHandoverPolicyCacheService;
import de.caritas.cob.userservice.api.service.CaseHandoverService;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggle;
import de.caritas.cob.userservice.api.service.consultingtype.ReleaseToggleService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.BooleanPermissionPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverConsentValue;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.ConsentPermissionPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTextPermissionPolicy;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.PermissionPolicyMode;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("testing")
@TestPropertySource(properties = {"multitenancy.enabled=true", "feature.topics.enabled=false"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CaseHandoverRequiredConsentMailIT {
  @Autowired private CaseHandoverService handovers;
  @Autowired private CaseHandoverRequestRepository requests;
  @Autowired private SessionRepository sessions;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository agencies;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private EventNotificationService feed;
  @Autowired private CaseHandoverGrantedMailEligibility grantEligibility;
  @Autowired private CaseHandoverRequiredConsentMailEligibility consentEligibility;
  @Autowired private AccountInactivityService lifecycle;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private AccountInactivityEffects accountEffects;
  private final TenantSystemEmailDelivery delivery = mock(TenantSystemEmailDelivery.class);
  @MockitoBean private CaseHandoverMailSender queuedMail;
  @MockitoBean private CaseHandoverPolicyCacheService policies;
  @MockitoBean private UserAccountService actor;
  @MockitoBean private ReleaseToggleService toggles;
  @MockitoBean private MatrixFeedUpdateSignalService feedSignal;

  private User seeker;
  private Consultant previous;
  private Consultant incoming;
  private Session session;
  private ConsultantAgency membership;
  private User replacementSeeker;

  @BeforeEach
  void currentAuthorizedCase() {
    TenantContext.setCurrentTenant(7L);
    seeker =
        users.save(
            User.builder()
                .userId(UUID.randomUUID().toString())
                .username("consent-seeker-" + UUID.randomUUID())
                .email("seeker@example.test")
                .languageCode(LanguageCode.en)
                .encourage2fa(false)
                .magicLinkLoginEnabled(false)
                .notificationsEnabled(false)
                .notificationsSettings("{\"reassignmentNotificationEnabled\":false}")
                .tenantId(7L)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());
    previous = consultant("previous");
    incoming = consultant("incoming");
    membership =
        agencies.save(
            ConsultantAgency.builder().consultant(incoming).agencyId(10L).tenantId(7L).build());
    session =
        sessions.save(
            Session.builder()
                .user(seeker)
                .consultant(previous)
                .agencyId(10L)
                .tenantId(7L)
                .consultingTypeId(1)
                .languageCode(LanguageCode.en)
                .registrationType(Session.RegistrationType.REGISTERED)
                .postcode("12345")
                .status(Session.SessionStatus.IN_PROGRESS)
                .isConsultantDirectlySet(false)
                .createDate(LocalDateTime.now())
                .updateDate(LocalDateTime.now())
                .build());
    when(actor.retrieveValidatedConsultant()).thenReturn(incoming);
    when(toggles.isToggleEnabled(ReleaseToggle.NEW_EMAIL_NOTIFICATIONS)).thenReturn(true);
    when(policies.getEffective(7L)).thenReturn(policy(CaseHandoverConsentValue.OPT_IN));
  }

  @AfterEach
  void removeOnlyLocalFixture() {
    TenantContext.setCurrentTenant(0L);
    try {
      if (seeker != null) {
        feed.clearFeed(seeker.getUserId());
        jdbc.update(
            "DELETE FROM account_inactivity_journal WHERE identity_id=?", seeker.getUserId());
        jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", seeker.getUserId());
      }
      if (incoming != null) feed.clearFeed(incoming.getId());
      if (session != null) {
        requests.deleteAll(requests.findBySessionId(session.getId()));
        sessions.deleteById(session.getId());
      }
      if (membership != null) agencies.delete(membership);
      if (seeker != null) users.delete(seeker);
      if (incoming != null) consultants.delete(incoming);
      if (previous != null) consultants.delete(previous);
      if (replacementSeeker != null) users.delete(replacementSeeker);
    } finally {
      TenantContext.clear();
    }
  }

  @Test
  void requiredPersonalConsentQueuesNeutralActionAfterCommitDespiteOptionalMailOptOut() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var outcome =
                  handovers.requestAccess(
                      session.getId(), "UNPLANNED_ABSENCE", "Staff-only absence explanation");
              assertThat(outcome.getStatus()).isEqualTo("PENDING_CLIENT_CONSENT");
              verifyNoInteractions(queuedMail);
            });
    var sent = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(queuedMail).send(sent.capture());
    assertThat(sent.getValue().outcome())
        .isEqualTo(CaseHandoverEmailNotification.Outcome.CONSENT_REQUESTED);
    assertThat(sent.getValue().recipient()).isEqualTo("seeker@example.test");
    assertThat(sent.getValue().sessionId()).isEqualTo(session.getId());
  }

  @ParameterizedTest
  @EnumSource(
      value = CaseHandoverConsentValue.class,
      names = {"OPT_OUT", "NONE"})
  void alreadyPermittedTakeoverSendsOnlyTheOptionalIncomingConsultantMail(
      CaseHandoverConsentValue mode) {
    when(policies.getEffective(7L)).thenReturn(policy(mode));
    incoming.setNotificationsEnabled(true);
    incoming.setNotificationsSettings("{\"reassignmentNotificationEnabled\":true}");
    consultants.save(incoming);

    var result =
        handovers.requestAccess(session.getId(), "UNPLANNED_ABSENCE", "Staff-only explanation");

    assertThat(result.getStatus())
        .isEqualTo(
            mode == CaseHandoverConsentValue.NONE ? "GRANTED" : "GRANTED_PENDING_CLIENT_OPTOUT");
    var sent = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(queuedMail).send(sent.capture());
    assertThat(sent.getValue().outcome()).isEqualTo(CaseHandoverEmailNotification.Outcome.GRANTED);
    assertThat(sent.getValue().recipient()).isEqualTo(incoming.getEmail());
  }

  @ParameterizedTest
  @EnumSource(CurrentLifecycle.class)
  void currentRequiredConsentStillDeliversItsSnapshotDespiteOptionalPreferences(
      CurrentLifecycle state) {
    var mail = queueRequiredConsent();
    assertThat(mail.recipientUserId()).isEqualTo(seeker.getUserId());
    if (state == CurrentLifecycle.ACTIVE) {
      lifecycle.assignAtCreation(seeker.getUserId(), 7L, 30, 0L, Instant.now());
      assertThat(lifecycle.snapshot(seeker.getUserId()).orElseThrow().status())
          .isEqualTo(AccountInactivityService.Status.ACTIVE);
    }
    session.setStatus(Session.SessionStatus.DONE);
    sessions.save(session);
    delayedSender().send(mail);
    verify(delivery)
        .sendConfirmed(
            eq(7L),
            any(),
            eq(TenantSystemEmailDelivery.Purpose.HANDOVER_REQUESTED),
            eq("seeker@example.test"),
            any());
  }

  @Test
  void aLegacySnapshotWithoutOriginalSeekerIdentityCannotDeliverRequiredConsent() {
    var mail = queueRequiredConsent();
    delayedSender()
        .send(
            new CaseHandoverEmailNotification.Mail(
                mail.requestId(),
                mail.sessionId(),
                mail.matrixRoomId(),
                mail.outcome(),
                mail.tenantId(),
                mail.recipient(),
                mail.language(),
                mail.dialect()));
    verifyNoInteractions(delivery);
  }

  private enum CurrentLifecycle {
    ACTIVE,
    LEGACY
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(longs = {8L})
  void changedSeekerTenantAfterCommitCannotReceiveTheQueuedConsentRequest(Long changedTenant) {
    var mail = queueRequiredConsent();
    seeker.setTenantId(changedTenant);
    users.save(seeker);
    assertThat(users.findByUserIdAndDeleteDateIsNull(seeker.getUserId())).isEmpty();
    delayedSender().send(mail);
    verifyNoInteractions(delivery);
  }

  @Test
  void changedSeekerAddressAfterCommitCannotReceiveOrRedirectTheQueuedConsentRequest() {
    var mail = queueRequiredConsent();
    seeker.setEmail("changed@example.test");
    users.save(seeker);

    delayedSender().send(mail);

    verifyNoInteractions(delivery);
  }

  @ParameterizedTest
  @EnumSource(InvalidConsent.class)
  void queuedConsentMustStillBeRequiredForItsCurrentAuthorizedSeeker(InvalidConsent change) {
    var mail = queueRequiredConsent();
    var request = requests.findById(mail.requestId()).orElseThrow();
    switch (change) {
      case GRANTED -> {
        request.setStatus(CaseHandoverRequest.Status.GRANTED);
        requests.save(request);
      }
      case DECLINED -> {
        request.setStatus(CaseHandoverRequest.Status.CLIENT_CONSENT_DECLINED);
        requests.save(request);
      }
      case DENIED -> {
        request.setStatus(CaseHandoverRequest.Status.DENIED);
        requests.save(request);
      }
      case EXPIRED -> {
        request.setStatus(CaseHandoverRequest.Status.EXPIRED);
        requests.save(request);
      }
      case OPT_OUT -> {
        request.setClientConsent(CaseHandoverConsentMode.OPT_OUT);
        requests.save(request);
      }
      case NONE -> {
        request.setClientConsent(CaseHandoverConsentMode.NONE);
        requests.save(request);
      }
      case CO_ACCESS -> {
        request.setAccessType(CaseHandoverRequest.AccessType.CO_ACCESS);
        requests.save(request);
      }
      case DELETED_SEEKER -> {
        seeker.setDeleteDate(LocalDateTime.now());
        users.save(seeker);
      }
      case SUSPENDED_SEEKER -> {
        lifecycle.assignAtCreation(seeker.getUserId(), 7L, 12, 1L, Instant.now());
        when(accountEffects.suspend(seeker.getUserId())).thenReturn(true);
        assertThat(lifecycle.suspend(seeker.getUserId())).isTrue();
        assertThat(lifecycle.snapshot(seeker.getUserId()))
            .hasValueSatisfying(
                state ->
                    assertThat(state.status())
                        .isEqualTo(AccountInactivityService.Status.SUSPENDED));
      }
      case OTHER_SEEKER_SAME_EMAIL -> {
        replacementSeeker =
            users.save(
                User.builder()
                    .userId(UUID.randomUUID().toString())
                    .username("replacement-seeker-" + UUID.randomUUID())
                    .email(seeker.getEmail())
                    .languageCode(LanguageCode.en)
                    .encourage2fa(false)
                    .magicLinkLoginEnabled(false)
                    .tenantId(7L)
                    .createDate(LocalDateTime.now())
                    .updateDate(LocalDateTime.now())
                    .build());
        session.setUser(replacementSeeker);
        sessions.save(session);
      }
      case REQUEST_TENANT -> {
        request.setTenantId(8L);
        requests.save(request);
      }
      case SESSION_TENANT -> {
        session.setTenantId(8L);
        sessions.save(session);
      }
      case ROOM -> {
        session.setMatrixRoomId("!changed:example.test");
        sessions.save(session);
      }
      case OWNER_REPLACED -> {
        session.setConsultant(incoming);
        sessions.save(session);
      }
      case REMOVED_REQUEST -> requests.delete(request);
    }

    delayedSender().send(mail);

    verifyNoInteractions(delivery);
  }

  private enum InvalidConsent {
    GRANTED,
    DECLINED,
    DENIED,
    EXPIRED,
    OPT_OUT,
    NONE,
    CO_ACCESS,
    DELETED_SEEKER,
    SUSPENDED_SEEKER,
    OTHER_SEEKER_SAME_EMAIL,
    REQUEST_TENANT,
    SESSION_TENANT,
    ROOM,
    OWNER_REPLACED,
    REMOVED_REQUEST
  }

  private CaseHandoverEmailNotification.Mail queueRequiredConsent() {
    handovers.requestAccess(session.getId(), "UNPLANNED_ABSENCE", "Staff-only explanation");
    var queued = ArgumentCaptor.forClass(CaseHandoverEmailNotification.Mail.class);
    verify(queuedMail).send(queued.capture());
    return queued.getValue();
  }

  private CaseHandoverMailSender delayedSender() {
    var routes = mock(TenantSystemEmailRouteService.class);
    var tenants = mock(TenantService.class);
    var urls = mock(TenantTemplateSupplier.class);
    var composer = mock(CaseHandoverMailComposer.class);
    var tenant = new RestrictedTenantDTO().id(7L);
    when(routes.resolve(7L))
        .thenReturn(
            Optional.of(
                new TenantSystemEmailRouteService.Route(
                    TenantSystemEmailRouteService.Mode.PLATFORM, null)));
    when(tenants.getRestrictedTenantData(7L)).thenReturn(tenant);
    when(urls.getTenantBaseUrl(tenant)).thenReturn("https://tenant.example.test");
    when(composer.compose(any(), any()))
        .thenReturn(
            new OrisoEmailRenderer.RenderedEmail(
                "Notice", "<p>Protected consent</p>", "Protected consent"));
    return new CaseHandoverMailSender(
        routes, delivery, tenants, urls, composer, grantEligibility, consentEligibility);
  }

  private Consultant consultant(String label) {
    return consultants.save(
        Consultant.builder()
            .id(UUID.randomUUID().toString())
            .username("consent-" + label + "-" + UUID.randomUUID())
            .firstName(label)
            .lastName("Fixture")
            .email(label + "@example.test")
            .languageCode(LanguageCode.en)
            .tenantId(7L)
            .encourage2fa(false)
            .magicLinkLoginEnabled(false)
            .notifyEnquiriesRepeating(false)
            .notifyNewChatMessageFromAdviceSeeker(false)
            .status(ConsultantStatus.CREATED)
            .build());
  }

  private CaseHandoverPolicies policy(CaseHandoverConsentValue consent) {
    var mode = PermissionPolicyMode.ENFORCED;
    var enabled = new BooleanPermissionPolicy(null).value(true).mode(mode);
    var reason =
        new CaseHandoverReasonPolicy()
            .code(CaseHandoverReasonPolicy.CodeEnum.COUNSELLOR_IS_ILL)
            .labels(
                new MultilingualTextPermissionPolicy(null)
                    .value(Map.of("en", "Unplanned absence"))
                    .mode(mode))
            .enabled(enabled)
            .accessAllowed(enabled)
            .clientConsent(new ConsentPermissionPolicy(null).value(consent).mode(mode))
            .clientConsentRequired(
                new BooleanPermissionPolicy(null)
                    .value(consent == CaseHandoverConsentValue.OPT_IN)
                    .mode(mode));
    return new CaseHandoverPolicies().reasons(Map.of("COUNSELLOR_IS_ILL", reason));
  }
}
