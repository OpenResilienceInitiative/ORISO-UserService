package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.InviteUnitCreatedEvent;
import de.caritas.cob.userservice.api.service.accountinvite.InviteUnitType;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import de.caritas.cob.userservice.api.tenant.WithTenant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The invite tracker (ORISO-Admin#1026): every phase has its timestamp, including when the unit a
 * waiting invite needed was created, and each invite carries one derived progress phase.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@TestPropertySource(properties = "multitenancy.enabled=true")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@WithTenant(AccountInviteProgressIT.OWN_TENANT)
class AccountInviteProgressIT {

  static final long OWN_TENANT = 1L;
  private static final long NEW_AGENCY = 4601L;

  @MockitoBean private TenantResolverService tenantResolverService;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  private AuthenticatedUser authenticatedUser;

  @Autowired private MockMvc mvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailDeliveryRepository deliveryRepository;
  @Autowired private AccountInviteService accountInviteService;
  @Autowired private ApplicationEventPublisher events;

  @BeforeEach
  void setUp() {
    Tenants.actAs(
        authenticatedUser, "traeger-admin", OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.USER_ADMIN);
    when(keycloakService.findByEmail(anyString())).thenReturn(Optional.empty());
    when(agencyIdAllocationClient.getAvailability(NEW_AGENCY))
        .thenReturn(IdAllocationStatus.RESERVED);
  }

  @AfterEach
  void cleanUp() {
    Tenants.acrossAll(
        () -> {
          deliveryRepository.deleteAll();
          accountInviteRepository.deleteAll();
        });
  }

  @Test
  void release_Should_StampUnitCreatedAtExactlyOnce_And_TheListShowsIt() throws Exception {
    seed(
        base(AccountInviteTargetRole.AGENCY_ADMIN)
            .agencyId(NEW_AGENCY)
            .agencyIdAllocationMode(IdAllocationMode.MANUAL)
            .alsoCounsellor(false)
            .expiresAt(LocalDateTime.now().plusDays(5))
            .build());
    AccountInvite waiting =
        seed(
            base(AccountInviteTargetRole.COUNSELLOR)
                .agencyId(NEW_AGENCY)
                .agencyIdAllocationMode(IdAllocationMode.MANUAL)
                .status(AccountInviteStatus.WAITING_FOR_UNIT)
                .waitingForUnit(InviteUnitType.AGENCY)
                .queuedExpiryDays(30L)
                .build());
    assertThat(waiting.getUnitCreatedAt()).isNull();

    events.publishEvent(new InviteUnitCreatedEvent(InviteUnitType.AGENCY, NEW_AGENCY, OWN_TENANT));
    LocalDateTime unitCreatedAt = reload(waiting).getUnitCreatedAt();
    events.publishEvent(new InviteUnitCreatedEvent(InviteUnitType.AGENCY, NEW_AGENCY, OWN_TENANT));

    assertThat(unitCreatedAt).isNotNull();
    assertThat(reload(waiting).getUnitCreatedAt()).isEqualTo(unitCreatedAt);
    DocumentContext list = list("");
    assertThat(seconds(row(list, waiting, "unitCreatedAt"))).isEqualTo(seconds(unitCreatedAt));
    assertThat((String) row(list, waiting, "progressPhase")).isEqualTo("PREPARED");
  }

  @Test
  void list_Should_CarryEveryPhaseTimestamp_And_CountThePhasesAcrossAllPages() throws Exception {
    LocalDateTime sent = LocalDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
    LocalDateTime accepted = LocalDateTime.now().minusDays(2).truncatedTo(ChronoUnit.SECONDS);
    AccountInvite draft = seed(base(AccountInviteTargetRole.COUNSELLOR).build());
    AccountInvite invited = seed(sentInvite());
    delivered(invited, InviteEmailDeliveryStatus.SENT, sent);
    AccountInvite bounced = seed(sentInvite());
    delivered(bounced, InviteEmailDeliveryStatus.FAILED, null);
    AccountInvite accountCreated = seed(acceptedInvite("pending-2fa-user", accepted));
    AccountInvite done = seed(acceptedInvite("done-user", accepted));
    accountInviteService.markTwoFactorActive("done-user");
    seed(base(AccountInviteTargetRole.COUNSELLOR).status(AccountInviteStatus.EXPIRED).build());
    seed(base(AccountInviteTargetRole.COUNSELLOR).status(AccountInviteStatus.REVOKED).build());

    DocumentContext all = list("?size=100");
    assertThat((String) row(all, draft, "progressPhase")).isEqualTo("PREPARED");
    assertThat((String) row(all, invited, "progressPhase")).isEqualTo("INVITED");
    assertThat(seconds(row(all, invited, "sentAt"))).isEqualTo(sent);
    assertThat((String) row(all, bounced, "progressPhase")).isEqualTo("NEEDS_ACTION");
    assertThat((String) row(all, accountCreated, "progressPhase")).isEqualTo("ACCOUNT_CREATED");
    assertThat(seconds(row(all, accountCreated, "accountCreatedAt"))).isEqualTo(accepted);
    assertThat((Object) row(all, accountCreated, "completedAt")).isNull();
    assertThat((String) row(all, done, "progressPhase")).isEqualTo("DONE");
    assertThat((Object) row(all, done, "completedAt")).isNotNull();

    mvc.perform(get("/useradmin/account-invites?size=2").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2))
        .andExpect(jsonPath("$.totalElements").value(7))
        .andExpect(jsonPath("$.phaseCounts.PREPARED").value(1))
        .andExpect(jsonPath("$.phaseCounts.INVITED").value(1))
        .andExpect(jsonPath("$.phaseCounts.ACCOUNT_CREATED").value(1))
        .andExpect(jsonPath("$.phaseCounts.DONE").value(1))
        .andExpect(jsonPath("$.phaseCounts.NEEDS_ACTION").value(2))
        .andExpect(jsonPath("$.phaseCounts.CLOSED").value(1));

    mvc.perform(get("/useradmin/account-invites?progress_phase=NEEDS_ACTION").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[*].progressPhase", everyNeedsAction()))
        .andExpect(jsonPath("$.phaseCounts.PREPARED").value(1));
  }

  private static org.hamcrest.Matcher<Iterable<? extends String>> everyNeedsAction() {
    return org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("NEEDS_ACTION"));
  }

  private DocumentContext list(String query) throws Exception {
    String body =
        mvc.perform(get("/useradmin/account-invites" + query).with(admin()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.parse(body);
  }

  private static <T> T row(DocumentContext list, AccountInvite invite, String field) {
    List<T> values = list.read("$.content[?(@.id == " + invite.getId() + ")]." + field);
    assertThat(values).as("row of invite %s", invite.getId()).hasSize(1);
    return values.get(0);
  }

  private static LocalDateTime seconds(Object value) {
    LocalDateTime time =
        value instanceof LocalDateTime local ? local : LocalDateTime.parse(String.valueOf(value));
    return time.truncatedTo(ChronoUnit.SECONDS);
  }

  private void delivered(
      AccountInvite invite, InviteEmailDeliveryStatus status, LocalDateTime sentAt) {
    Tenants.acrossAll(
        () ->
            deliveryRepository.save(
                InviteEmailDelivery.builder()
                    .accountInviteId(invite.getId())
                    .templateKind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .subjectSnapshot("Einladung")
                    .bodySnapshot("Hallo")
                    .recipientSnapshot(invite.getRecipientEmail())
                    .status(status)
                    .sentAt(sentAt)
                    .createDate(LocalDateTime.now())
                    .build()));
  }

  private AccountInvite seed(AccountInvite invite) {
    return Tenants.acrossAll(() -> accountInviteRepository.save(invite));
  }

  private AccountInvite reload(AccountInvite invite) {
    return Tenants.acrossAll(() -> accountInviteRepository.findById(invite.getId())).orElseThrow();
  }

  private static AccountInvite sentInvite() {
    return base(AccountInviteTargetRole.COUNSELLOR)
        .status(AccountInviteStatus.EMAIL_SENT)
        .tokenHash(AccountInviteService.hash(UUID.randomUUID().toString()))
        .expiresAt(LocalDateTime.now().plusDays(20))
        .build();
  }

  private static AccountInvite acceptedInvite(String userId, LocalDateTime acceptedAt) {
    return base(AccountInviteTargetRole.COUNSELLOR)
        .status(AccountInviteStatus.ACCEPTED)
        .activeRecipientKey(null)
        .acceptedAt(acceptedAt)
        .acceptedByUserId(userId)
        .emailVerificationStatus(EmailVerificationStatus.VERIFIED)
        .build();
  }

  private static AccountInvite.AccountInviteBuilder base(AccountInviteTargetRole role) {
    String email = role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.org";
    return AccountInvite.builder()
        .targetRole(role)
        .tenantId(OWN_TENANT)
        .agencyId(1L)
        .recipientEmail(email)
        .activeRecipientKey(email)
        .status(AccountInviteStatus.DRAFT)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now());
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      admin() {
    return jwt()
        .authorities(
            new SimpleGrantedAuthority(AuthorityValue.USER_ADMIN),
            new SimpleGrantedAuthority(AuthorityValue.TENANT_ADMIN));
  }
}
