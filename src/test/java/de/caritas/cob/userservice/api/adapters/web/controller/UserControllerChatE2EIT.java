package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.testHelper.RequestBodyConstants.VALID_CREATE_CHAT_BODY_WITH_AGENCY_PLACEHOLDER;
import static de.caritas.cob.userservice.api.testHelper.RequestBodyConstants.VALID_CREATE_CHAT_V1_BODY;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_CREDENTIALS_SYSTEM_A;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_CREDENTIALS_TECHNICAL_A;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_TOKEN;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_TOKEN_HEADER_PARAMETER_NAME;
import static java.util.Objects.nonNull;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDeleteResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.message.MessageResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomResponse;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.RocketChatUserDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.E2eKeyDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.session.SessionTopicEnrichmentService;
import de.caritas.cob.userservice.api.testConfig.TestAgencyControllerApi;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantSettings;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.RandomStringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(properties = "rocket-chat.enabled=true")
@ExtendWith({OutputCaptureExtension.class, MockitoExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserControllerChatE2EIT {

  private static final EasyRandom easyRandom = new EasyRandom();
  private static final String CSRF_HEADER = "X-CSRF-Token";
  private static final String CSRF_VALUE = "test";
  private static final Cookie CSRF_COOKIE = new Cookie("CSRF-TOKEN", CSRF_VALUE);

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @Autowired private UsernameTranscoder usernameTranscoder;

  @Autowired private ConsultantRepository consultantRepository;

  @Autowired private ConsultantAgencyRepository consultantAgencyRepository;

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRepository chatRepository;

  @Autowired private ChatAgencyRepository chatAgencyRepository;

  @Autowired private UserChatRepository chatUserRepository;

  @Autowired private UserAgencyRepository userAgencyRepository;

  @Autowired private VideoChatConfig videoChatConfig;

  @Autowired private IdentityConfig identityConfig;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @MockitoBean private RocketChatCredentialsProvider rocketChatCredentialsProvider;

  @MockitoBean private AgencyServiceApiControllerFactory agencyServiceApiControllerFactory;

  @MockitoBean private SessionTopicEnrichmentService sessionTopicEnrichmentService;

  @MockitoBean private TenantService tenantService;

  @Autowired private CacheManager cacheManager;

  @MockitoBean
  @Qualifier("restTemplate")
  private RestTemplate restTemplate;

  @MockitoBean
  @Qualifier("rocketChatRestTemplate")
  private RestTemplate rocketChatRestTemplate;

  @MockitoBean private MongoClient mockedMongoClient;

  @Mock private MongoDatabase mongoDatabase;

  @Mock private MongoCollection<Document> mongoCollection;

  @Mock private MongoCursor<Document> mongoCursor;

  @Mock private FindIterable<Document> findIterable;

  private User user;
  private Consultant consultant;
  private E2eKeyDTO e2eKeyDTO;
  private Chat chat;
  private ChatAgency chatAgency;
  private UserAgency userAgency;
  private UserInfoResponseDTO userInfoResponse;
  private GroupDeleteResponseDTO groupDeleteResponse;
  private SubscriptionsGetDTO subscriptionsGetResponse;

  @AfterEach
  void reset() {
    if (!TestTransaction.isActive()) {
      if (nonNull(user)) {
        user.setDeleteDate(null);
        userRepository.save(user);
      }
      if (nonNull(chat) && chatRepository.existsById(chat.getId())) {
        chatRepository.deleteById(chat.getId());
      }
      if (nonNull(chatAgency) && chatAgencyRepository.existsById(chatAgency.getId())) {
        chatAgencyRepository.deleteById(chatAgency.getId());
      }
      if (nonNull(userAgency) && userAgencyRepository.existsById(userAgency.getId())) {
        userAgencyRepository.deleteById(userAgency.getId());
      }
    }
    user = null;
    consultant = null;
    chat = null;
    chatAgency = null;
    userAgency = null;
    videoChatConfig.setE2eEncryptionEnabled(false);
    userInfoResponse = null;
    subscriptionsGetResponse = null;
    groupDeleteResponse = null;
    identityConfig.setDisplayNameAllowedForConsultants(false);
    cacheManager.getCache("rocketChatUserCache").clear();
  }

  @BeforeEach
  public void setUp() {
    when(agencyServiceApiControllerFactory.createControllerApi())
        .thenReturn(
            new TestAgencyControllerApi(
                new de.caritas.cob.userservice.agencyserivce.generated.ApiClient()));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CREATE_NEW_CHAT)
  @Transactional
  void createChatV1_Should_ReturnCreated_When_ChatWasCreated() throws Exception {
    givenAValidConsultant(true);
    givenAValidRocketChatSystemUser();

    mockMvc
        .perform(
            post("/users/chat/new")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_CREATE_CHAT_V1_BODY)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("groupId", is("rcGroupId")));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CREATE_NEW_CHAT)
  @Transactional
  void createChatV2_Should_ReturnCreated_When_ChatWasCreated() throws Exception {
    givenAValidConsultant(true);
    givenGroupChatFeatureEnabled();
    givenAValidRocketChatSystemUser();

    ConsultantAgency consultantAgency =
        consultant.getConsultantAgencies().stream().findFirst().orElseThrow();
    mockMvc
        .perform(
            post("/users/chat/v2/new")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(giveValidCreateChatBodyWithAgency(consultantAgency))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("groupId", is("rcGroupId")));
  }

  private String giveValidCreateChatBodyWithAgency(ConsultantAgency consultantAgency) {
    return VALID_CREATE_CHAT_BODY_WITH_AGENCY_PLACEHOLDER.replace(
        "${AGENCY_ID}", consultantAgency.getAgencyId().toString());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void updateChat_Should_PersistScheduleFieldsToDatabase_When_OwnerEditsInactiveSeries()
      throws Exception {
    // The owner is the mocked authenticatedUser; updateChat authorizes on chatOwner id.
    givenAValidConsultant(true);

    // Seed an INACTIVE repetitive series with a known "before" schedule, straight into H2 so
    // the assertions below reload from the database rather than a mocked service result.
    var beforeStart = LocalDateTime.of(2026, 1, 5, 18, 0);
    var seededChat = easyRandom.nextObject(Chat.class);
    seededChat.setId(null);
    seededChat.setGroupId(RC_GROUP_ID);
    seededChat.setMatrixRoomId(null);
    seededChat.setActive(false); // updateChat rejects active chats with 409
    seededChat.setChatOwner(consultant);
    seededChat.setConsultingTypeId(easyRandom.nextInt(128));
    seededChat.setMaxParticipants(easyRandom.nextInt(128));
    seededChat.setUpdateDate(CustomLocalDateTime.nowInUtc());
    seededChat.setSourceLanguage("de");
    seededChat.setRepetitive(true);
    seededChat.setRepeatCount(12);
    seededChat.setChatInterval(Chat.ChatInterval.WEEKLY);
    seededChat.setChatModality(Chat.ChatModality.TEXT);
    seededChat.setTimezone("Europe/Berlin");
    seededChat.setStartDate(beforeStart);
    seededChat.setInitialStartDate(beforeStart);
    seededChat.setCurrentOccurrenceIndex(5); // non-zero, must reset to 0 on re-anchor
    chat = chatRepository.save(seededChat);

    // "After" schedule: every field changed so a no-op update would fail every assertion.
    var newStartDate = LocalDate.of(2026, 3, 2);
    var newStartTime = LocalTime.of(20, 30);
    var body = new ChatDTO();
    body.setTopic("Edited self-help series");
    body.setStartDate(newStartDate);
    body.setStartTime(newStartTime);
    body.setDuration(90);
    body.setRepetitive(true);
    body.setRepeatCount(6); // 12 -> 6
    body.setChatInterval(Chat.ChatInterval.MONTHLY); // WEEKLY -> MONTHLY
    body.setModality(Chat.ChatModality.VIDEO); // TEXT -> VIDEO
    body.setTimezone("UTC"); // Europe/Berlin -> UTC

    mockMvc
        .perform(
            put("/users/chat/{chatId}/update", chat.getId())
                .with(jwt().authorities(new SimpleGrantedAuthority(AuthorityValue.UPDATE_CHAT)))
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("groupId", is(chat.getGroupId())));

    var persisted = chatRepository.findById(chat.getId()).orElseThrow();
    assertEquals(6, persisted.getRepeatCount());
    assertTrue(persisted.isRepetitive());
    assertEquals(Chat.ChatInterval.MONTHLY, persisted.getChatInterval());
    assertEquals(Chat.ChatModality.VIDEO, persisted.getChatModality());
    assertEquals("UTC", persisted.getTimezone());
    assertEquals(LocalDateTime.of(newStartDate, newStartTime), persisted.getStartDate());
    assertEquals(LocalDateTime.of(newStartDate, newStartTime), persisted.getInitialStartDate());
    assertEquals(0, persisted.getCurrentOccurrenceIndex());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnClientErrorIfUserIdHasInvalidFormat() throws Exception {
    var invalidUserId = RandomStringUtils.randomAlphabetic(16);

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", invalidUserId, aPositiveLong())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnClientErrorIfChatIdHasInvalidFormat() throws Exception {
    givenAValidUser();
    var invalidChatId = RandomStringUtils.randomAlphabetic(16);

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", user.getRcUserId(), invalidChatId)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().is4xxClientError());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnBadRequestIfRcTokenIsNotGiven() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", user.getRcUserId(), chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnNotFoundIfUserDoesNotExist() throws Exception {
    var nonExistingUserId = RandomStringUtils.randomAlphanumeric(17);
    givenAValidConsultant(true);
    givenAValidChat(false);

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", nonExistingUserId, chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnNotFoundIfChatDoesNotExist() throws Exception {
    givenAValidUser();

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", user.getRcUserId(), aPositiveLong())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnNoContentIfBanWentWell() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatMuteUserInRoomResponse();

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", user.getRcUserId(), chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNoContent());

    var urlSuffix = "/api/v1/method.call/muteUserInRoom";
    verify(rocketChatRestTemplate)
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(MessageResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.UPDATE_CHAT)
  void banFromChatShouldReturnNotFoundIfRocketChatReturnsAnInvalidResponse() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAnInvalidRocketChatMuteUserInRoomResponse();

    mockMvc
        .perform(
            post("/users/{chatUserId}/chat/{chatId}/ban", user.getRcUserId(), chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());

    var urlSuffix = "/api/v1/method.call/muteUserInRoom";
    verify(rocketChatRestTemplate)
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(MessageResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void getChatShouldReturnOkIfUsersAreBannedAndAUserRequested() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);

    mockMvc
        .perform(
            get("/users/chat/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("groupId", is(chat.getGroupId())))
        .andExpect(jsonPath("bannedUsers[0]", isA(String.class)));

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void getChatShouldReturnOkIfUsersAreNotBannedAndAUserRequested() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), false);

    mockMvc
        .perform(
            get("/users/chat/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("groupId", is(chat.getGroupId())))
        .andExpect(jsonPath("bannedUsers", is(empty())));

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void getChatShouldReturnOkIfUsersAreBannedAndAConsultantRequested() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);

    mockMvc
        .perform(
            get("/users/chat/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("groupId", is(chat.getGroupId())))
        .andExpect(jsonPath("bannedUsers[0]", isA(String.class)));

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void getChatShouldReturnOkIfUsersAreNotBannedAndAConsultantRequested() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), false);

    mockMvc
        .perform(
            get("/users/chat/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("groupId", is(chat.getGroupId())))
        .andExpect(jsonPath("bannedUsers", is(empty())));

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  void assignChat_Should_ReturnUnauthorized_When_UserIsMissing() throws Exception {
    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", "xyz")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void assignChat_Should_ReturnNotFound_When_ChatIsNotFound() throws Exception {
    givenAValidUser(true);

    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", RC_GROUP_ID)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void assignChat_Should_ReturnOK() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);

    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", RC_GROUP_ID)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    UserChat storedChatUser = chatUserRepository.findByChatAndUser(chat, user).orElseThrow();
    assertEquals(storedChatUser.getChat(), chat);
    assertEquals(storedChatUser.getUser(), user);

    chatUserRepository.delete(storedChatUser);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  @Transactional
  void assignChat_Should_ReturnOK_When_GroupIdIsAMatrixRoomId() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    var matrixRoomId = "!OxvwFBFfkXNNhHUBOk:matrix.localhost";
    givenAValidChat(false, matrixRoomId);

    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", matrixRoomId)
                .with(jwt().authorities(new SimpleGrantedAuthority(AuthorityValue.USER_DEFAULT)))
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var storedChatUser = chatUserRepository.findByChatAndUser(chat, user).orElseThrow();
    chatUserRepository.delete(storedChatUser);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void assignChat_Should_ReturnConflict_When_UserIsAlreadyAssigned() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);

    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", RC_GROUP_ID)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    UserChat storedChatUser = chatUserRepository.findByChatAndUser(chat, user).orElseThrow();
    assertEquals(storedChatUser.getChat(), chat);
    assertEquals(storedChatUser.getUser(), user);

    mockMvc
        .perform(
            put("/users/chat/{groupId}/assign", RC_GROUP_ID)
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isConflict());

    chatUserRepository.delete(storedChatUser);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void leaveLegacyRocketChatShouldKeepOneTimeChatWhenMatrixMembershipIsUnknown(
      CapturedOutput logOutput) throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenValidRocketChatTechUserResponse();
    givenAnOnlyTechUserRocketChatGroupMemberResponse();
    givenAValidRocketChatInfoUserResponse();
    givenAValidRocketChatGroupDeleteResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/leave", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    assertTrue(chatRepository.existsById(chat.getId()));
    assertTrue(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate, never())
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));
    verifyRocketChatUserRemovedFromGroup(logOutput, chat.getGroupId(), user.getRcUserId());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void leaveChatShouldReturnOkAndNotDeleteOneTimeChatOrChatAgencyIfNotLastUser(
      CapturedOutput logOutput) throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenValidRocketChatTechUserResponse();
    var chatUserId = RandomStringUtils.randomAlphanumeric(17);
    givenAPositiveRocketChatGroupMemberResponse(chatUserId);
    givenAValidRocketChatInfoUserResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/leave", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    assertTrue(chatRepository.existsById(chat.getId()));
    assertTrue(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate, never())
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));
    verifyRocketChatUserRemovedFromGroup(logOutput, chat.getGroupId(), user.getRcUserId());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void leaveLegacyRocketChatShouldKeepRepetitiveChatWhenMatrixMembershipIsUnknown(
      CapturedOutput logOutput) throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(true);
    givenAValidRocketChatSystemUser();
    givenValidRocketChatTechUserResponse();
    givenAnOnlyTechUserRocketChatGroupMemberResponse();
    givenAValidRocketChatInfoUserResponse();
    givenAValidRocketChatGroupDeleteResponse();

    var allChatsBefore =
        StreamSupport.stream(chatRepository.findAll().spliterator(), false)
            .collect(Collectors.toSet());

    mockMvc
        .perform(
            put("/users/chat/{chatId}/leave", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var allChatsAfter =
        StreamSupport.stream(chatRepository.findAll().spliterator(), false)
            .sorted(Comparator.comparing(Chat::getUpdateDate).reversed())
            .collect(Collectors.toList());

    assertEquals(allChatsBefore.size(), allChatsAfter.size());
    assertTrue(allChatsBefore.containsAll(allChatsAfter));

    assertTrue(chatRepository.existsById(chat.getId()));
    assertTrue(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate, never())
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));
    verifyRocketChatUserRemovedFromGroup(logOutput, chat.getGroupId(), user.getRcUserId());

    var chatAfter = chatRepository.findById(chat.getId()).orElseThrow();

    assertEquals(chat.getConsultingTypeId(), chatAfter.getConsultingTypeId());
    assertEquals(
        chat.getInitialStartDate().truncatedTo(ChronoUnit.SECONDS),
        chatAfter.getInitialStartDate().truncatedTo(ChronoUnit.SECONDS));
    assertEquals(
        chat.getStartDate().truncatedTo(ChronoUnit.SECONDS),
        chatAfter.getStartDate().truncatedTo(ChronoUnit.SECONDS));
    assertEquals(chat.getDuration(), chatAfter.getDuration());
    assertEquals(chat.isRepetitive(), chatAfter.isRepetitive());
    assertEquals(chat.getChatInterval(), chatAfter.getChatInterval());
    assertTrue(chatAfter.isActive());
    assertEquals(chat.getMaxParticipants(), chatAfter.getMaxParticipants());
    assertEquals(chat.getGroupId(), chatAfter.getGroupId());
    assertEquals(chat.getChatOwner(), chatAfter.getChatOwner());
    assertPersistedTimestampEquals(chat.getUpdateDate(), chatAfter.getUpdateDate());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.USER_DEFAULT)
  void leaveChatShouldReturnOkAndNotDeleteRepetitiveChatOrChatAgencyIfNotLastUser(
      CapturedOutput logOutput) throws Exception {
    givenAValidUser(true);
    givenAValidConsultant();
    givenAValidChat(true);
    givenAValidRocketChatSystemUser();
    givenValidRocketChatTechUserResponse();
    var chatUserId = RandomStringUtils.randomAlphanumeric(17);
    givenAPositiveRocketChatGroupMemberResponse(chatUserId);
    givenAValidRocketChatInfoUserResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/leave", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    assertTrue(chatRepository.existsById(chat.getId()));
    assertTrue(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate, never())
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));
    verifyRocketChatUserRemovedFromGroup(logOutput, chat.getGroupId(), user.getRcUserId());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithNoContent() throws Exception {
    givenAValidConsultant(true);
    givenACorrectlyFormattedE2eKeyDTO();
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatInfoUserResponse();
    var subscriptionSize = easyRandom.nextInt(4) + 1;
    givenAValidRocketChatGetSubscriptionsResponse(subscriptionSize, true);
    givenValidRocketChatGroupKeyUpdateResponses();

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isNoContent());

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(UserInfoResponseDTO.class));

    urlSuffix = "/api/v1/subscriptions.get";
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(SubscriptionsGetDTO.class));

    urlSuffix = "/api/v1/e2e.updateGroupKey";
    verify(rocketChatRestTemplate, times(subscriptionSize))
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithNoContentOnEmptySubscriptions() throws Exception {
    givenAValidConsultant(true);
    givenACorrectlyFormattedE2eKeyDTO();
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatInfoUserResponse();
    givenAValidRocketChatGetSubscriptionsResponse(0, true);

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isNoContent());

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(UserInfoResponseDTO.class));

    urlSuffix = "/api/v1/subscriptions.get";
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(SubscriptionsGetDTO.class));

    urlSuffix = "/api/v1/e2e.updateGroupKey";
    verify(rocketChatRestTemplate, never())
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithNoContentOnNoE2eKeySubscriptions() throws Exception {
    givenAValidConsultant(true);
    givenACorrectlyFormattedE2eKeyDTO();
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatInfoUserResponse();
    givenAValidRocketChatGetSubscriptionsResponse(easyRandom.nextInt(4) + 1, false);

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isNoContent());

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(UserInfoResponseDTO.class));

    urlSuffix = "/api/v1/subscriptions.get";
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(SubscriptionsGetDTO.class));

    urlSuffix = "/api/v1/e2e.updateGroupKey";
    verify(rocketChatRestTemplate, never())
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithNoContentIfNotTemporarilyEncrypted() throws Exception {
    givenAValidConsultant(true);
    givenACorrectlyFormattedE2eKeyDTO();
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatInfoUserResponse();
    givenARocketChatGetSubscriptionsResponseIncludingNoneTemporary();

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isNoContent());

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(UserInfoResponseDTO.class));

    urlSuffix = "/api/v1/subscriptions.get";
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(SubscriptionsGetDTO.class));

    urlSuffix = "/api/v1/e2e.updateGroupKey";
    verify(rocketChatRestTemplate, never())
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithInternalServerErrorIfSubscriptionUpdateFailed()
      throws Exception {
    givenAValidConsultant(true);
    givenACorrectlyFormattedE2eKeyDTO();
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatInfoUserResponse();
    givenAValidRocketChatGetSubscriptionsResponse(easyRandom.nextInt(4) + 1, true);
    givenFailedRocketChatGroupKeyUpdateResponses();

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header("rcToken", RandomStringUtils.randomAlphabetic(16))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isInternalServerError());

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(UserInfoResponseDTO.class));

    urlSuffix = "/api/v1/subscriptions.get";
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(SubscriptionsGetDTO.class));

    urlSuffix = "/api/v1/e2e.updateGroupKey";
    verify(rocketChatRestTemplate)
        .postForEntity(endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithBadRequestIfE2eKeyHasWrongFormat() throws Exception {
    givenAValidConsultant(true);
    givenAWronglyFormattedE2eKeyDTO();

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(e2eKeyDTO)))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.CONSULTANT_DEFAULT)
  void updateE2eInChatsShouldRespondWithBadRequestIfPayloadIsEmpty() throws Exception {
    givenAValidConsultant(true);

    mockMvc
        .perform(
            put("/users/chat/e2e")
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.STOP_CHAT)
  void stopChatShouldReturnOkIfUsersAreNotBanned() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), false);
    givenAValidRocketChatGroupDeleteResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/stop", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.STOP_CHAT)
  void stopChatShouldReturnOkIfUsersAreBanned() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);
    givenAValidRocketChatUnmuteResponse();
    givenAValidRocketChatGroupDeleteResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/stop", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var urlSuffix = "/api/v1/rooms.info?roomId=" + chat.getGroupId();
    verify(rocketChatRestTemplate)
        .exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.STOP_CHAT)
  void stopChatShouldReturnOkIfEncryptionIsDeactivated() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(true);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatGroupDeleteResponse();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);
    givenAValidRocketChatUnmuteResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/stop", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var allChatsAfter =
        StreamSupport.stream(chatRepository.findAll().spliterator(), false)
            .sorted(Comparator.comparing(Chat::getUpdateDate).reversed())
            .collect(Collectors.toList());
    chat = allChatsAfter.get(0);
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.STOP_CHAT)
  void stopChatShouldReturnOkAndDeleteOneTimeChatAndChatAgency() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(false);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatGroupDeleteResponse();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);
    givenAValidRocketChatUnmuteResponse();

    mockMvc
        .perform(
            put("/users/chat/{chatId}/stop", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    assertFalse(chatRepository.existsById(chat.getId()));
    assertFalse(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate)
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));
  }

  @Test
  @WithMockUser(authorities = AuthorityValue.STOP_CHAT)
  void stopChatShouldCompleteFiniteSeriesWhenNoNextOccurrenceRemains() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenAValidChat(true);
    givenAValidRocketChatSystemUser();
    givenAValidRocketChatGroupDeleteResponse();
    givenAValidRocketChatRoomResponse(chat.getGroupId(), true);
    givenAValidRocketChatUnmuteResponse();
    var originalStartDate = chat.getStartDate();
    var originalGroupId = chat.getGroupId();
    var originalUpdateDate = chat.getUpdateDate();
    assertNull(chat.nextStart());

    var allChatsBefore =
        StreamSupport.stream(chatRepository.findAll().spliterator(), false)
            .collect(Collectors.toSet());

    mockMvc
        .perform(
            put("/users/chat/{chatId}/stop", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk());

    var allChatsAfter =
        StreamSupport.stream(chatRepository.findAll().spliterator(), false)
            .sorted(Comparator.comparing(Chat::getUpdateDate).reversed())
            .collect(Collectors.toList());

    assertEquals(allChatsBefore.size(), allChatsAfter.size());
    assertTrue(allChatsBefore.containsAll(allChatsAfter));

    assertTrue(chatRepository.existsById(chat.getId()));
    assertTrue(chatAgencyRepository.existsById(chatAgency.getId()));

    var urlSuffix = "/api/v1/groups.delete";
    verify(restTemplate)
        .postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class));

    var chatAfter = chatRepository.findById(chat.getId()).orElseThrow();

    assertEquals(chat.getConsultingTypeId(), chatAfter.getConsultingTypeId());
    assertEquals(
        chat.getInitialStartDate().truncatedTo(ChronoUnit.SECONDS),
        chatAfter.getInitialStartDate().truncatedTo(ChronoUnit.SECONDS));
    assertEquals(
        originalStartDate.truncatedTo(ChronoUnit.SECONDS),
        chatAfter.getStartDate().truncatedTo(ChronoUnit.SECONDS));
    assertEquals(chat.getDuration(), chatAfter.getDuration());
    assertEquals(chat.isRepetitive(), chatAfter.isRepetitive());
    assertEquals(chat.getChatInterval(), chatAfter.getChatInterval());
    assertFalse(chatAfter.isActive());
    assertEquals(chat.getMaxParticipants(), chatAfter.getMaxParticipants());
    assertEquals(originalGroupId, chatAfter.getGroupId());
    assertEquals(chat.getChatOwner(), chatAfter.getChatOwner());
    assertPersistedTimestampEquals(originalUpdateDate, chatAfter.getUpdateDate());
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.USER_DEFAULT})
  void getChatByIdShouldFindChatByChatId() throws Exception {
    givenAValidUser(true);
    givenAValidConsultant(false);
    givenNoRocketChatSubscriptionUpdates();
    givenNoRocketChatRoomUpdates();
    givenAValidChat(false);

    mockMvc
        .perform(
            get("/users/chat/room/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header(RC_TOKEN_HEADER_PARAMETER_NAME, RC_TOKEN)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].session", is(nullValue())))
        .andExpect(jsonPath("sessions[0].chat", is(notNullValue())))
        .andExpect(jsonPath("sessions[0].consultant", is(nullValue())));
  }

  @Test
  @WithMockUser(authorities = {AuthorityValue.CONSULTANT_DEFAULT})
  void getChatByIdShouldFindChatByChatIdForConsultant() throws Exception {
    givenAValidUser();
    givenAValidConsultant(true);
    givenNoRocketChatSubscriptionUpdates();
    givenNoRocketChatRoomUpdates();
    givenAValidChat(false);

    mockMvc
        .perform(
            get("/users/chat/room/{chatId}", chat.getId())
                .cookie(CSRF_COOKIE)
                .header(CSRF_HEADER, CSRF_VALUE)
                .header(RC_TOKEN_HEADER_PARAMETER_NAME, RC_TOKEN)
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("sessions[0].session", is(nullValue())))
        .andExpect(jsonPath("sessions[0].chat", is(notNullValue())))
        .andExpect(jsonPath("sessions[0].consultant.firstName", is("Emiration")))
        .andExpect(jsonPath("sessions[0].consultant.lastName", is("Consultant")))
        .andExpect(
            jsonPath("sessions[0].consultant.id", is("0b3b1cc6-be98-4787-aa56-212259d811b9")));
  }

  private long aPositiveLong() {
    return Math.abs(easyRandom.nextLong());
  }

  private void givenACorrectlyFormattedE2eKeyDTO() {
    var n =
        "w5j-hUYZRT-ZSBJsk3J1gEtZG5fuP66dWMxs2I4PxgIC7TH8JU_zEDSjgjR6mCsIARVhyzZnBsNVoJYIg2TDF"
            + "18TAcYhaDsFEhxntg9RktrLGIs_nod0cafLCVQYWfp27SrpBeHdO9ewuezJzSzvNPZnx-8iWIDqp_nQt2xSPdh2"
            + "8AUm8f3KJ0P0AGFL6HiQ24GcLlsi-xqit3_M-MMr0kYJenaxJX1IdXCd1Io_pWBcgykSxhGo0fDWpfhkS1jmU4_"
            + "_9RNfoR1uroa10g3YVWYXvpZ5T9Qw96ynhwqdLMsGwbo1Y2AyG8NckOR3fE4ARC3OSUv0LFqmdq2xf5quZw";

    e2eKeyDTO = new E2eKeyDTO();
    e2eKeyDTO.setPublicKey(n);
  }

  private void givenAWronglyFormattedE2eKeyDTO() {
    e2eKeyDTO = new E2eKeyDTO();
    e2eKeyDTO.setPublicKey(RandomStringUtils.randomAlphanumeric(8));
  }

  private void givenAValidRocketChatUnmuteResponse() {
    var urlSuffix = "/method.call/unmuteUserInRoom";
    var messageResponse = easyRandom.nextObject(MessageResponse.class);
    messageResponse.setSuccess(true);

    when(rocketChatRestTemplate.postForEntity(
            endsWith(urlSuffix), any(HttpEntity.class), eq(MessageResponse.class)))
        .thenReturn(ResponseEntity.ok(messageResponse));
  }

  private void givenAValidRocketChatInfoUserResponse() {
    givenAValidRocketChatInfoUserResponse(consultant);
  }

  private void givenAValidRocketChatInfoUserResponse(Consultant consultant) {
    userInfoResponse = new UserInfoResponseDTO();
    userInfoResponse.setSuccess(true);

    var chatUser = easyRandom.nextObject(RocketChatUserDTO.class);
    chatUser.setId(consultant.getRocketChatId());
    chatUser.setUsername(consultant.getUsername());
    chatUser.setName(usernameTranscoder.encodeUsername("Consultant Max"));
    userInfoResponse.setUser(chatUser);

    var urlSuffix = "/api/v1/users.info?userId=" + consultant.getRocketChatId();
    when(rocketChatRestTemplate.exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET),
            any(HttpEntity.class), eq(UserInfoResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(userInfoResponse));
  }

  private void givenAValidRocketChatGroupDeleteResponse() {
    groupDeleteResponse = new GroupDeleteResponseDTO();
    groupDeleteResponse.setSuccess(true);

    var urlSuffix = "/api/v1/groups.delete";
    when(restTemplate.postForObject(
            endsWith(urlSuffix), any(HttpEntity.class), eq(GroupDeleteResponseDTO.class)))
        .thenReturn(groupDeleteResponse);
  }

  private void givenAPositiveRocketChatGroupMemberResponse(String chatUserId)
      throws JsonProcessingException {
    var doc1 = givenSubscription(RC_CREDENTIALS_TECHNICAL_A.getRocketChatUserId(), "t", null);
    var doc2 = givenSubscription(chatUserId, "b", "c");
    givenMongoResponseWith(doc1, doc2);
  }

  private void givenAnOnlyTechUserRocketChatGroupMemberResponse() throws JsonProcessingException {
    var doc = givenSubscription(RC_CREDENTIALS_TECHNICAL_A.getRocketChatUserId(), "t", null);
    givenMongoResponseWith(doc);
  }

  private void givenMongoResponseWith(Document doc, Document... docs) {
    if (nonNull(doc)) {
      when(mongoCursor.next()).thenReturn(doc, docs);
    }
    var booleanList = new LinkedList<Boolean>();
    var numExtraDocs = docs.length;
    while (numExtraDocs-- > 0) {
      booleanList.add(true);
    }
    booleanList.add(false);
    if (nonNull(doc)) {
      when(mongoCursor.hasNext()).thenReturn(true, booleanList.toArray(new Boolean[0]));
    } else {
      when(mongoCursor.hasNext()).thenReturn(false);
    }
    when(findIterable.iterator()).thenReturn(mongoCursor);
    when(mongoCollection.find(any(Bson.class))).thenReturn(findIterable);
    when(mockedMongoClient.getDatabase("rocketchat")).thenReturn(mongoDatabase);
    when(mongoDatabase.getCollection("rocketchat_subscription")).thenReturn(mongoCollection);
  }

  private Document givenSubscription(String chatUserId, String username, String name)
      throws JsonProcessingException {
    var doc = new LinkedHashMap<String, Object>();
    doc.put("_id", RandomStringUtils.randomAlphanumeric(17));
    doc.put("rid", RandomStringUtils.randomAlphanumeric(17));
    doc.put("name", RandomStringUtils.randomAlphanumeric(17));

    var user = new LinkedHashMap<>();
    user.put("_id", chatUserId);
    user.put("username", username);
    if (nonNull(name)) {
      user.put("name", name);
    }

    doc.put("u", user);

    var json = objectMapper.writeValueAsString(doc);

    return Document.parse(json);
  }

  private void givenAValidRocketChatGetSubscriptionsResponse(int subscriptionSize, boolean isE2e) {
    subscriptionsGetResponse = new SubscriptionsGetDTO();
    subscriptionsGetResponse.setSuccess(true);

    var updates = new ArrayList<SubscriptionsUpdateDTO>(subscriptionSize);
    for (int i = 0; i < subscriptionSize; i++) {
      var subscriptionsUpdateDTO = easyRandom.nextObject(SubscriptionsUpdateDTO.class);
      subscriptionsUpdateDTO.setRoomId(RandomStringUtils.randomAlphanumeric(8));
      if (isE2e) {
        subscriptionsUpdateDTO.setE2eKey(
            "tmp.1234567890abU2FsdGVkX1+3tjZ5PaAKTMSKZS4v8t8BwGmmhqoMj68=");
      } else {
        subscriptionsUpdateDTO.setE2eKey(null);
      }
      var user = new RocketChatUserDTO();
      user.setId(RandomStringUtils.randomAlphanumeric(17));
      subscriptionsUpdateDTO.setUser(user);
      updates.add(subscriptionsUpdateDTO);
    }
    subscriptionsGetResponse.setUpdate(updates.toArray(new SubscriptionsUpdateDTO[0]));

    var urlSuffix = "/api/v1/subscriptions.get";
    when(rocketChatRestTemplate.exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET),
            any(HttpEntity.class), eq(SubscriptionsGetDTO.class)))
        .thenReturn(ResponseEntity.ok(subscriptionsGetResponse));
  }

  private void givenARocketChatGetSubscriptionsResponseIncludingNoneTemporary() {
    subscriptionsGetResponse = new SubscriptionsGetDTO();
    subscriptionsGetResponse.setSuccess(true);

    var size = easyRandom.nextInt(5);
    var updates = new ArrayList<SubscriptionsUpdateDTO>(size);
    for (int i = 0; i <= size; i++) {
      var subscriptionsUpdateDTO = easyRandom.nextObject(SubscriptionsUpdateDTO.class);
      subscriptionsUpdateDTO.setRoomId(RandomStringUtils.randomAlphanumeric(8));
      subscriptionsUpdateDTO.setE2eKey(RandomStringUtils.randomAlphanumeric(60));
      var user = new RocketChatUserDTO();
      user.setId(RandomStringUtils.randomAlphanumeric(17));
      subscriptionsUpdateDTO.setUser(user);
      updates.add(subscriptionsUpdateDTO);
    }
    subscriptionsGetResponse.setUpdate(updates.toArray(new SubscriptionsUpdateDTO[0]));

    var urlSuffix = "/api/v1/subscriptions.get";
    when(rocketChatRestTemplate.exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET),
            any(HttpEntity.class), eq(SubscriptionsGetDTO.class)))
        .thenReturn(ResponseEntity.ok(subscriptionsGetResponse));
  }

  private void givenValidRocketChatGroupKeyUpdateResponses() {
    var urlSuffix = "/api/v1/e2e.updateGroupKey";
    var response = easyRandom.nextObject(StandardResponseDTO.class);

    when(rocketChatRestTemplate.postForEntity(
            endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class)))
        .thenReturn(ResponseEntity.ok(response));
  }

  private void givenFailedRocketChatGroupKeyUpdateResponses() {
    var urlSuffix = "/api/v1/e2e.updateGroupKey";

    when(rocketChatRestTemplate.postForEntity(
            endsWith(urlSuffix), any(HttpEntity.class), eq(StandardResponseDTO.class)))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
  }

  private void givenAValidRocketChatRoomResponse(String roomId, boolean hasBannedUsers) {
    var urlSuffix = "/rooms.info?roomId=" + roomId;
    var roomResponse = easyRandom.nextObject(RoomResponse.class);
    roomResponse.setSuccess(true);
    if (hasBannedUsers) {
      roomResponse.getRoom().setMuted(List.of(user.getRcUserId()));
    } else {
      roomResponse.getRoom().setMuted(null);
    }

    when(rocketChatRestTemplate.exchange(
            endsWith(urlSuffix), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomResponse.class)))
        .thenReturn(ResponseEntity.ok(roomResponse));
  }

  private void givenAValidRocketChatMuteUserInRoomResponse() {
    var urlSuffix = "/api/v1/method.call/muteUserInRoom";
    var messageResponse = easyRandom.nextObject(MessageResponse.class);
    messageResponse.setSuccess(true);

    when(rocketChatRestTemplate.postForEntity(
            endsWith(urlSuffix), any(HttpEntity.class), eq(MessageResponse.class)))
        .thenReturn(ResponseEntity.ok(messageResponse));
  }

  private void givenAnInvalidRocketChatMuteUserInRoomResponse() {
    var urlSuffix = "/api/v1/method.call/muteUserInRoom";
    var messageResponse = easyRandom.nextObject(MessageResponse.class);
    messageResponse.setSuccess(true); // according to Rocket.Chat
    var message =
        RandomStringUtils.randomAlphanumeric(8)
            + "error-user-not-in-room"
            + RandomStringUtils.randomAlphanumeric(8);
    messageResponse.setMessage(message);

    when(rocketChatRestTemplate.postForEntity(
            endsWith(urlSuffix), any(HttpEntity.class), eq(MessageResponse.class)))
        .thenReturn(ResponseEntity.ok().body(messageResponse));
  }

  private void givenAValidConsultant() {
    givenAValidConsultant(false);
  }

  private void givenAValidConsultant(boolean isAuthUser) {
    consultant = consultantRepository.findAll().iterator().next();
    if (isAuthUser) {
      when(authenticatedUser.getUserId()).thenReturn(consultant.getId());
      when(authenticatedUser.isAdviceSeeker()).thenReturn(false);
      when(authenticatedUser.isConsultant()).thenReturn(true);
      when(authenticatedUser.getUsername()).thenReturn(consultant.getUsername());
      when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.CONSULTANT.getValue()));
      when(authenticatedUser.getGrantedAuthorities()).thenReturn(Set.of("anAuthority"));
    }
  }

  private void givenGroupChatFeatureEnabled() {
    if (consultant.getTenantId() == null) {
      consultant.setTenantId(1L);
      consultantRepository.save(consultant);
    }
    var tenant =
        new RestrictedTenantDTO()
            .id(consultant.getTenantId())
            .name("Test tenant")
            .settings(new RestrictedTenantSettings().featureGroupChatV2Enabled(true));
    when(tenantService.getRestrictedTenantDataFresh(consultant.getTenantId())).thenReturn(tenant);
  }

  private void givenAValidUser() {
    givenAValidUser(false);
  }

  private void givenAValidUser(boolean isAuthUser) {
    user = userRepository.findAll().iterator().next();
    if (isAuthUser) {
      when(authenticatedUser.getUserId()).thenReturn(user.getUserId());
      when(authenticatedUser.isAdviceSeeker()).thenReturn(true);
      when(authenticatedUser.isConsultant()).thenReturn(false);
      when(authenticatedUser.getUsername()).thenReturn(user.getUsername());
      when(authenticatedUser.getRoles()).thenReturn(Set.of(UserRole.USER.getValue()));
      when(authenticatedUser.getGrantedAuthorities()).thenReturn(Set.of("anotherAuthority"));
    }
  }

  private void givenAValidChat(boolean isRepetitive) {
    givenAValidChat(isRepetitive, RC_GROUP_ID);
  }

  private void givenAValidChat(boolean isRepetitive, String groupId) {
    chat = easyRandom.nextObject(Chat.class);
    chat.setId(null);
    chat.setGroupId(groupId);
    chat.setMatrixRoomId(groupId.startsWith("!") ? groupId : null);
    chat.setActive(true);
    chat.setRepetitive(isRepetitive);
    chat.setSourceLanguage("de");
    chat.setChatOwner(consultant);
    chat.setConsultingTypeId(easyRandom.nextInt(128));
    chat.setDuration(easyRandom.nextInt(32768));
    chat.setMaxParticipants(easyRandom.nextInt(128));
    chat.setUpdateDate(CustomLocalDateTime.nowInUtc());
    chatRepository.save(chat);

    var agencyId =
        consultantAgencyRepository.findByConsultantId(consultant.getId()).getFirst().getAgencyId();
    chatAgency = new ChatAgency();
    chatAgency.setChat(chat);
    chatAgency.setAgencyId(agencyId);
    chatAgencyRepository.save(chatAgency);

    if (nonNull(user)) {
      userAgency = new UserAgency();
      userAgency.setUser(user);
      userAgency.setAgencyId(agencyId);
      userAgencyRepository.save(userAgency);
    }
  }

  private void givenAValidRocketChatSystemUser() throws RocketChatUserNotInitializedException {
    when(rocketChatCredentialsProvider.getSystemUserSneaky()).thenReturn(RC_CREDENTIALS_SYSTEM_A);
    when(rocketChatCredentialsProvider.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);
  }

  private void givenValidRocketChatTechUserResponse() throws RocketChatUserNotInitializedException {
    when(rocketChatCredentialsProvider.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    var body = new UserInfoResponseDTO();
    body.setSuccess(true);
    if (nonNull(user)) {
      body.setUser(new RocketChatUserDTO("", user.getUsername(), null, null));
    }
    var userInfoResponseDTO = ResponseEntity.ok(body);
    when(restTemplate.exchange(anyString(), any(), any(), eq(UserInfoResponseDTO.class)))
        .thenReturn(userInfoResponseDTO);
    when(restTemplate.exchange(
            anyString(), any(), any(), eq(UserInfoResponseDTO.class), anyString()))
        .thenReturn(userInfoResponseDTO);
  }

  private void verifyRocketChatUserRemovedFromGroup(
      CapturedOutput logOutput, String groupId, String chatUserId) {
    int occurrencesOfRemoval =
        StringUtils.countOccurrencesOf(
            logOutput.getOut(),
            "RocketChatTestConfig.removeUserFromGroup(" + chatUserId + "," + groupId + ") called");
    assertEquals(1, occurrencesOfRemoval);
  }

  private void assertPersistedTimestampEquals(
      LocalDateTime expected, LocalDateTime persistedValue) {
    assertTrue(
        Math.abs(ChronoUnit.NANOS.between(expected, persistedValue)) <= 500,
        () -> "Expected persisted timestamp " + expected + " but was " + persistedValue);
  }

  private void givenNoRocketChatSubscriptionUpdates() {
    var response = new SubscriptionsGetDTO();
    var subscriptionsUpdate = new SubscriptionsUpdateDTO[0];
    response.setUpdate(subscriptionsUpdate);
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(SubscriptionsGetDTO.class)))
        .thenReturn(ResponseEntity.ok(response));
  }

  private void givenNoRocketChatRoomUpdates() {
    var response = new RoomsGetDTO();
    var roomsUpdate = new RoomsUpdateDTO[0];
    response.setUpdate(roomsUpdate);
    when(restTemplate.exchange(
            anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(RoomsGetDTO.class)))
        .thenReturn(ResponseEntity.ok(response));
  }
}
