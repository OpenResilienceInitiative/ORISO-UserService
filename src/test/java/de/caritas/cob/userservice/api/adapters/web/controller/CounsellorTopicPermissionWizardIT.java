package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.identity.IdentityOtpType;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.EmailVerificationStatus;
import de.caritas.cob.userservice.api.service.accountinvite.TwoFactorGateStatus;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class CounsellorTopicPermissionWizardIT {

  /** Seeded row: the mocked admin facade hands it back to receive the invite's permission. */
  private static final String SEEDED_CONSULTANT_ID = "0b3b1cc6-be98-4787-aa56-212259d811b9";

  private static final Long AGENCY_ID = 1026L;
  private static final Long AGENCY_TOPIC_A = 2L;
  private static final Long AGENCY_TOPIC_B = 7L;

  /** Active Träger topic the agency does not offer. */
  private static final Long OTHER_TENANT_TOPIC = 9L;

  private static final String CSRF = "it-csrf-token";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF);

  @Autowired private MockMvc mockMvc;
  @Autowired private AccountInviteRepository accountInviteRepository;
  @Autowired private ConsultantRepository consultantRepository;

  @MockitoBean private ConsultantAdminFacade consultantAdminFacade;
  @MockitoBean private KeycloakService keycloakService;
  @MockitoBean private AgencyService agencyService;
  @MockitoBean private TopicService topicService;

  @BeforeEach
  void upstreams() {
    agencyOffers(AGENCY_TOPIC_A, AGENCY_TOPIC_B);
    when(topicService.getAllActiveTopicsMap())
        .thenReturn(
            Map.of(
                AGENCY_TOPIC_A, topic(AGENCY_TOPIC_A, "Schulden"),
                AGENCY_TOPIC_B, topic(AGENCY_TOPIC_B, "Sucht"),
                OTHER_TENANT_TOPIC, topic(OTHER_TENANT_TOPIC, "Migration")));
    when(consultantAdminFacade.createNewConsultant(any(CreateConsultantDTO.class)))
        .thenReturn(
            new ConsultantAdminResponseDTO()
                .embedded(new ConsultantDTO().id(SEEDED_CONSULTANT_ID)));
    when(keycloakService.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("technical-access-token", 60, 60, "refresh"));
    when(keycloakService.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(false, "SECRET", "QR", IdentityOtpType.APP));
  }

  @AfterEach
  void resetSeededConsultant() {
    consultantRepository
        .findById(SEEDED_CONSULTANT_ID)
        .ifPresent(
            consultant -> {
              consultant.setTopicPermission(TopicPermission.CREATE);
              consultantRepository.save(consultant);
            });
  }

  // --- resolve -----------------------------------------------------------------------------------

  @Test
  void resolve_create_offersTheAgencyTopicsPlusEveryTraegerTopic() throws Exception {
    String token = seedInvite(TopicPermission.CREATE, null);

    resolve(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicPermission").value("CREATE"))
        .andExpect(jsonPath("$.topics.length()").value(2))
        .andExpect(jsonPath("$.availableTopics.length()").value(3));
  }

  @Test
  void resolve_selectExisting_offersOnlyTheAgencyTopics() throws Exception {
    String token = seedInvite(TopicPermission.SELECT_EXISTING, null);

    resolve(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicPermission").value("SELECT_EXISTING"))
        .andExpect(jsonPath("$.topics[0].id").value(AGENCY_TOPIC_A))
        .andExpect(jsonPath("$.topics[1].id").value(AGENCY_TOPIC_B))
        .andExpect(jsonPath("$.topics.length()").value(2))
        .andExpect(jsonPath("$.availableTopics.length()").value(0));
  }

  @Test
  void resolve_none_offersOnlyTheAssignedDepartment() throws Exception {
    String token = seedInvite(TopicPermission.NONE, AGENCY_TOPIC_B);

    resolve(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicPermission").value("NONE"))
        .andExpect(jsonPath("$.topics[0].id").value(AGENCY_TOPIC_B))
        .andExpect(jsonPath("$.topics.length()").value(1))
        .andExpect(jsonPath("$.availableTopics.length()").value(0));
  }

  @Test
  void resolve_noneWithoutAssignedDepartment_offersTheAgencyTopicsToPickOneFrom() throws Exception {
    String token = seedInvite(TopicPermission.NONE, null);

    resolve(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.topicPermission").value("NONE"))
        .andExpect(jsonPath("$.topics.length()").value(2))
        .andExpect(jsonPath("$.availableTopics.length()").value(0));
  }

  // --- register ----------------------------------------------------------------------------------

  @Test
  void register_selectExisting_rejectsATopicTheAgencyDoesNotOffer() throws Exception {
    String token = seedInvite(TopicPermission.SELECT_EXISTING, null);

    register(token, "[" + OTHER_TENANT_TOPIC + "]").andExpect(status().isBadRequest());

    assertThat(inviteOf(token).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_none_rejectsAnotherAgencyTopicThanTheAssignedOne() throws Exception {
    String token = seedInvite(TopicPermission.NONE, AGENCY_TOPIC_B);

    register(token, "[" + AGENCY_TOPIC_A + "]").andExpect(status().isBadRequest());

    assertThat(inviteOf(token).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_none_withZeroTopics_getsTheAssignedDepartment() throws Exception {
    String token = seedInvite(TopicPermission.NONE, AGENCY_TOPIC_B);

    register(token, "[]").andExpect(status().isOk());

    assertThat(counsellor().getTopicPermission()).isEqualTo(TopicPermission.NONE);
  }

  @Test
  void register_noneWithoutAssignedDepartment_acceptsExactlyOneAgencyTopic() throws Exception {
    String twoTopics = seedInvite(TopicPermission.NONE, null);
    register(twoTopics, "[" + AGENCY_TOPIC_A + ", " + AGENCY_TOPIC_B + "]")
        .andExpect(status().isBadRequest());

    String oneTopic = seedInvite(TopicPermission.NONE, null);
    register(oneTopic, "[" + AGENCY_TOPIC_B + "]").andExpect(status().isOk());
  }

  @Test
  void register_create_acceptsAnotherTraegerTopic() throws Exception {
    String token = seedInvite(TopicPermission.CREATE, null);

    register(token, "[" + AGENCY_TOPIC_A + ", " + OTHER_TENANT_TOPIC + "]")
        .andExpect(status().isOk());

    assertThat(counsellor().getTopicPermission()).isEqualTo(TopicPermission.CREATE);
  }

  @Test
  void register_selectExisting_acceptsAnAgencyTopicAndCarriesThePermissionToTheCounsellor()
      throws Exception {
    String token = seedInvite(TopicPermission.SELECT_EXISTING, null);

    register(token, "[" + AGENCY_TOPIC_B + "]").andExpect(status().isOk());

    assertThat(counsellor().getTopicPermission()).isEqualTo(TopicPermission.SELECT_EXISTING);
  }

  @Test
  void register_withZeroTopics_isRejectedWhenTheAgencyOffersSeveral() throws Exception {
    String token = seedInvite(TopicPermission.SELECT_EXISTING, null);

    register(token, "[]").andExpect(status().isBadRequest());

    assertThat(inviteOf(token).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void register_withZeroTopics_getsTheOnlyAgencyTopicWhenThereIsExactlyOne() throws Exception {
    agencyOffers(AGENCY_TOPIC_A);
    String token = seedInvite(TopicPermission.SELECT_EXISTING, null);

    register(token, "[]").andExpect(status().isOk());
  }

  // --- helpers -----------------------------------------------------------------------------------

  private void agencyOffers(Long... topicIds) {
    when(agencyService.getAgencyWithoutCaching(AGENCY_ID))
        .thenReturn(new AgencyDTO().id(AGENCY_ID).topicIds(List.of(topicIds)));
  }

  private static TopicDTO topic(Long id, String name) {
    return new TopicDTO().id(id).name(name);
  }

  private ResultActions resolve(String token) throws Exception {
    return mockMvc.perform(get("/users/account-invites/{token}/onboarding", token));
  }

  private ResultActions register(String token, String topicIdsJson) throws Exception {
    return mockMvc.perform(
        post("/users/account-invites/{token}/onboarding/register", token)
            .header("X-CSRF-Token", CSRF)
            .cookie(CSRF_COOKIE)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "account": { "username": "topic_permission_%s", "password": "Valid-Test-Password-2026!" },
                  "topicIds": %s
                }
                """
                    .formatted(UUID.randomUUID().toString().substring(0, 8), topicIdsJson)));
  }

  private AccountInvite inviteOf(String token) throws Exception {
    String hash = sha256(token);
    return accountInviteRepository.findAll().stream()
        .filter(invite -> hash.equals(invite.getTokenHash()))
        .findFirst()
        .orElseThrow();
  }

  private Consultant counsellor() {
    return consultantRepository.findById(SEEDED_CONSULTANT_ID).orElseThrow();
  }

  private String seedInvite(TopicPermission topicPermission, Long departmentId) throws Exception {
    String token = "topic-permission-token-" + UUID.randomUUID();
    accountInviteRepository.save(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(79L)
            .recipientEmail("topic.permission." + UUID.randomUUID() + "@oriso.org")
            .firstName("Lisa")
            .lastName("Simpson")
            .agencyId(AGENCY_ID)
            .departmentId(departmentId)
            .tokenHash(sha256(token))
            .expiresAt(LocalDateTime.now().plusDays(1))
            .status(AccountInviteStatus.EMAIL_SENT)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .topicPermission(topicPermission)
            .createDate(LocalDateTime.now())
            .build());
    return token;
  }

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }
}
