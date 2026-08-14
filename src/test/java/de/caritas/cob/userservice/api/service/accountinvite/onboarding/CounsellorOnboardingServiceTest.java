package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityProfile;
import de.caritas.cob.userservice.api.port.out.IdentityProfileLookup;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteLinkException;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService;
import de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService.ProvisionCounsellorCommand;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService.RegisterCounsellorCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CounsellorOnboardingServiceTest {

  private static final String RAW_TOKEN = "raw-counsellor-token";
  private static final Long TENANT_ID = 21L;
  private static final Long AGENCY_ID = 5L;
  private static final Long DEPARTMENT_TOPIC_ID = 12L;
  private static final Long EXTRA_AGENCY_TOPIC_ID = 13L;
  private static final String CONSULTANT_ID = "consultant-uuid-1";

  @Mock private AccountInviteRepository accountInviteRepository;
  @Mock private AccountInviteService accountInviteService;
  @Mock private CounsellorInviteProvisioningService counsellorInviteProvisioningService;
  @Mock private IdentitySecondFactor identitySecondFactor;
  @Mock private IdentityProfileLookup identityProfileLookup;
  @Mock private AgencyService agencyService;
  @Mock private TopicService topicService;
  @Mock private UsernameTranscoder usernameTranscoder;

  private CounsellorOnboardingService service;

  @BeforeEach
  void setUp() {
    service =
        new CounsellorOnboardingService(
            accountInviteRepository,
            accountInviteService,
            counsellorInviteProvisioningService,
            identitySecondFactor,
            identityProfileLookup,
            agencyService,
            topicService,
            usernameTranscoder);
  }

  private static AccountInvite invite() {
    return AccountInvite.builder()
        .id(8L)
        .targetRole(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(TENANT_ID)
        .agencyId(AGENCY_ID)
        .departmentId(DEPARTMENT_TOPIC_ID)
        .recipientEmail("counsellor@example.org")
        .firstName("Lena")
        .lastName("Beraterin")
        .status(AccountInviteStatus.EMAIL_SENT)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .expiresAt(LocalDateTime.now().plusDays(7))
        .build();
  }

  private void inviteResolves(AccountInvite invite) {
    when(accountInviteService.findInviteByToken(RAW_TOKEN)).thenReturn(invite);
  }

  private void agencyCoverageResolves() {
    lenient()
        .when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(
            new AgencyDTO()
                .id(AGENCY_ID)
                .topicIds(List.of(DEPARTMENT_TOPIC_ID, EXTRA_AGENCY_TOPIC_ID)));
    lenient()
        .when(topicService.getAllActiveTopicsMap())
        .thenReturn(
            Map.of(
                DEPARTMENT_TOPIC_ID,
                new TopicDTO().id(DEPARTMENT_TOPIC_ID).name("Family counselling"),
                EXTRA_AGENCY_TOPIC_ID,
                new TopicDTO().id(EXTRA_AGENCY_TOPIC_ID).name("Debt counselling")));
  }

  private static RegisterCounsellorCommand command() {
    return new RegisterCounsellorCommand(
        "lena.b",
        "s3cretPassword",
        "counsellor_female",
        "Head of counselling centre",
        "Dipl.-Soz.Päd.",
        "Lena",
        "Lena B. (Nord)",
        List.of(DEPARTMENT_TOPIC_ID));
  }

  // --- resolve ---

  @Test
  void resolveOnboardingInvite_deliverableInvite_returnsCoverageFromAgencyAndDepartment() {
    inviteResolves(invite());
    agencyCoverageResolves();

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertFalse(state.pendingTwoFactorResume());
    assertEquals(2, state.topics().size());
    assertEquals(DEPARTMENT_TOPIC_ID, state.topics().get(0).id());
    assertEquals("Family counselling", state.topics().get(0).name());
    assertEquals(EXTRA_AGENCY_TOPIC_ID, state.topics().get(1).id());
  }

  @Test
  void resolveOnboardingInvite_agencyLookupFails_fallsBackToDepartmentTopic() {
    inviteResolves(invite());
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenThrow(new IllegalStateException("agency service down"));
    when(topicService.getAllActiveTopicsMap()).thenReturn(Map.of());

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertEquals(1, state.topics().size());
    assertEquals(DEPARTMENT_TOPIC_ID, state.topics().get(0).id());
    assertNull(state.topics().get(0).name());
  }

  @Test
  void resolveOnboardingInvite_tenantAdminToken_answers404() {
    AccountInvite tenantInvite = invite();
    tenantInvite.setTargetRole(AccountInviteTargetRole.TENANT_ADMIN);
    inviteResolves(tenantInvite);

    assertThrows(NotFoundException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));
  }

  @Test
  void resolveOnboardingInvite_unknownToken_answers404() {
    when(accountInviteService.findInviteByToken(RAW_TOKEN))
        .thenThrow(new NotFoundException("Account invite not found"));

    assertThrows(NotFoundException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));
  }

  @Test
  void resolveOnboardingInvite_expiredInvite_expiresAndAnswersExpired() {
    AccountInvite expired = invite();
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    inviteResolves(expired);

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.EXPIRED, exception.getReason());
    assertEquals(AccountInviteStatus.EXPIRED, expired.getStatus());
    verify(accountInviteRepository).save(expired);
  }

  @Test
  void resolveOnboardingInvite_acceptedWithPendingTwoFactor_resumes() {
    AccountInvite resumable = invite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    resumable.setTotpPendingSecret("STOREDSECRET");
    inviteResolves(resumable);

    var state = service.resolveOnboardingInvite(RAW_TOKEN);

    assertTrue(state.pendingTwoFactorResume());
    assertTrue(state.topics().isEmpty());
  }

  @Test
  void resolveOnboardingInvite_acceptedWithSatisfiedGate_answersConsumed() {
    AccountInvite consumed = invite();
    consumed.setStatus(AccountInviteStatus.ACCEPTED);
    consumed.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    inviteResolves(consumed);

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void resolveOnboardingInvite_revokedInvite_answersRevoked() {
    AccountInvite revoked = invite();
    revoked.setStatus(AccountInviteStatus.REVOKED);
    inviteResolves(revoked);

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.resolveOnboardingInvite(RAW_TOKEN));

    assertEquals(AccountInviteLinkException.Reason.REVOKED, exception.getReason());
  }

  // --- register ---

  @Test
  void registerCounsellor_happyPath_delegatesToProvisioningAndStoresPendingSecret() {
    AccountInvite deliverable = invite();
    inviteResolves(deliverable);
    agencyCoverageResolves();

    AccountInvite accepted = invite();
    accepted.setStatus(AccountInviteStatus.ACCEPTED);
    accepted.setProvisionedUserId(CONSULTANT_ID);
    when(counsellorInviteProvisioningService.acceptInvite(eq(RAW_TOKEN), any()))
        .thenReturn(accepted);
    when(usernameTranscoder.encodeUsername("lena.b")).thenReturn("enc.lena.b");
    when(identitySecondFactor.getOtpCredential("enc.lena.b"))
        .thenReturn(
            new IdentityOtpCredential(false, "TOTPSECRET", "QRBASE64", IdentityOtpType.APP));

    var result = service.registerCounsellor(RAW_TOKEN, command());

    assertEquals(CONSULTANT_ID, result.consultantId());
    assertEquals("TOTPSECRET", result.totpSecret());
    assertEquals("QRBASE64", result.totpQrCodeBase64());
    assertTrue(result.twoFactorRequired());
    assertEquals("TOTPSECRET", accepted.getTotpPendingSecret());
    verify(accountInviteRepository).save(accepted);

    ArgumentCaptor<ProvisionCounsellorCommand> captor =
        ArgumentCaptor.forClass(ProvisionCounsellorCommand.class);
    verify(counsellorInviteProvisioningService).acceptInvite(eq(RAW_TOKEN), captor.capture());
    ProvisionCounsellorCommand provision = captor.getValue();
    assertEquals("lena.b", provision.username());
    assertEquals("s3cretPassword", provision.password());
    assertEquals(Boolean.TRUE, provision.formalLanguage());
    assertNull(provision.acceptedByUserId());
    assertEquals("counsellor_female", provision.salutation());
    assertEquals("Head of counselling centre", provision.position());
    assertEquals("Dipl.-Soz.Päd.", provision.title());
    assertEquals("Lena", provision.displayName());
    assertEquals("Lena B. (Nord)", provision.internalDisplayName());
    assertEquals(List.of(DEPARTMENT_TOPIC_ID), provision.topicIds());
  }

  @Test
  void registerCounsellor_waivedTwoFactorGate_skipsOtpMaterialAndReportsNotRequired() {
    inviteResolves(invite());
    agencyCoverageResolves();

    AccountInvite accepted = invite();
    accepted.setStatus(AccountInviteStatus.ACCEPTED);
    accepted.setProvisionedUserId(CONSULTANT_ID);
    accepted.setTwoFactorStatus(TwoFactorGateStatus.WAIVED);
    when(counsellorInviteProvisioningService.acceptInvite(eq(RAW_TOKEN), any()))
        .thenReturn(accepted);

    var result = service.registerCounsellor(RAW_TOKEN, command());

    assertFalse(result.twoFactorRequired());
    assertNull(result.totpSecret());
    verify(identitySecondFactor, never()).getOtpCredential(anyString());
  }

  @Test
  void registerCounsellor_topicOutsideCoverage_isRejectedBeforeProvisioning() {
    inviteResolves(invite());
    agencyCoverageResolves();

    RegisterCounsellorCommand outside =
        new RegisterCounsellorCommand(
            "lena.b", "s3cretPassword", null, null, null, null, null, List.of(999L));

    assertThrows(BadRequestException.class, () -> service.registerCounsellor(RAW_TOKEN, outside));
    verify(counsellorInviteProvisioningService, never()).acceptInvite(anyString(), any());
  }

  @Test
  void registerCounsellor_missingTopics_isRejected() {
    RegisterCounsellorCommand noTopics =
        new RegisterCounsellorCommand(
            "lena.b", "s3cretPassword", null, null, null, null, null, List.of());

    assertThrows(BadRequestException.class, () -> service.registerCounsellor(RAW_TOKEN, noTopics));
  }

  @Test
  void registerCounsellor_shortPassword_isRejected() {
    RegisterCounsellorCommand shortPassword =
        new RegisterCounsellorCommand(
            "lena.b", "short", null, null, null, null, null, List.of(DEPARTMENT_TOPIC_ID));

    assertThrows(
        BadRequestException.class, () -> service.registerCounsellor(RAW_TOKEN, shortPassword));
  }

  @Test
  void registerCounsellor_consumedInvite_answersLinkDeath() {
    AccountInvite consumed = invite();
    consumed.setStatus(AccountInviteStatus.ACCEPTED);
    consumed.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    inviteResolves(consumed);

    var exception =
        assertThrows(
            AccountInviteLinkException.class,
            () -> service.registerCounsellor(RAW_TOKEN, command()));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
    verify(counsellorInviteProvisioningService, never()).acceptInvite(anyString(), any());
  }

  @Test
  void registerCounsellor_missingOtpMaterial_staysAcceptedAndResumesVerifyOnly() {
    inviteResolves(invite());
    agencyCoverageResolves();

    AccountInvite accepted = invite();
    accepted.setStatus(AccountInviteStatus.ACCEPTED);
    accepted.setProvisionedUserId(CONSULTANT_ID);
    when(counsellorInviteProvisioningService.acceptInvite(eq(RAW_TOKEN), any()))
        .thenReturn(accepted);
    when(usernameTranscoder.encodeUsername("lena.b")).thenReturn("enc.lena.b");
    when(identitySecondFactor.getOtpCredential("enc.lena.b")).thenReturn(null);

    var result = service.registerCounsellor(RAW_TOKEN, command());

    assertEquals(CONSULTANT_ID, result.consultantId());
    assertNull(result.totpSecret());
    assertTrue(result.twoFactorRequired());
    verify(accountInviteRepository, never()).save(accepted);
  }

  // --- two-factor ---

  @Test
  void activateTwoFactor_happyPath_marksGateActiveAndClearsPendingSecret() {
    AccountInvite resumable = invite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    resumable.setAcceptedByUserId(CONSULTANT_ID);
    resumable.setTotpPendingSecret("TOTPSECRET");
    inviteResolves(resumable);
    when(identityProfileLookup.findById(CONSULTANT_ID))
        .thenReturn(
            Optional.of(
                new IdentityProfile(CONSULTANT_ID, "enc.lena.b", "Lena", "Beraterin", "mail")));
    when(identitySecondFactor.setUpOtpCredential("enc.lena.b", "123456", "TOTPSECRET"))
        .thenReturn(true);

    service.activateTwoFactor(RAW_TOKEN, "123456");

    verify(accountInviteService).markTwoFactorActive(CONSULTANT_ID);
    assertNull(resumable.getTotpPendingSecret());
    verify(accountInviteRepository).save(resumable);
  }

  @Test
  void activateTwoFactor_rejectedCode_answers400AndKeepsPendingSecret() {
    AccountInvite resumable = invite();
    resumable.setStatus(AccountInviteStatus.ACCEPTED);
    resumable.setAcceptedByUserId(CONSULTANT_ID);
    resumable.setTotpPendingSecret("TOTPSECRET");
    inviteResolves(resumable);
    when(identityProfileLookup.findById(CONSULTANT_ID))
        .thenReturn(
            Optional.of(
                new IdentityProfile(CONSULTANT_ID, "enc.lena.b", "Lena", "Beraterin", "mail")));
    when(identitySecondFactor.setUpOtpCredential("enc.lena.b", "000000", "TOTPSECRET"))
        .thenReturn(false);

    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "000000"));

    assertEquals("TOTPSECRET", resumable.getTotpPendingSecret());
    verify(accountInviteService, never()).markTwoFactorActive(anyString());
  }

  @Test
  void activateTwoFactor_beforeRegistration_answers400() {
    inviteResolves(invite());

    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));
  }

  @Test
  void activateTwoFactor_satisfiedGate_answersConsumed() {
    AccountInvite done = invite();
    done.setStatus(AccountInviteStatus.ACCEPTED);
    done.setTwoFactorStatus(TwoFactorGateStatus.ACTIVE);
    inviteResolves(done);

    var exception =
        assertThrows(
            AccountInviteLinkException.class, () -> service.activateTwoFactor(RAW_TOKEN, "123456"));

    assertEquals(AccountInviteLinkException.Reason.CONSUMED, exception.getReason());
  }

  @Test
  void activateTwoFactor_blankOtp_answers400() {
    assertThrows(BadRequestException.class, () -> service.activateTwoFactor(RAW_TOKEN, "  "));
  }
}
