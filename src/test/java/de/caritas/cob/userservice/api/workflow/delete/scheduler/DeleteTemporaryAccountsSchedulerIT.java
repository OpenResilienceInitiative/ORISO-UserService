package de.caritas.cob.userservice.api.workflow.delete.scheduler;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.ConsultingTypeServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.MailServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.TopicServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ScheduledTaskClaimRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.consultingtype.ApplicationSettingsService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.api.testHelper.ChatRecoveryPolicyFixtures;
import de.caritas.cob.userservice.api.workflow.delete.service.AnonymousUserDeletionUnit;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.ConsultingTypeControllerApi;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.BasicConsultingTypeResponseDTO;
import de.caritas.cob.userservice.mailservice.generated.web.MailsControllerApi;
import de.caritas.cob.userservice.topicservice.generated.ApiClient;
import de.caritas.cob.userservice.topicservice.generated.web.TopicControllerApi;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import lombok.SneakyThrows;
import org.apache.commons.lang3.RandomStringUtils;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriTemplateHandler;

/**
 * A person who joins a self-help group "without an account" (FE#1499, 0a) gets a real account that
 * nobody can log into again once the browser is closed. These tests register such a person through
 * the public registration endpoint and then run the cleanup job, the way it runs in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {
      "feature.topics.enabled=true",
      "keycloak.realm=test",
      "identity.openid-connect-url=http://localhost:8080/auth/realms/test/protocol/openid-connect"
    })
class DeleteTemporaryAccountsSchedulerIT {

  private static final String TASK_NAME = "temporary-account-deletion";
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);
  private static final String MATRIX_USER_ID = "@temporary-participant:matrix.oriso.org";

  @Autowired private DeleteTemporaryAccountsScheduler scheduler;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private UserService userService;
  @Autowired private ScheduledTaskClaimRepository claimRepository;
  @Autowired private ChatRepository chatRepository;
  @Autowired private UserChatRepository userChatRepository;
  @Autowired private ConsultantRepository consultantRepository;
  @Autowired private ChatService chatService;
  @Autowired private AnonymousUserDeletionUnit accountRemover;

  private static final String SEEDED_CONSULTANT_ID = "0b3b1cc6-be98-4787-aa56-212259d811b9";
  private static final EasyRandom easyRandom = new EasyRandom();
  private final List<Long> groupsToRemove = new ArrayList<>();
  private final List<String> registeredUserIds = new ArrayList<>();

  @Value("${user.temporary.deleteWorkflow.maxAge}")
  private Duration maxAge;

  @MockitoBean private TenantService tenantService;
  @MockitoBean private MatrixSynapseService matrixSynapseService;
  @MockitoBean private Keycloak keycloak;
  @MockitoSpyBean private KeycloakService identityAccounts;
  @MockitoBean private AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;
  @MockitoBean private ConsultingTypeControllerApi consultingTypeControllerApi;

  @MockitoBean
  private ConsultingTypeServiceApiControllerFactory consultingTypeServiceApiControllerFactory;

  @MockitoBean private TopicServiceApiControllerFactory topicServiceApiControllerFactory;
  @MockitoBean private TopicControllerApi topicControllerApi;
  @MockitoBean private MailServiceApiControllerFactory mailServiceApiControllerFactory;
  @MockitoBean private MailsControllerApi mailsControllerApi;
  @MockitoBean private ApplicationSettingsService applicationSettingsService;

  @MockitoBean
  @Qualifier("restTemplate")
  private RestTemplate restTemplate;

  @BeforeEach
  void setUp() throws Exception {
    deleteSchedulerClaim();
    when(tenantService.getRestrictedTenantDataFresh(anyLong()))
        .thenReturn(ChatRecoveryPolicyFixtures.tenant());
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(ChatRecoveryPolicyFixtures.tenant());

    var matrixUser = new MatrixCreateUserResponseDTO();
    matrixUser.setUserId(MATRIX_USER_ID);
    when(matrixSynapseService.createUser(anyString(), anyString(), anyString()))
        .thenReturn(ResponseEntity.ok(matrixUser));
    when(matrixSynapseService.deactivateUser(anyString())).thenReturn(true);

    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TestAgencyControllerApi(
                new de.caritas.cob.userservice.agencyserivce.generated.ApiClient()) {
              @Override
              public List<
                      de.caritas.cob.userservice.agencyserivce.generated.web.model
                          .AgencyResponseDTO>
                  getAgenciesByIds(List<Long> ids) {
                var agencies = super.getAgenciesByIds(ids);
                agencies.forEach(agency -> agency.setTenantId(1L));
                return agencies;
              }
            });
    givenTheConsultingTypeService();
    when(topicServiceApiControllerFactory.createControllerApi()).thenReturn(topicControllerApi);
    when(topicControllerApi.getApiClient()).thenReturn(new ApiClient());
    when(topicControllerApi.getAllTopics())
        .thenReturn(
            List.of(new TopicDTO().id(1L).name("topic").status("ACTIVE").internalIdentifier("t1")));
    when(mailServiceApiControllerFactory.createControllerApi()).thenReturn(mailsControllerApi);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
    // Accounts a test kept on purpose must not leak into other ITs sharing this database.
    registeredUserIds.forEach(accountRemover::deleteUser);
    registeredUserIds.clear();
    groupsToRemove.forEach(
        id -> {
          chatRepository
              .findById(id)
              .ifPresent(chat -> userChatRepository.deleteAll(userChatRepository.findByChat(chat)));
          chatRepository.deleteById(id);
        });
    groupsToRemove.clear();
    deleteSchedulerClaim();
  }

  @Test
  void aTemporaryParticipantIsDeletedOnceTheirLongestPossibleLoginHasEnded() throws Exception {
    var participant = register(true);
    ageBy(participant, maxAge.plusMinutes(1));

    scheduler.performDeletionWorkflow();

    assertFalse(userService.getUser(participant.getUserId()).isPresent());
    assertTrue(sessionRepository.findByUserUserId(participant.getUserId()).isEmpty());
    verify(identityAccounts).deleteUser(participant.getUserId());
    verify(matrixSynapseService).deactivateUser(eq(MATRIX_USER_ID));
  }

  @Test
  void aTemporaryParticipantIsKeptWhileTheirLoginCouldStillBeAlive() throws Exception {
    var participant = register(true);
    ageBy(participant, maxAge.minusMinutes(1));

    scheduler.performDeletionWorkflow();

    assertTrue(userService.getUser(participant.getUserId()).isPresent());
    verify(identityAccounts, never()).deleteUser(participant.getUserId());
  }

  @Test
  void anAccountRegisteredTheOrdinaryWayIsNeverDeletedByThisJob() throws Exception {
    var member = register(false);
    ageBy(member, maxAge.multipliedBy(30));

    scheduler.performDeletionWorkflow();

    assertTrue(userService.getUser(member.getUserId()).isPresent());
    verify(identityAccounts, never()).deleteUser(member.getUserId());
  }

  @Test
  void theGroupAndItsRoomOutliveTheTemporaryParticipant() throws Exception {
    var participant = register(true);
    var group = givenAGroupWithRoom("!self-help-group:matrix.oriso.org");
    chatService.saveUserChatRelation(UserChat.builder().user(participant).chat(group).build());
    ageBy(participant, maxAge.plusMinutes(1));

    scheduler.performDeletionWorkflow();

    assertFalse(userService.getUser(participant.getUserId()).isPresent());
    assertTrue(chatRepository.existsById(group.getId()));
    verify(matrixSynapseService, never()).purgeRoom("!self-help-group:matrix.oriso.org");
  }

  private User register(boolean temporary) throws Exception {
    TenantContext.setCurrentTenant(1L);
    var email = RandomStringUtils.randomAlphabetic(8) + "@example.com";
    var userDTO = new UserDTO();
    userDTO.setUsername(RandomStringUtils.randomAlphabetic(8, 20));
    userDTO.setPassword("s3cret-Passw0rd!");
    userDTO.setPostcode("00000");
    userDTO.setAgencyId(1L);
    userDTO.setAge("17");
    userDTO.setState("8");
    userDTO.setTermsAccepted("true");
    userDTO.setConsultingType("1");
    userDTO.setEmail(email);
    userDTO.setMainTopicId(1L);
    userDTO.setTopicIds(List.of(1L));
    userDTO.setTemporary(temporary);

    mockMvc
        .perform(
            post("/users/askers/new")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(userDTO)))
        .andExpect(status().isCreated());

    var registered =
        StreamSupport.stream(userRepository.findAll().spliterator(), false)
            .filter(user -> email.equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    registeredUserIds.add(registered.getUserId());
    return registered;
  }

  /**
   * Moves the account's creation back in time; the clock is the only thing a test can't wait on.
   */
  private void ageBy(User user, Duration age) {
    var stored = userRepository.findById(user.getUserId()).orElseThrow();
    stored.setCreateDate(LocalDateTime.now().minus(age));
    userRepository.save(stored);
  }

  private Chat givenAGroupWithRoom(String matrixRoomId) {
    var chat = easyRandom.nextObject(Chat.class);
    chat.setId(null);
    chat.setActive(true);
    chat.setRepetitive(true);
    chat.setChatOwner(consultantRepository.findById(SEEDED_CONSULTANT_ID).orElseThrow());
    chat.setConsultingTypeId(1);
    chat.setDuration(90);
    chat.setMaxParticipants(10);
    chat.setSourceLanguage("de");
    chat.setMatrixRoomId(matrixRoomId);
    chat.setUpdateDate(LocalDateTime.now());
    chat = chatRepository.save(chat);
    groupsToRemove.add(chat.getId());
    return chat;
  }

  private void deleteSchedulerClaim() {
    claimRepository.findById(TASK_NAME).ifPresent(claimRepository::delete);
  }

  private void givenTheConsultingTypeService() {
    when(consultingTypeControllerApi.getApiClient())
        .thenReturn(
            new de.caritas.cob.userservice.consultingtypeservice.generated.ApiClient(restTemplate));
    when(consultingTypeServiceApiControllerFactory.createControllerApi())
        .thenReturn(consultingTypeControllerApi);
    when(restTemplate.getUriTemplateHandler())
        .thenReturn(
            new UriTemplateHandler() {
              @SneakyThrows
              @Override
              public @NonNull URI expand(
                  @NonNull String uriTemplate, @NonNull Map<String, ?> uriVariables) {
                return new URI("");
              }

              @SneakyThrows
              @Override
              public @NonNull URI expand(
                  @NonNull String uriTemplate, Object @NonNull ... uriVariables) {
                return new URI("");
              }
            });
    var body = new BasicConsultingTypeResponseDTO();
    body.setId(1);
    ParameterizedTypeReference<List<BasicConsultingTypeResponseDTO>> type =
        new ParameterizedTypeReference<>() {};
    when(restTemplate.exchange(any(RequestEntity.class), eq(type)))
        .thenReturn(ResponseEntity.ok(List.of(body)));
  }
}
