package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteUnitType;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Changing the role later (ORISO-Admin#1026): a pending invite changes its role, an account only
 * gains one. Both go through the caller's scope and the "higher invites lower" rule.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@TestPropertySource(properties = "multitenancy.enabled=true")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TenantFixtures.class)
@WithTenant(AccountInviteRoleChangeIT.OWN_TENANT)
class AccountInviteRoleChangeIT {

  static final long OWN_TENANT = 1L;
  private static final long OTHER_TENANT = 2L;
  private static final long AGENCY = 1L;
  private static final long OTHER_AGENCY = 2L;
  private static final long TOPIC = 11L;
  private static final long NEW_AGENCY = 4501L;

  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  private AuthenticatedUser authenticatedUser;

  @Autowired private MockMvc mvc;
  @Autowired private TenantFixtures fixtures;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private AdminRepository adminRepository;
  @Autowired private AdminAgencyRepository adminAgencyRepository;

  @BeforeEach
  void upstreams() {
    actAsTraegerAdmin(OWN_TENANT);
    when(keycloakService.findByEmail(anyString())).thenReturn(Optional.empty());
    when(agencyFacts.find(AGENCY)).thenReturn(Optional.of(agency(AGENCY, OWN_TENANT)));
    when(agencyFacts.find(OTHER_AGENCY)).thenReturn(Optional.of(agency(OTHER_AGENCY, OWN_TENANT)));
    when(agencyIdAllocationClient.getAvailability(AGENCY)).thenReturn(IdAllocationStatus.ASSIGNED);
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
  }

  @AfterEach
  void cleanUp() {
    Tenants.acrossAll(() -> accountInviteRepository.deleteAll());
    fixtures.removeAll();
  }

  // --- a pending invite changes its role --------------------------------------------------------

  @Test
  void changeRole_Should_MakeACounsellorInviteAnAgencyAdminInvite_When_InScope() throws Exception {
    AccountInvite invite = seed(counsellorInvite(OWN_TENANT, AGENCY));

    changeRole(invite, "{\"targetRole\":\"AGENCY_ADMIN\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("AGENCY_ADMIN"))
        .andExpect(jsonPath("$.alsoCounsellor").value(true))
        .andExpect(jsonPath("$.topicPermission").value("CREATE"))
        .andExpect(jsonPath("$.inviteStatus").value("DRAFT"));

    assertThat(reload(invite).getTargetRole()).isEqualTo(AccountInviteTargetRole.AGENCY_ADMIN);
  }

  @Test
  void changeRole_Should_DropAlsoCounsellor_When_AnAgencyAdminInviteBecomesACounsellorInvite()
      throws Exception {
    AccountInvite invite = seed(agencyAdminInvite(AGENCY, IdAllocationMode.EXISTING));

    changeRole(invite, "{\"targetRole\":\"COUNSELLOR\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("COUNSELLOR"))
        .andExpect(jsonPath("$.alsoCounsellor").doesNotExist())
        .andExpect(jsonPath("$.topicPermission").value("SELECT_EXISTING"));

    assertThat(reload(invite).getAlsoCounsellor()).isNull();
  }

  @Test
  void changeRole_Should_Answer403_When_AnAgencyAdminSetsARoleTheyCannotInvite() throws Exception {
    actAsAgencyAdminOf(AGENCY);
    AccountInvite invite = seed(counsellorInvite(OWN_TENANT, AGENCY));

    changeRole(invite, "{\"targetRole\":\"AGENCY_ADMIN\"}").andExpect(status().isForbidden());

    assertThat(reload(invite).getTargetRole()).isEqualTo(AccountInviteTargetRole.COUNSELLOR);
  }

  @Test
  void changeRole_Should_Answer403_When_TheInviteIsOutsideTheCallersAgencies() throws Exception {
    actAsAgencyAdminOf(AGENCY);
    AccountInvite invite = seed(counsellorInvite(OWN_TENANT, OTHER_AGENCY));

    changeRole(invite, "{\"targetRole\":\"COUNSELLOR\"}").andExpect(status().isForbidden());
  }

  @Test
  void changeRole_Should_Answer403_When_TheInviteBelongsToAnotherTraeger() throws Exception {
    AccountInvite invite = seed(counsellorInvite(OWN_TENANT, AGENCY));
    actAsTraegerAdmin(OTHER_TENANT);

    changeRole(invite, "{\"targetRole\":\"AGENCY_ADMIN\"}").andExpect(status().isForbidden());

    assertThat(reload(invite).getTargetRole()).isEqualTo(AccountInviteTargetRole.COUNSELLOR);
  }

  @Test
  void changeRole_Should_Answer409RoleChangeNeedsNewInvite_When_TheRoleMovesToTheTraegerLevel()
      throws Exception {
    AccountInvite invite = seed(counsellorInvite(OWN_TENANT, AGENCY));

    changeRole(invite, "{\"targetRole\":\"TENANT_ADMIN\"}")
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "ROLE_CHANGE_NEEDS_NEW_INVITE"));
  }

  @Test
  void changeRole_Should_Answer409OnlyUnitAdmin_When_CounsellorsWaitForTheOnlyFoundingAdmin()
      throws Exception {
    AccountInvite founder = seed(agencyAdminInvite(NEW_AGENCY, IdAllocationMode.MANUAL));
    seed(waitingCounsellor());

    changeRole(founder, "{\"targetRole\":\"COUNSELLOR\"}")
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "ONLY_UNIT_ADMIN"));

    assertThat(reload(founder).getTargetRole()).isEqualTo(AccountInviteTargetRole.AGENCY_ADMIN);
  }

  @Test
  void changeRole_Should_QueueTheInvite_When_AnotherFoundingAdminRemains() throws Exception {
    seed(agencyAdminInvite(NEW_AGENCY, IdAllocationMode.MANUAL));
    AccountInvite secondFounder = seed(agencyAdminInvite(NEW_AGENCY, IdAllocationMode.MANUAL));

    changeRole(secondFounder, "{\"targetRole\":\"COUNSELLOR\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("COUNSELLOR"))
        .andExpect(jsonPath("$.inviteStatus").value("WAITING_FOR_UNIT"))
        .andExpect(jsonPath("$.waitingForUnit").value("AGENCY"));

    AccountInvite queued = reload(secondFounder);
    assertThat(queued.getExpiresAt()).isNull();
    assertThat(queued.getTokenHash()).isNull();
  }

  @Test
  void changeRole_Should_LeaveTheQueue_When_AWaitingCounsellorBecomesAFoundingAdmin()
      throws Exception {
    seed(agencyAdminInvite(NEW_AGENCY, IdAllocationMode.MANUAL));
    AccountInvite waiting = seed(waitingCounsellor());

    changeRole(waiting, "{\"targetRole\":\"AGENCY_ADMIN\",\"alsoCounsellor\":false}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.targetRole").value("AGENCY_ADMIN"))
        .andExpect(jsonPath("$.alsoCounsellor").value(false))
        .andExpect(jsonPath("$.inviteStatus").value("DRAFT"))
        .andExpect(jsonPath("$.waitingForUnit").doesNotExist());

    assertThat(reload(waiting).getExpiresAt()).isAfter(LocalDateTime.now().plusDays(29));
    // It shares the first founder's reservation instead of taking another number.
    verify(agencyIdAllocationClient, never()).reserve(any(), anyLong());
  }

  @Test
  void acceptedInvite_Should_RefuseTheRoleEdit_And_TheAddRolePathWorks() throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);
    AccountInvite accepted = counsellorInvite(OWN_TENANT, AGENCY);
    accepted.setStatus(AccountInviteStatus.ACCEPTED);
    accepted.setActiveRecipientKey(null);
    accepted.setAcceptedAt(LocalDateTime.now());
    accepted.setAcceptedByUserId(counsellor.getId());
    seed(accepted);

    changeRole(accepted, "{\"targetRole\":\"AGENCY_ADMIN\"}")
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "INVITE_ALREADY_ACCEPTED"));

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isOk());
  }

  // --- an account gains a role ------------------------------------------------------------------

  @Test
  void addRole_Should_MakeTheCounsellorAgencyAdmin_WithTheKeycloakRolesAndTheAgencyRelation()
      throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.consultantId").value(counsellor.getId()))
        .andExpect(jsonPath("$.role").value("AGENCY_ADMIN"))
        .andExpect(jsonPath("$.agencyIds[0]").value(AGENCY));

    verify(keycloakService).updateRole(counsellor.getId(), UserRole.RESTRICTED_AGENCY_ADMIN);
    verify(keycloakService).updateRole(counsellor.getId(), UserRole.USER_ADMIN);
    Admin admin =
        Tenants.acrossAll(() -> adminRepository.findById(counsellor.getId())).orElseThrow();
    assertThat(admin.getType()).isEqualTo(AdminType.AGENCY);
    assertThat(admin.getTenantId()).isEqualTo(OWN_TENANT);
    assertThat(agencyIdsOfAdmin(counsellor.getId())).containsExactly(AGENCY);
  }

  @Test
  void addRole_Should_Answer403_When_TheCounsellorBelongsToAnotherTraeger() throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);
    actAsTraegerAdmin(OTHER_TENANT);

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isForbidden());

    verify(keycloakService, never()).updateRole(eq(counsellor.getId()), any(UserRole.class));
    assertThat(Tenants.acrossAll(() -> adminRepository.findById(counsellor.getId()))).isEmpty();
  }

  @Test
  void addRole_Should_Answer403_When_AnAgencyAdminAddsAnAdminRole() throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);
    actAsAgencyAdminOf(AGENCY);

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isForbidden());

    verify(keycloakService, never()).updateRole(eq(counsellor.getId()), any(UserRole.class));
  }

  @Test
  void addRole_Should_Answer409RoleAlreadyGranted_When_TheCounsellorAdministersTheAgency()
      throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);
    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isOk());

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "ROLE_ALREADY_GRANTED"));
  }

  @Test
  void addRole_Should_Answer400_When_TheCounsellorDoesNotCounselInThatAgency() throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + OTHER_AGENCY + "}")
        .andExpect(status().isBadRequest());

    assertThat(Tenants.acrossAll(() -> adminRepository.findById(counsellor.getId()))).isEmpty();
  }

  @Test
  void addRole_Should_WriteNoAdminRelation_When_KeycloakRefusesTheRole() throws Exception {
    Consultant counsellor = fixtures.consultant(OWN_TENANT, AGENCY);
    doThrow(new IllegalStateException("keycloak down"))
        .when(keycloakService)
        .updateRole(counsellor.getId(), UserRole.RESTRICTED_AGENCY_ADMIN);

    addRole(counsellor, "{\"role\":\"AGENCY_ADMIN\",\"agencyId\":" + AGENCY + "}")
        .andExpect(status().is5xxServerError());

    assertThat(Tenants.acrossAll(() -> adminRepository.findById(counsellor.getId()))).isEmpty();
    assertThat(agencyIdsOfAdmin(counsellor.getId())).isEmpty();
  }

  // --- helpers ----------------------------------------------------------------------------------

  private ResultActions changeRole(AccountInvite invite, String body) throws Exception {
    return mvc.perform(
        put("/useradmin/account-invites/{id}/role", invite.getId())
            .with(admin())
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private ResultActions addRole(Consultant counsellor, String body) throws Exception {
    return mvc.perform(
        post("/useradmin/consultants/{id}/roles", counsellor.getId())
            .with(admin())
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private void actAsTraegerAdmin(long tenantId) {
    Tenants.actAs(
        authenticatedUser,
        "traeger-admin-" + tenantId,
        tenantId,
        UserRole.TENANT_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdminOf(long agencyId) {
    Admin agencyAdmin = fixtures.admin(OWN_TENANT, AdminType.AGENCY, agencyId);
    Tenants.actAs(
        authenticatedUser,
        agencyAdmin.getId(),
        OWN_TENANT,
        UserRole.RESTRICTED_AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private List<Long> agencyIdsOfAdmin(String adminId) {
    return Tenants.acrossAll(
        () ->
            adminAgencyRepository.findByAdminId(adminId).stream()
                .map(AdminAgency::getAgencyId)
                .toList());
  }

  private AccountInvite seed(AccountInvite invite) {
    return Tenants.acrossAll(() -> accountInviteRepository.save(invite));
  }

  private AccountInvite reload(AccountInvite invite) {
    return Tenants.acrossAll(() -> accountInviteRepository.findById(invite.getId())).orElseThrow();
  }

  private static AgencyFacts.Agency agency(long id, long tenantId) {
    return new AgencyFacts.Agency(id, tenantId, false, List.of(TOPIC), TopicPermission.CREATE);
  }

  private static AccountInvite counsellorInvite(long tenantId, long agencyId) {
    return base(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(tenantId)
        .agencyId(agencyId)
        .agencyIdAllocationMode(IdAllocationMode.EXISTING)
        .departmentId(TOPIC)
        .topicPermission(TopicPermission.SELECT_EXISTING)
        .expiresAt(LocalDateTime.now().plusDays(10))
        .build();
  }

  private static AccountInvite agencyAdminInvite(long agencyId, IdAllocationMode agencyMode) {
    return base(AccountInviteTargetRole.AGENCY_ADMIN)
        .tenantId(OWN_TENANT)
        .agencyId(agencyId)
        .agencyIdAllocationMode(agencyMode)
        .alsoCounsellor(true)
        .topicPermission(TopicPermission.CREATE)
        .expiresAt(LocalDateTime.now().plusDays(10))
        .build();
  }

  private static AccountInvite waitingCounsellor() {
    return base(AccountInviteTargetRole.COUNSELLOR)
        .tenantId(OWN_TENANT)
        .agencyId(NEW_AGENCY)
        .agencyIdAllocationMode(IdAllocationMode.MANUAL)
        .topicPermission(TopicPermission.SELECT_EXISTING)
        .status(AccountInviteStatus.WAITING_FOR_UNIT)
        .waitingForUnit(InviteUnitType.AGENCY)
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
        .createdByUserId("inviter")
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now());
  }

  /** The caller's identity comes from the mocked AuthenticatedUser; this only passes security. */
  private static JwtRequestPostProcessor admin() {
    return jwt()
        .authorities(
            new SimpleGrantedAuthority(AuthorityValue.USER_ADMIN),
            new SimpleGrantedAuthority(AuthorityValue.TENANT_ADMIN));
  }
}
