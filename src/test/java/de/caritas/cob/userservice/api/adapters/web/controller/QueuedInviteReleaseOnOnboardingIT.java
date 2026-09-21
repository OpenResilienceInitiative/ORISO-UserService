package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.InviteUnitType;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.AgencyCreationClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient.OperatorDpa;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The trigger of the queue (ORISO-Admin#1026, slice 5): when a unit's first admin finishes
 * onboarding and the unit exists, the invites waiting for it are released automatically — sent with
 * their template, or turned into a DRAFT without one. Several admins for the same new Träger: the
 * first creates it, the others join it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class QueuedInviteReleaseOnOnboardingIT {

  private static final long TENANT = 79L;
  private static final long NEW_AGENCY = 1276L;
  private static final long NEW_TENANT = 4242L;
  private static final String ADMIN_ID = "c0a1e5e5-1026-4a4a-9d1e-000000000005";
  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailTemplateRepository templateRepository;
  @Autowired private InviteEmailDeliveryRepository deliveryRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;

  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private AgencyCreationClient agencyCreationClient;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;
  @MockitoBean private TenantCreationClient tenantCreationClient;
  @MockitoBean private OperatorDpaContentClient operatorDpaContentClient;

  private Long templateId;

  @BeforeEach
  void upstreams() {
    when(agencyService.getAgencyWithoutCaching(NEW_AGENCY)).thenReturn(null);
    when(keycloakService.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("technical-access-token", 60, 60, "refresh"));
    when(keycloakService.createUser(any(UserDTO.class), anyString(), anyString()))
        .thenReturn(new CreatedIdentity(ADMIN_ID));
    when(keycloakService.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(false, "SECRET", "QR", IdentityOtpType.APP));
    when(inviteMailDispatchService.send(
            anyString(), anyString(), anyString(), anyString(), any(), any()))
        .thenAnswer(call -> new InviteMailSendReceipt(call.getArgument(0), Instant.now()));
    when(operatorDpaContentClient.fetchPublishedDpa())
        .thenReturn(new OperatorDpa("{\"de\":\"<p>AVV</p>\"}", "1"));
    when(tenantCreationClient.createTenant(any()))
        .thenReturn(new MultilingualTenantDTO().id(NEW_TENANT));
    when(agencyIdAllocationClient.reserve(null, NEW_TENANT)).thenReturn(1777L);
    templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .name("queue-release-it")
                    .language("de")
                    .subject("Einladung")
                    .body("Hallo {{firstName}}")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
  }

  @AfterEach
  void cleanUp() {
    deliveryRepository.deleteAll();
    accountInviteRepository.deleteAll();
    templateRepository.deleteById(templateId);
    adminAgencyRepository.deleteAll(adminAgencyRepository.findByAdminId(ADMIN_ID));
    adminRepository.findById(ADMIN_ID).ifPresent(adminRepository::delete);
  }

  @Test
  void agencyAdminFinishingOnboarding_Should_SendTheInvitesWaitingForTheNewAgency()
      throws Exception {
    String adminToken = seedSentInvite(agencyAdminInvite());
    AccountInvite waiting = seed(waitingCounsellor(templateId));
    AccountInvite waitingDraft = seed(waitingCounsellor(null));

    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", adminToken)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    { "account": { "username": "queue_admin", "password": "Valid-Test-Password-2026!" },
                      "alsoCounsellor": false, "agency": { "name": "Neue Beratungsstelle" } }
                    """))
        .andExpect(status().isOk());

    AccountInvite sent = reload(waiting);
    assertThat(sent.getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(sent.getWaitingForUnit()).isNull();
    assertThat(sent.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    verify(inviteMailDispatchService)
        .send(eq(sent.getRecipientEmail()), anyString(), anyString(), anyString(), any(), any());
    assertThat(reload(waitingDraft).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
  }

  @Test
  void tenantAdminFinishingOnboarding_Should_ReleaseTheAgencyAdminsWaitingForTheNewTenant()
      throws Exception {
    String adminToken = seedSentInvite(newTenantAdminInvite("reservation-4242"));
    AccountInvite waitingAgencyAdmin = seed(waitingAgencyAdminForTheTenant());

    registerNewTenant(adminToken, "reservation-4242").andExpect(status().isOk());

    AccountInvite released = reload(waitingAgencyAdmin);
    assertThat(released.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(released.getAgencyId()).isEqualTo(1777L);
    verify(agencyIdAllocationClient).reserve(null, NEW_TENANT);
  }

  @Test
  void secondTenantAdmin_Should_JoinTheTenantTheFirstOneCreated() throws Exception {
    AccountInvite first = newTenantAdminInvite("shared-token");
    first.setStatus(AccountInviteStatus.ACCEPTED);
    first.setActiveRecipientKey(null);
    seed(first);
    String secondToken = seedSentInvite(newTenantAdminInvite("shared-token"));

    mockMvc
        .perform(get("/users/account-invites/{token}/onboarding", secondToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.joinsExistingTenant").value(true));
    mockMvc
        .perform(
            post("/users/account-invites/{token}/onboarding/register", secondToken)
                .header("X-CSRF-Token", CSRF)
                .cookie(CSRF_COOKIE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"account\": { \"password\": \"Valid-Test-Password-2026!\" } }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tenantId").value(NEW_TENANT));

    verify(tenantCreationClient, never()).createTenant(any());
    verify(agencyIdAllocationClient, never()).reserve(any(), anyLong());
  }

  private org.springframework.test.web.servlet.ResultActions registerNewTenant(
      String token, String reservationToken) throws Exception {
    return mockMvc.perform(
        post("/users/account-invites/{token}/onboarding/register", token)
            .header("X-CSRF-Token", CSRF)
            .cookie(CSRF_COOKIE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{ \"organisation\": { \"name\": \"Neuer Träger\" },"
                    + " \"dpa\": { \"accepted\": true },"
                    + " \"account\": { \"password\": \"Valid-Test-Password-2026!\" },"
                    + " \"reservedTenantId\": "
                    + NEW_TENANT
                    + ", \"tenantIdReservationToken\": \""
                    + reservationToken
                    + "\" }"));
  }

  private AccountInvite reload(AccountInvite invite) {
    return accountInviteRepository.findById(invite.getId()).orElseThrow();
  }

  private AccountInvite seed(AccountInvite invite) {
    return accountInviteRepository.save(invite);
  }

  private String seedSentInvite(AccountInvite invite) {
    String token = "queue-release-" + UUID.randomUUID();
    invite.setTokenHash(AccountInviteService.hash(token));
    invite.setStatus(AccountInviteStatus.EMAIL_SENT);
    invite.setExpiresAt(LocalDateTime.now().plusDays(1));
    seed(invite);
    return token;
  }

  private static AccountInvite agencyAdminInvite() {
    return base(AccountInviteTargetRole.AGENCY_ADMIN)
        .tenantId(TENANT)
        .agencyId(NEW_AGENCY)
        .agencyIdAllocationMode(IdAllocationMode.MANUAL)
        .alsoCounsellor(false)
        .build();
  }

  private static AccountInvite waitingCounsellor(Long templateId) {
    return base(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(TENANT)
        .agencyId(NEW_AGENCY)
        .agencyIdAllocationMode(IdAllocationMode.MANUAL)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.AGENCY)
        .queuedTemplateId(templateId)
        .queuedExpiryDays(30L)
        .build();
  }

  private static AccountInvite newTenantAdminInvite(String reservationToken) {
    return base(AccountInviteTargetRole.TENANT_ADMIN)
        .tenantId(NEW_TENANT)
        .tenantIdAllocationMode(IdAllocationMode.MANUAL)
        .tenantIdReservationToken(reservationToken)
        .build();
  }

  private static AccountInvite waitingAgencyAdminForTheTenant() {
    return base(AccountInviteTargetRole.AGENCY_ADMIN)
        .tenantId(NEW_TENANT)
        .tenantIdAllocationMode(IdAllocationMode.MANUAL)
        .agencyIdAllocationMode(IdAllocationMode.AUTO)
        .alsoCounsellor(true)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.TENANT)
        .queuedExpiryDays(30L)
        .build();
  }

  private static AccountInvite.AccountInviteBuilder base(AccountInviteTargetRole role) {
    String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.org";
    return AccountInvite.builder()
        .targetRole(role)
        .recipientEmail(email)
        .activeRecipientKey(email)
        .firstName("Ada")
        .lastName("Lovelace")
        .status(AccountInviteStatus.DRAFT)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createDate(LocalDateTime.now());
  }
}
