package de.caritas.cob.userservice.api.service;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.testHelper.FieldConstants.FIELD_NAME_ROCKET_CHAT_TECH_AUTH_TOKEN;
import static de.caritas.cob.userservice.api.testHelper.FieldConstants.FIELD_NAME_ROCKET_CHAT_TECH_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.GROUP_MEMBER_DTO_LIST;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.GROUP_MEMBER_USER_1;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.GROUP_MEMBER_USER_2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_CREDENTIALS;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_CREDENTIALS_SYSTEM_A;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_CREDENTIALS_TECHNICAL_A;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_GROUP_ID_2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.RC_USER_ID;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ROCKET_CHAT_USER_DTO;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.ROCKET_CHAT_USER_DTO_2;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USER_INFO_RESPONSE_DTO;
import static java.util.Objects.nonNull;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.powermock.reflect.Whitebox.setInternalState;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentialsProvider;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatMapper;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatGroupClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatMessageClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatPresenceClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatRoomClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatSubscriptionClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.client.RocketChatUserClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.config.RocketChatConfig;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.StandardResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupMemberDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.group.GroupsListAllResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.DataDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.login.LoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.logout.LogoutResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.room.RoomsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsGetDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.subscriptions.SubscriptionsUpdateDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.RocketChatUserDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UserUpdateRequestDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.dto.user.UsersListReponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.RocketChatUnauthorizedException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatAddUserToGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatCreateGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatDeleteUserException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupMembersException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetGroupsListAllException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatGetUserIdException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatLoginException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveSystemMessagesException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatRemoveUserFromGroupException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.RandomStringUtils;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RocketChatServiceTest {

  private final String MESSAGE = "Lorem Ipsum";
  private final String GROUP_ID = "xxxYYY";
  private final String GROUP_NAME = "group";
  private final GroupResponseDTO EMPTY_GROUP_RESPONSE_DTO =
      new GroupResponseDTO(null, false, null, null);
  private final SubscriptionsGetDTO SUBSCRIPTIONS_GET_DTO =
      new SubscriptionsGetDTO(new SubscriptionsUpdateDTO[] {}, false, null, null);
  private final RoomsGetDTO ROOMS_GET_DTO =
      new RoomsGetDTO(new RoomsUpdateDTO[] {}, true, null, null);
  private final ResponseEntity<SubscriptionsGetDTO> SUBSCRIPTIONS_GET_RESPONSE_ENTITY =
      new ResponseEntity<>(SUBSCRIPTIONS_GET_DTO, HttpStatus.OK);
  private final ResponseEntity<RoomsGetDTO> ROOMS_GET_RESPONSE_ENTITY =
      new ResponseEntity<>(ROOMS_GET_DTO, HttpStatus.OK);
  private final ResponseEntity<SubscriptionsGetDTO> SUBSCRIPTIONS_GET_RESPONSE_ENTITY_NOT_OK =
      new ResponseEntity<>(SUBSCRIPTIONS_GET_DTO, HttpStatus.BAD_REQUEST);
  private final ResponseEntity<RoomsGetDTO> ROOMS_GET_RESPONSE_ENTITY_NOT_OK =
      new ResponseEntity<>(ROOMS_GET_DTO, HttpStatus.BAD_REQUEST);
  private final String ERROR_MSG = "error";
  private final StandardResponseDTO STANDARD_RESPONSE_DTO_SUCCESS =
      new StandardResponseDTO(true, null);
  private final StandardResponseDTO STANDARD_RESPONSE_DTO_ERROR =
      new StandardResponseDTO(false, ERROR_MSG);
  private final LoginResponseDTO LOGIN_RESPONSE_DTO_TECH_USER =
      new LoginResponseDTO(
          "status",
          new DataDTO(
              FIELD_NAME_ROCKET_CHAT_TECH_AUTH_TOKEN, FIELD_NAME_ROCKET_CHAT_TECH_USER_ID, null));
  private final LogoutResponseDTO LOGOUT_RESPONSE_DTO_WITH =
      new LogoutResponseDTO(null, null, null);
  private final GroupDTO GROUP_DTO =
      new GroupDTO(GROUP_ID, GROUP_NAME, null, null, 0, 0, null, null, false, false, null);
  private final GroupDTO GROUP_DTO_2 =
      new GroupDTO(RC_GROUP_ID_2, GROUP_NAME, null, null, 0, 0, null, null, false, false, null);
  private final GroupResponseDTO GROUP_RESPONSE_DTO =
      new GroupResponseDTO(GROUP_DTO, true, null, null);
  private final UsersListReponseDTO USERS_LIST_RESPONSE_DTO_EMPTY =
      new UsersListReponseDTO(new RocketChatUserDTO[0]);
  private final UsersListReponseDTO USERS_LIST_RESPONSE_DTO =
      new UsersListReponseDTO(new RocketChatUserDTO[] {ROCKET_CHAT_USER_DTO});
  private final UsersListReponseDTO USERS_LIST_RESPONSE_DTO_WITH_2_USERS =
      new UsersListReponseDTO(
          new RocketChatUserDTO[] {ROCKET_CHAT_USER_DTO, ROCKET_CHAT_USER_DTO_2});
  private final GroupsListAllResponseDTO GROUPS_LIST_ALL_RESPONSE_DTO_EMPTY =
      new GroupsListAllResponseDTO(new GroupDTO[0], 1, 0, 0);
  private final GroupsListAllResponseDTO GROUPS_LIST_ALL_RESPONSE_DTO =
      new GroupsListAllResponseDTO(new GroupDTO[] {GROUP_DTO, GROUP_DTO_2}, 0, 2, 10);

  private final GroupsListAllResponseDTO GROUPS_LIST_ALL_RESPONSE_DTO_PAGINATED =
      new GroupsListAllResponseDTO(new GroupDTO[] {GROUP_DTO, GROUP_DTO_2}, 0, 100, 1000);

  private final GroupsListAllResponseDTO
      GROUPS_LIST_ALL_RESPONSE_DTO_PAGINATED_WITH_TOTAL_ZERO_ELEMENTS =
          new GroupsListAllResponseDTO(new GroupDTO[] {GROUP_DTO, GROUP_DTO_2}, 0, 0, 0);
  private final LocalDateTime DATETIME_OLDEST = nowInUtc();
  private final LocalDateTime DATETIME_LATEST = nowInUtc();
  private final String PASSWORD = "password";
  private final RocketChatConfig rocketChatConfig =
      new RocketChatConfig(new MockHttpServletRequest());
  private final ObjectMapper objectMapper = new ObjectMapper();

  // New client mocks required by refactored facade constructor
  @Mock private RocketChatClient rocketChatClient;
  @Mock private RocketChatMapper rocketChatMapper;
  @Mock private RocketChatConfig rocketChatConfigMock;
  @Mock private RocketChatUserClient rocketChatUserClient;
  @Mock private RocketChatGroupClient rocketChatGroupClient;
  @Mock private RocketChatRoomClient rocketChatRoomClient;
  @Mock private RocketChatMessageClient rocketChatMessageClient;
  @Mock private RocketChatPresenceClient rocketChatPresenceClient;
  @Mock private RocketChatSubscriptionClient rocketChatSubscriptionClient;

  @Mock Logger logger;
  @Mock RocketChatCredentialsProvider rcCredentialsHelper;
  @InjectMocks private RocketChatService rocketChatService;
  @Mock private RestTemplate restTemplate;
  @Mock private MongoClient mockedMongoClient;

  @Mock private MongoDatabase mongoDatabase;

  @Mock private MongoCollection<Document> mongoCollection;

  @Mock private MongoCursor<Document> mongoCursor;

  @Mock private FindIterable<Document> findIterable;

  @BeforeEach
  void setup() {
    rocketChatConfig.setBaseUrl("http://localhost/api/v1");
    setField(rocketChatService, "rocketChatConfig", rocketChatConfig);

    setInternalState(RocketChatService.class, "log", logger);
  }

  /** Method: createPrivateGroup */
  @Test
  void createPrivateGroup_Should_ReturnTheGroupId_WhenRocketChatApiCallWasSuccessfully()
      throws SecurityException, RocketChatCreateGroupException {

    when(rocketChatGroupClient.createPrivateGroup(eq(GROUP_NAME), eq(RC_CREDENTIALS)))
        .thenReturn(Optional.of(GROUP_RESPONSE_DTO));

    Optional<GroupResponseDTO> result =
        rocketChatService.createPrivateGroup(GROUP_NAME, RC_CREDENTIALS);

    assertTrue(result.isPresent());
    assertEquals(GROUP_ID, result.get().getGroup().getId());
  }

  @Test
  void
      createPrivateGroup_Should_ThrowRocketChatCreateGroupException_WhenApiCallFailsWithAnException()
          throws SecurityException, RocketChatCreateGroupException {

    doThrow(new RocketChatCreateGroupException("error"))
        .when(rocketChatGroupClient)
        .createPrivateGroup(eq(GROUP_NAME), eq(RC_CREDENTIALS));

    try {
      rocketChatService.createPrivateGroup(GROUP_NAME, RC_CREDENTIALS);
      fail("Expected exception: RocketChatCreateGroupException");
    } catch (RocketChatCreateGroupException rocketChatCreateGroupException) {
      assertTrue(true, "Excepted RocketChatCreateGroupException thrown");
    }
  }

  /** Method: deleteGroup */
  @Test
  void deleteGroup_Should_ReturnTrue_WhenApiCallIsSuccessful() throws SecurityException {

    when(rocketChatGroupClient.rollbackGroup(eq(GROUP_ID), eq(RC_CREDENTIALS))).thenReturn(true);

    boolean result = rocketChatService.rollbackGroup(GROUP_ID, RC_CREDENTIALS);

    assertTrue(result);
  }

  @Test
  void deleteGroup_Should_ReturnFalseAndLog_WhenApiCallIsNotSuccessful() throws SecurityException {

    when(rocketChatGroupClient.rollbackGroup(eq(GROUP_ID), eq(RC_CREDENTIALS))).thenReturn(false);

    boolean result = rocketChatService.rollbackGroup(GROUP_ID, RC_CREDENTIALS);

    assertFalse(result);
  }

  @Test
  void rollbackGroup_Should_Log_WhenApiCallFailsWithAnException() throws SecurityException {

    when(rocketChatGroupClient.rollbackGroup(eq(GROUP_ID), eq(RC_CREDENTIALS))).thenReturn(false);

    var result = rocketChatService.rollbackGroup(GROUP_ID, RC_CREDENTIALS);

    assertFalse(result);
    verify(rocketChatGroupClient, times(1)).rollbackGroup(eq(GROUP_ID), eq(RC_CREDENTIALS));
  }

  /** Method: addUserToGroup */
  @Test
  void addUserToGroup_Should_ThrowRocketChatAddUserToGroupException_WheApiCallFails()
      throws RocketChatAddUserToGroupException {

    doThrow(new RocketChatAddUserToGroupException("error"))
        .when(rocketChatGroupClient)
        .addUserToGroup(eq(RC_USER_ID), eq(GROUP_ID));

    try {
      rocketChatService.addUserToGroup(RC_USER_ID, GROUP_ID);
      fail("Expected exception: RocketChatAddUserToGroupException");
    } catch (RocketChatAddUserToGroupException rcAddToGroupEx) {
      assertTrue(true, "Excepted RocketChatAddUserToGroupException thrown");
    }
  }

  @Test
  void addUserToGroup_Should_ThrowRocketChatLoginException_WhenResponseIsNotSuccessful()
      throws RocketChatUserNotInitializedException, RocketChatAddUserToGroupException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    doThrow(new RocketChatAddUserToGroupException("error"))
        .when(rocketChatGroupClient)
        .addUserToGroup(eq(RC_USER_ID), eq(GROUP_ID));

    try {
      rocketChatService.addUserToGroup(RC_USER_ID, GROUP_ID);
      fail("Expected exception: RocketChatAddUserToGroupException");
    } catch (RocketChatAddUserToGroupException rcAddToGroupEx) {
      assertTrue(true, "Excepted RocketChatAddUserToGroupException thrown");
    }
  }

  /** Method: removeUserFromGroup */
  @Test
  void
      removeUserFromGroup_Should_ThrowRocketChatRemoveUserFromGroupException_WhenAPICallIsNotSuccessful()
          throws RocketChatUserNotInitializedException, RocketChatRemoveUserFromGroupException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    doThrow(new RocketChatRemoveUserFromGroupException("error"))
        .when(rocketChatGroupClient)
        .removeUserFromGroup(eq(RC_USER_ID), eq(GROUP_ID));

    try {
      rocketChatService.removeUserFromGroup(RC_USER_ID, GROUP_ID);
      fail("Expected exception: RocketChatRemoveUserFromGroupException");
    } catch (RocketChatRemoveUserFromGroupException ex) {
      assertTrue(true, "Excepted RocketChatRemoveUserFromGroupException thrown");
    }
  }

  @Test
  void
      removeUserFromGroup_Should_ThrowRocketChatRemoveUserFromGroupException_WhenAPIResponseIsUnSuccessful()
          throws Exception {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    doThrow(new RocketChatRemoveUserFromGroupException("error"))
        .when(rocketChatGroupClient)
        .removeUserFromGroup(eq(RC_USER_ID), eq(GROUP_ID));

    try {
      rocketChatService.removeUserFromGroup(RC_USER_ID, GROUP_ID);
      fail("Expected exception: RocketChatRemoveUserFromGroupException");
    } catch (RocketChatRemoveUserFromGroupException ex) {
      assertTrue(true, "Excepted RocketChatRemoveUserFromGroupException thrown");
    }
  }

  /** Method: createPrivateGroupWithSystemUser */
  @Test
  void
      createPrivateGroupWithSystemUser_Should_ReturnTheGroupId_When_RocketChatApiCallWasSuccessful()
          throws Exception {

    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);

    when(rocketChatGroupClient.createPrivateGroupWithSystemUser(eq(GROUP_NAME)))
        .thenReturn(Optional.of(GROUP_RESPONSE_DTO));

    Optional<GroupResponseDTO> result =
        rocketChatService.createPrivateGroupWithSystemUser(GROUP_NAME);

    assertTrue(result.isPresent());
    assertEquals(GROUP_ID, result.get().getGroup().getId());
  }

  /** Method: removeSystemMessages */
  @Test
  void
      removeSystemMessages_Should_ThrowRocketChatRemoveSystemMessagesException_WhenApiCallFailsWithAnException()
          throws RocketChatUserNotInitializedException, RocketChatRemoveSystemMessagesException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    doThrow(new RocketChatRemoveSystemMessagesException("error"))
        .when(rocketChatMessageClient)
        .removeSystemMessages(eq(GROUP_ID), eq(DATETIME_OLDEST), eq(DATETIME_LATEST));

    try {
      rocketChatService.removeSystemMessages(GROUP_ID, DATETIME_OLDEST, DATETIME_LATEST);
      fail("Expected exception: RocketChatRemoveSystemMessagesException");
    } catch (RocketChatRemoveSystemMessagesException rocketChatRemoveSystemMessagesException) {
      assertTrue(true, "Excepted RocketChatRemoveSystemMessagesException thrown");
    }
  }

  @Test
  void
      removeSystemMessages_Should_ThrowRocketChatRemoveSystemMessagesException_WhenDateFormatIsWrong()
          throws RocketChatUserNotInitializedException, RocketChatRemoveSystemMessagesException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    doThrow(new RocketChatRemoveSystemMessagesException("error"))
        .when(rocketChatMessageClient)
        .removeSystemMessages(eq(GROUP_ID), eq(DATETIME_OLDEST), eq(null));

    try {
      rocketChatService.removeSystemMessages(GROUP_ID, DATETIME_OLDEST, null);
      fail("Expected exception: RocketChatRemoveSystemMessagesException");
    } catch (RocketChatRemoveSystemMessagesException rocketChatRemoveSystemMessagesException) {
      assertTrue(true, "Excepted RocketChatRemoveSystemMessagesException thrown");
    }
  }

  @Test
  void removeSystemMessages_Should_ReturnFalseAndLogError_WhenApiCallIsUnSuccessful()
      throws Exception {
    assertThrows(
        RocketChatRemoveSystemMessagesException.class,
        () -> {
          doThrow(new RocketChatRemoveSystemMessagesException("error"))
              .when(rocketChatMessageClient)
              .removeSystemMessages(eq(GROUP_ID), eq(DATETIME_OLDEST), eq(DATETIME_LATEST));

          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

          rocketChatService.removeSystemMessages(GROUP_ID, DATETIME_OLDEST, DATETIME_LATEST);

          verify(logger, atLeastOnce()).error(anyString(), anyString(), anyString());
        });
  }

  @Test
  void removeSystemMessages_Should_NotThrowException_WhenApiCallIsSuccessful() throws Exception {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    assertDoesNotThrow(
        () -> rocketChatService.removeSystemMessages(GROUP_ID, DATETIME_OLDEST, DATETIME_LATEST));
  }

  /** Method: getUserId */
  @Test
  void getUserId_Should_LoginUser() throws RocketChatLoginException {

    when(rocketChatUserClient.getUserID(eq(USERNAME), eq(PASSWORD), eq(false)))
        .thenReturn(LOGIN_RESPONSE_DTO_TECH_USER.getData().getUserId());

    rocketChatService.getUserID(USERNAME, PASSWORD, false);

    verify(rocketChatUserClient, times(1)).getUserID(USERNAME, PASSWORD, false);
  }

  @Test
  void getUserId_Should_LogoutUser() throws RocketChatLoginException {

    when(rocketChatUserClient.getUserID(eq(USERNAME), eq(PASSWORD), eq(false)))
        .thenReturn(LOGIN_RESPONSE_DTO_TECH_USER.getData().getUserId());

    rocketChatService.getUserID(USERNAME, PASSWORD, false);

    verify(rocketChatUserClient, times(1)).getUserID(USERNAME, PASSWORD, false);
  }

  @Test
  void getUserId_Should_ReturnCorrectUserId() throws RocketChatLoginException {

    when(rocketChatUserClient.getUserID(eq(USERNAME), eq(PASSWORD), eq(false)))
        .thenReturn(LOGIN_RESPONSE_DTO_TECH_USER.getData().getUserId());

    String result = rocketChatService.getUserID(USERNAME, PASSWORD, false);

    assertEquals(LOGIN_RESPONSE_DTO_TECH_USER.getData().getUserId(), result);
  }

  /** Method: getSubscriptionsOfUser */
  @Test
  void
      getSubscriptionsOfUser_Should_ThrowInternalServerErrorException_When_APICallIsNotSuccessful() {

    when(rocketChatSubscriptionClient.getSubscriptionsOfUser(eq(RC_CREDENTIALS)))
        .thenThrow(new InternalServerErrorException("error"));

    try {
      rocketChatService.getSubscriptionsOfUser(RC_CREDENTIALS);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException ex) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  void
      getSubscriptionsOfUser_Should_ThrowInternalServerErrorException_When_APIResponseIsUnSuccessful() {

    when(rocketChatSubscriptionClient.getSubscriptionsOfUser(eq(RC_CREDENTIALS)))
        .thenThrow(new InternalServerErrorException("error"));

    try {
      rocketChatService.getSubscriptionsOfUser(RC_CREDENTIALS);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException ex) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  void
      getSubscriptionsOfUser_Should_ThrowUnauthorizedException_When_RocketChatReturnsUnauthorized() {

    when(rocketChatSubscriptionClient.getSubscriptionsOfUser(eq(RC_CREDENTIALS)))
        .thenThrow(new RocketChatUnauthorizedException(RC_CREDENTIALS.getRocketChatUserId(), null));

    var thrown =
        assertThrows(
            RocketChatUnauthorizedException.class,
            () -> rocketChatService.getSubscriptionsOfUser(RC_CREDENTIALS));

    var prefix = "Could not get Rocket.Chat subscriptions for user ID";
    assertTrue(thrown.getMessage().startsWith(prefix));
  }

  @Test
  void getSubscriptionsOfUser_Should_ReturnListOfSubscriptionsUpdateDTO_When_APICallIsSuccessful() {

    when(rocketChatSubscriptionClient.getSubscriptionsOfUser(eq(RC_CREDENTIALS)))
        .thenReturn(List.of());

    assertThat(
        rocketChatService.getSubscriptionsOfUser(RC_CREDENTIALS),
        everyItem(instanceOf(SubscriptionsUpdateDTO.class)));
  }

  /** Method: getRoomsOfUser */
  @Test
  void getRoomsOfUser_Should_ThrowInternalServerErrorException_When_APICallIsNotSuccessful() {

    when(rocketChatRoomClient.getRoomsOfUser(eq(RC_CREDENTIALS)))
        .thenThrow(new InternalServerErrorException("error"));

    try {
      rocketChatService.getRoomsOfUser(RC_CREDENTIALS);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException ex) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  void getRoomsOfUser_Should_ThrowInternalServerErrorException_When_APIResponseIsUnSuccessful() {

    when(rocketChatRoomClient.getRoomsOfUser(eq(RC_CREDENTIALS)))
        .thenThrow(new InternalServerErrorException("error"));

    try {
      rocketChatService.getRoomsOfUser(RC_CREDENTIALS);
      fail("Expected exception: InternalServerErrorException");
    } catch (InternalServerErrorException ex) {
      assertTrue(true, "Excepted InternalServerErrorException thrown");
    }
  }

  @Test
  void getRoomsOfUser_Should_ReturnListOfRoomsUpdateDTO_WhenAPICallIsSuccessful() {

    when(rocketChatRoomClient.getRoomsOfUser(eq(RC_CREDENTIALS))).thenReturn(List.of());

    assertThat(
        rocketChatService.getRoomsOfUser(RC_CREDENTIALS),
        everyItem(instanceOf(RoomsUpdateDTO.class)));
  }

  /** Method: removeAllStandardUsersFromGroup */
  @Test
  void
      removeAllStandardUsersFromGroup_Should_ThrowRocketChatGetGroupMembersException_WhenGroupListIsEmpty()
          throws Exception {

    setField(rocketChatGroupClient, "rocketChatRoomClient", rocketChatRoomClient);
    setField(rocketChatGroupClient, "rcCredentialHelper", rcCredentialsHelper);
    Mockito.doCallRealMethod()
        .when(rocketChatGroupClient)
        .removeAllStandardUsersFromGroup(anyString());

    when(rocketChatRoomClient.getChatUsers(anyString())).thenReturn(new ArrayList<>());

    try {
      rocketChatService.removeAllStandardUsersFromGroup(GROUP_ID);
      fail("Expected exception: RocketChatGetGroupMembersException");
    } catch (RocketChatGetGroupMembersException rocketChatGetGroupMembersException) {
      assertTrue(true, "Excepted RocketChatGetGroupMembersException thrown");
    }
  }

  @Test
  void removeAllStandardUsersFromGroup_Should_RemoveAllStandardUsersAndNotTechnicalOrSystemUser()
      throws Exception {

    setField(rocketChatGroupClient, "rocketChatRoomClient", rocketChatRoomClient);
    setField(rocketChatGroupClient, "rcCredentialHelper", rcCredentialsHelper);
    Mockito.doCallRealMethod()
        .when(rocketChatGroupClient)
        .removeAllStandardUsersFromGroup(anyString());

    when(rocketChatRoomClient.getChatUsers(anyString())).thenReturn(GROUP_MEMBER_DTO_LIST);

    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    rocketChatService.removeAllStandardUsersFromGroup(GROUP_ID);

    verify(rocketChatGroupClient, times(0))
        .removeUserFromGroup(RC_CREDENTIALS_SYSTEM_A.getRocketChatUserId(), GROUP_ID);
    verify(rocketChatGroupClient, times(0))
        .removeUserFromGroup(RC_CREDENTIALS_TECHNICAL_A.getRocketChatUserId(), GROUP_ID);
    verify(rocketChatGroupClient, times(1))
        .removeUserFromGroup(GROUP_MEMBER_USER_1.get_id(), GROUP_ID);
    verify(rocketChatGroupClient, times(1))
        .removeUserFromGroup(GROUP_MEMBER_USER_2.get_id(), GROUP_ID);
  }

  /** Method: removeAllMessages */
  @Test
  void removeAllMessages_Should_NotThrowException_WhenRemoveMessagesSucceeded() throws Exception {

    rocketChatService.removeAllMessages(GROUP_ID);

    verify(rocketChatMessageClient, times(1)).removeAllMessages(eq(GROUP_ID));
  }

  @Test
  void
      removeAllMessages_Should_ThrowRocketChatRemoveSystemMessagesException_WhenRemoveMessagesFails()
          throws Exception {
    assertThrows(
        RocketChatRemoveSystemMessagesException.class,
        () -> {
          doThrow(new RocketChatRemoveSystemMessagesException("error"))
              .when(rocketChatMessageClient)
              .removeAllMessages(eq(GROUP_ID));

          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

          rocketChatService.removeAllMessages(GROUP_ID);
        });
  }

  /** Method: getStandardMembersOfGroup */
  @Test
  void
      getStandardMembersOfGroup_Should_ThrowRocketChatGetGroupMembersException_WhenAPICallIsNotSuccessful()
          throws RocketChatGetGroupMembersException, RocketChatUserNotInitializedException {

    doThrow(new RocketChatGetGroupMembersException("error"))
        .when(rocketChatGroupClient)
        .getStandardMembersOfGroup(eq(GROUP_ID));

    try {
      rocketChatService.getStandardMembersOfGroup(GROUP_ID);
      fail("Expected exception: RocketChatGetGroupMembersException");
    } catch (RocketChatGetGroupMembersException | RocketChatUserNotInitializedException ex) {
      assertTrue(true, "Excepted RocketChatGetGroupMembersException thrown");
    }
  }

  @Test
  void
      getStandardMembersOfGroup_Should_ThrowRocketChatGetGroupMembersException_WhenAPIResponseIsUnSuccessful()
          throws Exception {
    doThrow(new RocketChatGetGroupMembersException("error"))
        .when(rocketChatGroupClient)
        .getStandardMembersOfGroup(eq(GROUP_ID));

    try {
      rocketChatService.getStandardMembersOfGroup(GROUP_ID);
      fail("Expected exception: RocketChatGetGroupMembersException");
    } catch (RocketChatGetGroupMembersException ex) {
      assertTrue(true, "Excepted RocketChatGetGroupMembersException thrown");
    }
  }

  @Test
  void getStandardMembersOfGroup_Should_ReturnListFilteredOfGroupMemberDTO_WhenAPICallIsSuccessful()
      throws Exception {

    var member1 = new GroupMemberDTO();
    member1.set_id(RC_CREDENTIALS_SYSTEM_A.getRocketChatUserId());
    member1.setUsername("s");

    var member2 = new GroupMemberDTO();
    member2.set_id(RC_CREDENTIALS_TECHNICAL_A.getRocketChatUserId());
    member2.setUsername("t");

    var member3 = new GroupMemberDTO();
    member3.set_id("a");
    member3.setUsername("t");

    when(rocketChatGroupClient.getStandardMembersOfGroup(eq(GROUP_ID)))
        .thenReturn(List.of(member3));

    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    List<GroupMemberDTO> result = rocketChatService.getStandardMembersOfGroup(GROUP_ID);

    assertEquals(1, result.size());
    assertEquals("a", result.get(0).get_id());
  }

  /** Method: getUserInfo */
  @Test
  void getUserInfo_Should_ThrowInternalServerExceptionException_WhenAPICallFails()
      throws RocketChatUserNotInitializedException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
          when(rocketChatUserClient.getUserInfo(eq(RC_USER_ID)))
              .thenThrow(new InternalServerErrorException("error"));

          rocketChatService.getUserInfo(RC_USER_ID);
        });
  }

  @Test
  void getUserInfo_Should_ThrowInternalServerErrorException_WhenAPICallIsNotSuccessful()
      throws RocketChatUserNotInitializedException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
          when(rocketChatUserClient.getUserInfo(eq(RC_USER_ID)))
              .thenThrow(new InternalServerErrorException("error"));

          rocketChatService.getUserInfo(RC_USER_ID);
        });
  }

  @Test
  void getUserInfo_Should_ReturnUserInfoResponseDTOWithSameUserId_WhenAPICallIsSuccessful()
      throws Exception {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getUserInfo(eq(RC_USER_ID))).thenReturn(USER_INFO_RESPONSE_DTO);

    UserInfoResponseDTO result = rocketChatService.getUserInfo(RC_USER_ID);

    assertEquals(RC_USER_ID, result.getUser().getId());
  }

  @Test
  void updateUser_Should_performRocketChatUpdate() throws RocketChatUserNotInitializedException {
    UserUpdateRequestDTO userUpdateRequestDTO =
        new EasyRandom().nextObject(UserUpdateRequestDTO.class);
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.updateUser(eq(userUpdateRequestDTO)))
        .thenReturn(USER_INFO_RESPONSE_DTO);

    this.rocketChatService.updateUser(userUpdateRequestDTO);

    verify(rocketChatUserClient, times(1)).updateUser(eq(userUpdateRequestDTO));
  }

  @Test
  void updateUser_Should_throwInternalServerErrorException_When_rocketChatUpdateFails()
      throws RocketChatUserNotInitializedException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          UserUpdateRequestDTO userUpdateRequestDTO =
              new EasyRandom().nextObject(UserUpdateRequestDTO.class);
          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
          when(rocketChatUserClient.updateUser(eq(userUpdateRequestDTO)))
              .thenThrow(new InternalServerErrorException("error"));

          this.rocketChatService.updateUser(userUpdateRequestDTO);
        });
  }

  @Test
  void updateUser_Should_throwInternalServerErrorException_When_rocketChatIsNotReachable()
      throws RocketChatUserNotInitializedException {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          UserUpdateRequestDTO userUpdateRequestDTO =
              new EasyRandom().nextObject(UserUpdateRequestDTO.class);
          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
          when(rocketChatUserClient.updateUser(eq(userUpdateRequestDTO)))
              .thenThrow(new InternalServerErrorException("error"));

          this.rocketChatService.updateUser(userUpdateRequestDTO);
        });
  }

  @Test
  void deleteUser_Should_performRocketDeleteUser() throws Exception {
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    this.rocketChatService.deleteUser("");

    verify(rocketChatUserClient, times(1)).deleteUser(eq(""));
  }

  @Test
  void deleteUser_Should_performRocketDeleteUserOnAlreadyDeletedResponse() throws Exception {
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    rocketChatService.deleteUser("");

    verify(rocketChatUserClient).deleteUser(eq(""));
  }

  @Test
  void deleteUser_Should_throwRocketChatDeleteUserException_When_responseIsNotSuccess()
      throws Exception {
    assertThrows(
        RocketChatDeleteUserException.class,
        () -> {
          when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
          doThrow(new RocketChatDeleteUserException(new RuntimeException("error")))
              .when(rocketChatUserClient)
              .deleteUser(eq(""));

          this.rocketChatService.deleteUser("");
        });
  }

  @Test
  void deleteGroupAsTechnicalUser_Should_performRocketDeleteUser() throws Exception {
    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);

    this.rocketChatService.deleteGroupAsTechnicalUser("");

    verify(rocketChatGroupClient, times(1)).deleteGroupAsTechnicalUser(eq(""));
  }

  @Test
  void
      deleteGroupAsTechnicalUser_Should_throwRocketChatDeleteUserException_When_responseIsNotSuccess()
          throws Exception {
    assertThrows(
        RocketChatDeleteGroupException.class,
        () -> {
          doThrow(new RocketChatDeleteGroupException(new RuntimeException("error")))
              .when(rocketChatGroupClient)
              .deleteGroupAsTechnicalUser(eq(""));

          this.rocketChatService.deleteGroupAsTechnicalUser("");
        });
  }

  @Test
  void deleteGroupAsSystemUser_Should_performRocketDeleteUser() throws Exception {
    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);
    when(rocketChatGroupClient.deleteGroupAsSystemUser(eq(""))).thenReturn(true);

    this.rocketChatService.deleteGroupAsSystemUser("");

    verify(rocketChatGroupClient, times(1)).deleteGroupAsSystemUser(eq(""));
  }

  @Test
  void deleteGroupAsSystemUser_Should_throwInternalServerErrorException_When_responseIsNotSuccess()
      throws Exception {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          when(rocketChatGroupClient.deleteGroupAsSystemUser(eq("")))
              .thenThrow(new InternalServerErrorException("error"));

          this.rocketChatService.deleteGroupAsSystemUser("");
        });
  }

  @Test
  @SuppressWarnings("unchecked")
  void setRoomReadOnly_Should_performRocketChatSetRoomReadOnly() throws Exception {
    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);

    this.rocketChatService.setRoomReadOnly(RC_GROUP_ID);

    verify(rocketChatRoomClient, times(1)).setRoomReadOnly(eq(RC_GROUP_ID));
  }

  @Test
  void setRoomReadOnly_Should_logError_When_responseIsNotSuccess() throws Exception {
    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);

    this.rocketChatService.setRoomReadOnly("");

    verify(rocketChatRoomClient, times(1)).setRoomReadOnly(eq(""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void setRoomWriteable_Should_performRocketChatSetRoomReadOnly() throws Exception {
    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);

    this.rocketChatService.setRoomWriteable(RC_GROUP_ID);

    verify(rocketChatRoomClient, times(1)).setRoomWriteable(eq(RC_GROUP_ID));
  }

  @Test
  void setRoomWriteable_Should_logError_When_responseIsNotSuccess() throws Exception {
    when(rcCredentialsHelper.getSystemUser()).thenReturn(RC_CREDENTIALS_SYSTEM_A);

    this.rocketChatService.setRoomWriteable("");

    verify(rocketChatRoomClient, times(1)).setRoomWriteable(eq(""));
  }

  @Test
  void fetchAllInactivePrivateGroupsSinceGivenDate_ShouldThrowException_WhenRocketChatCallFails()
      throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenThrow(
            new RocketChatGetGroupsListAllException("Could not get all rocket chat groups", null));

    try {
      this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(LocalDateTime.now());
      fail("Expected exception: RocketChatGetGroupsListAllException");
    } catch (RocketChatGetGroupsListAllException ex) {
      assertTrue(true, "Excepted RocketChatGetGroupsListAllException thrown");
      assertEquals("Could not get all rocket chat groups", ex.getMessage());
    }
  }

  @Test
  void
      fetchAllInactivePrivateGroupsSinceGivenDate_Should_ThrowException_WhenHttpStatusFromRocketChatCallIsNotOk()
          throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenThrow(
            new RocketChatGetGroupsListAllException("Could not get all rocket chat groups", null));

    try {
      this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(LocalDateTime.now());
      fail("Expected exception: RocketChatGetGroupsListAllException");
    } catch (RocketChatGetGroupsListAllException ex) {
      assertTrue(true, "Excepted RocketChatGetGroupsListAllException thrown");
      assertEquals("Could not get all rocket chat groups", ex.getMessage());
    }
  }

  @Test
  void fetchAllInactivePrivateGroupsSinceGivenDate_Should_ReturnCorrectGroupDtoList()
      throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(any()))
        .thenReturn(List.of(GROUP_DTO, GROUP_DTO_2));

    List<GroupDTO> result =
        this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(LocalDateTime.now());

    assertThat(result.size(), is(2));
    assertThat(result.contains(GROUP_DTO), is(true));
    assertThat(result.contains(GROUP_DTO_2), is(true));
  }

  @Test
  void fetchAllInactivePrivateGroupsSinceGivenDate_Should_UseCorrectMongoQuery()
      throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    LocalDateTime dateToCheck = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck)))
        .thenReturn(List.of(GROUP_DTO, GROUP_DTO_2));

    this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(dateToCheck);

    verify(rocketChatGroupClient, times(1))
        .fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck));
  }

  @Test
  void
      fetchAllInactivePrivateGroupsSinceGivenDate_Should_CallRocketChatApiMultipleTimes_When_ResultIsPaginated()
          throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    LocalDateTime dateToCheck = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck)))
        .thenReturn(List.of(GROUP_DTO, GROUP_DTO_2));

    this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(dateToCheck);

    verify(rocketChatGroupClient, times(1))
        .fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck));
  }

  @Test
  void
      fetchAllInactivePrivateGroupsSinceGivenDate_Should_CallRocketChatApiOnlyOnce_When_ResponseContainsTotalOfZeroElements()
          throws RocketChatUserNotInitializedException, RocketChatGetGroupsListAllException {

    LocalDateTime dateToCheck = LocalDateTime.of(2021, 1, 1, 0, 0, 0);

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatGroupClient.fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck)))
        .thenReturn(List.of());

    this.rocketChatService.fetchAllInactivePrivateGroupsSinceGivenDate(dateToCheck);

    verify(rocketChatGroupClient, times(1))
        .fetchAllInactivePrivateGroupsSinceGivenDate(eq(dateToCheck));
  }

  @Test
  void getRocketChatUserIdByUsername_Should_ThrowException_WhenRocketChatCallFails()
      throws RocketChatUserNotInitializedException, RocketChatGetUserIdException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getRocketChatUserIdByUsername(eq(USERNAME)))
        .thenThrow(
            new RocketChatGetUserIdException("Could not get users list from Rocket.Chat", null));

    try {
      this.rocketChatService.getRocketChatUserIdByUsername(USERNAME);
      fail("Expected exception: RocketChatGetUserIdException");
    } catch (RocketChatGetUserIdException ex) {
      assertTrue(true, "Excepted RocketChatGetUserIdException thrown");
      assertEquals("Could not get users list from Rocket.Chat", ex.getMessage());
    }
  }

  @Test
  void getRocketChatUserIdByUsername_Should_ThrowException_WhenFoundNoUserByUsername()
      throws RocketChatUserNotInitializedException, RocketChatGetUserIdException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getRocketChatUserIdByUsername(eq(USERNAME)))
        .thenThrow(new RocketChatGetUserIdException("Found 0 users by username"));

    try {
      this.rocketChatService.getRocketChatUserIdByUsername(USERNAME);
      fail("Expected exception: RocketChatGetUserIdException");
    } catch (RocketChatGetUserIdException ex) {
      assertTrue(true, "Excepted RocketChatGetUserIdException thrown");
      assertEquals("Found 0 users by username", ex.getMessage());
    }
  }

  @Test
  void getRocketChatUserIdByUsername_Should_ThrowException_WhenFound2UsersByUsername()
      throws RocketChatUserNotInitializedException, RocketChatGetUserIdException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getRocketChatUserIdByUsername(eq(USERNAME)))
        .thenThrow(new RocketChatGetUserIdException("Found 2 users by username"));

    try {
      this.rocketChatService.getRocketChatUserIdByUsername(USERNAME);
      fail("Expected exception: RocketChatGetUserIdException");
    } catch (RocketChatGetUserIdException ex) {
      assertTrue(true, "Excepted RocketChatGetUserIdException thrown");
      assertEquals("Found 2 users by username", ex.getMessage());
    }
  }

  @Test
  void getRocketChatUserIdByUsername_Should_ThrowException_WhenHttpStatusFromRocketChatCallIsNotOk()
      throws RocketChatUserNotInitializedException, RocketChatGetUserIdException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getRocketChatUserIdByUsername(eq(USERNAME)))
        .thenThrow(new RocketChatGetUserIdException("Could not get users list from Rocket.Chat"));

    try {
      this.rocketChatService.getRocketChatUserIdByUsername(USERNAME);
      fail("Expected exception: RocketChatGetUserIdException");
    } catch (RocketChatGetUserIdException ex) {
      assertTrue(true, "Excepted RocketChatGetUserIdException thrown");
      assertEquals("Could not get users list from Rocket.Chat", ex.getMessage());
    }
  }

  @Test
  void getRocketChatUserIdByUsername_Should_ReturnRocketChatUserId()
      throws RocketChatUserNotInitializedException, RocketChatGetUserIdException {

    when(rcCredentialsHelper.getTechnicalUser()).thenReturn(RC_CREDENTIALS_TECHNICAL_A);
    when(rocketChatUserClient.getRocketChatUserIdByUsername(eq(USERNAME)))
        .thenReturn(USERS_LIST_RESPONSE_DTO.getUsers()[0].getId());

    String result = this.rocketChatService.getRocketChatUserIdByUsername(USERNAME);

    assertThat(result, is(USERS_LIST_RESPONSE_DTO.getUsers()[0].getId()));
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

  private Document givenSubscription(String chatUserId, String username)
      throws JsonProcessingException {
    var doc = new LinkedHashMap<String, Object>();
    doc.put("_id", RandomStringUtils.randomAlphanumeric(17));
    doc.put("rid", RandomStringUtils.randomAlphanumeric(17));
    doc.put("name", RandomStringUtils.randomAlphanumeric(17));

    var user = new LinkedHashMap<>();
    user.put("_id", chatUserId);
    user.put("username", username);

    doc.put("u", user);

    var json = objectMapper.writeValueAsString(doc);

    return Document.parse(json);
  }
}
