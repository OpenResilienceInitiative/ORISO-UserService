package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwner;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.tenant.AsTechnicalUser;
import de.caritas.cob.userservice.api.tenant.TenantFixtures;
import de.caritas.cob.userservice.api.tenant.TenantResolverService;
import de.caritas.cob.userservice.api.tenant.Tenants;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The 409 X-Reason values of the invite API, as the Admin receives them over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TenantFixtures.class)
@AsTechnicalUser
class AccountInviteConflictReasonsIT {

  private static final String CSRF_HEADER = "X-CSRF-TOKEN";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  /** Requests resolve to the Träger the caller acts in. */
  @MockitoBean private TenantResolverService tenantResolverService;

  @Autowired private MockMvc mvc;
  @Autowired private TenantFixtures fixtures;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private InviteEmailTemplateRepository templateRepository;

  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private AgencyIdAllocationClient agencyIdAllocationClient;
  @MockitoBean private TenantService tenantService;
  @MockitoBean private AgencyFacts agencyFacts;
  @MockitoBean private InviteMailDispatchService inviteMailDispatchService;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  private AuthenticatedUser authenticatedUser;

  private Long templateId;

  @BeforeEach
  void setUp() {
    Tenants.actAs(
        authenticatedUser,
        "platform-admin",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
    when(keycloakService.findByEmail(anyString())).thenReturn(Optional.empty());
    templateId =
        templateRepository
            .save(
                InviteEmailTemplate.builder()
                    .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                    .name("conflict-reasons")
                    .language("de")
                    .subject("Einladung")
                    .body("Hallo")
                    .active(true)
                    .createDate(LocalDateTime.now())
                    .build())
            .getId();
  }

  @AfterEach
  void cleanUp() {
    accountInviteRepository.deleteAll();
    templateRepository.deleteById(templateId);
    fixtures.removeAll();
  }

  @Test
  void createInvite_Should_Answer409EmailNotAvailable_When_TheAddressHasAnAccount()
      throws Exception {
    when(keycloakService.findByEmail("taken@example.org"))
        .thenReturn(Optional.of(new IdentityEmailOwner("taken")));

    createInvite("{\"targetRole\":\"COUNSELLOR\",\"recipientEmail\":\"taken@example.org\"}")
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "EMAIL_NOT_AVAILABLE"));
  }

  @Test
  void createInvite_Should_Answer409NoPendingUnitAdmin_When_TheNewAgencyHasNoAdminInvite()
      throws Exception {
    createInvite(counsellorInto(4401L))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "NO_PENDING_UNIT_ADMIN"));
  }

  @Test
  void sendInvite_Should_Answer409UnitNotCreated_While_TheInviteWaitsForItsAgency()
      throws Exception {
    when(agencyIdAllocationClient.reserve(4402L, 1L)).thenReturn(4402L);
    when(agencyIdAllocationClient.getAvailability(anyLong()))
        .thenReturn(IdAllocationStatus.RESERVED);
    createInvite(
            "{\"targetRole\":\"AGENCY_ADMIN\",\"tenantId\":1,\"recipientEmail\":"
                + "\"founder@example.org\",\"agencyId\":4402,\"agencyIdAllocationMode\":\"MANUAL\"}")
        .andExpect(status().isCreated());
    String waiting =
        createInvite(counsellorInto(4402L))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.inviteStatus").value("WAITING_FOR_UNIT"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Number inviteId = JsonPath.read(waiting, "$.id");

    mvc.perform(
            post("/useradmin/account-invites/{id}/send", inviteId.longValue())
                .with(admin())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"templateId\":" + templateId + "}"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "UNIT_NOT_CREATED"));
  }

  @Test
  void selfAssignment_Should_Answer409AlreadyExists_When_TheAdminCounselsThereAlready()
      throws Exception {
    when(agencyFacts.find(1L))
        .thenReturn(Optional.of(new AgencyFacts.Agency(1L, 1L, false, List.of(1L))));

    // A Träger admin who already counsels in agency 1.
    var counsellingAdmin = fixtures.consultant(1L, 1L);
    Tenants.actAs(
        authenticatedUser,
        counsellingAdmin.getId(),
        1L,
        UserRole.TENANT_ADMIN,
        UserRole.USER_ADMIN);

    mvc.perform(
            post("/useradmin/self-assignments")
                .with(admin())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"COUNSELLOR\",\"agencyId\":1}"))
        .andExpect(status().isConflict())
        .andExpect(header().string("X-Reason", "SELF_ASSIGNMENT_ALREADY_EXISTS"));
  }

  private org.springframework.test.web.servlet.ResultActions createInvite(String body)
      throws Exception {
    return mvc.perform(
        post("/useradmin/account-invites")
            .with(admin())
            .cookie(CSRF_COOKIE)
            .header(CSRF_HEADER, CSRF_VALUE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String counsellorInto(long newAgencyId) {
    return "{\"targetRole\":\"COUNSELLOR\",\"tenantId\":1,\"recipientEmail\":\"counsellor-"
        + newAgencyId
        + "@example.org\",\"agencyId\":"
        + newAgencyId
        + ",\"agencyIdAllocationMode\":\"MANUAL\"}";
  }

  /** The caller's identity comes from the mocked AuthenticatedUser; this only passes security. */
  private static JwtRequestPostProcessor admin() {
    return jwt()
        .authorities(
            new SimpleGrantedAuthority(AuthorityValue.USER_ADMIN),
            new SimpleGrantedAuthority(AuthorityValue.TENANT_ADMIN));
  }
}
