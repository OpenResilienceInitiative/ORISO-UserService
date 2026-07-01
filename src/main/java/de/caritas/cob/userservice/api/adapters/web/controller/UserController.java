package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE;
import static java.util.Collections.singletonList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.apache.commons.lang3.BooleanUtils.isFalse;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatCredentials;
import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateEnquiryMessageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.DeleteUserAccountDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.E2eKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EmailNotificationsDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.EnquiryMessageDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkConsumeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MasterKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MobileTokenDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewMessageNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.RocketChatGroupIdDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateChatResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.config.VideoChatConfig;
import de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue;
import de.caritas.cob.userservice.api.container.SessionListQueryParameter;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateEnquiryMessageFacade;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.facade.assignsession.AssignSessionFacade;
import de.caritas.cob.userservice.api.facade.sessionlist.SessionListFacade;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.OtpInfoDTO;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.AskerImportService;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.DecryptionService;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.helper.EmailUrlDecoder;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionFilter;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UsersApi;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.InternalServerErrorException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller for user api requests */
@Slf4j
@RestController
@RequiredArgsConstructor
@Api(tags = "user-controller")
public class UserController implements UsersApi {

  private final @NotNull UserAccountService userAccountProvider;
  private final @NotNull SessionService sessionService;
  private final @NotNull AuthenticatedUser authenticatedUser;
  private final @NotNull CreateEnquiryMessageFacade createEnquiryMessageFacade;
  private final @NotNull ConsultantImportService consultantImportService;
  private final @NotNull EmailNotificationFacade emailNotificationFacade;
  private final @NotNull AskerImportService askerImportService;
  private final @NotNull SessionListFacade sessionListFacade;
  private final @NotNull ConsultantAgencyService consultantAgencyService;
  private final @NotNull AssignSessionFacade assignSessionFacade;
  private final @NotNull AssignEnquiryFacade assignEnquiryFacade;
  private final @NotNull DecryptionService decryptionService;
  private final @NotNull UserChatControllerDelegate userChatControllerDelegate;
  private final @NotNull CreateUserFacade createUserFacade;
  private final @NotNull CreateNewSessionFacade createNewSessionFacade;
  private final @NotNull ConsultantDataFacade consultantDataFacade;
  private final @NotNull SessionDataService sessionDataService;
  private final @NotNull SessionArchiveService sessionArchiveService;
  private final @NonNull IdentityClientConfig identityClientConfig;
  private final @NonNull IdentityManaging identityManager;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull Messaging messenger;
  private final @NonNull ConsultantDtoMapper consultantDtoMapper;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull ConsultantService consultantService;
  private final @NonNull ConsultantUpdateService consultantUpdateService;
  private final @NonNull ConsultantDataProvider consultantDataProvider;
  private final @NonNull AskerDataProvider askerDataProvider;
  private final @NonNull VideoChatConfig videoChatConfig;
  private final @NonNull KeycloakUserDataProvider keycloakUserDataProvider;
  private final @NotNull IdentityClient identityClient;
  private final @NonNull MagicLinkLoginService magicLinkLoginService;

  private final @NotNull AdminUserFacade adminUserFacade;

  @Value("${feature.topics.enabled}")
  private boolean featureTopicsEnabled;

  private final @NonNull SessionDeleteService sessionDeleteService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull UsernameTranscoder usernameTranscoder;

  @Override
  public ResponseEntity<Void> userExists(@NotNull String username) {
    val usernameAvailable = identityClient.isUsernameAvailable(username);
    val userExists = !usernameAvailable;
    if (userExists) {
      return ResponseEntity.ok().build();
    }
    return ResponseEntity.notFound().build();
  }

  @GetMapping("/users/availability/{username}")
  public ResponseEntity<Void> usernameAvailability(@PathVariable String username) {
    val usernameAvailable = identityClient.isUsernameAvailable(username);
    return usernameAvailable
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/magic-link/request")
  public ResponseEntity<Void> requestMagicLink(@Valid @RequestBody MagicLinkRequestDTO requestDTO) {
    var result = magicLinkLoginService.requestMagicLink(requestDTO.getUsername());
    if (result
        == de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService.MagicLinkRequestResult
            .NOT_ENABLED) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    return ResponseEntity.noContent().build();
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/magic-link/consume")
  public ResponseEntity<KeycloakLoginResponseDTO> consumeMagicLink(
      @Valid @RequestBody MagicLinkConsumeDTO consumeDTO) {
    return magicLinkLoginService
        .consumeMagicLink(consumeDTO.getToken())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.badRequest().build());
  }

  /**
   * Creates an user account and returns a 201 CREATED on success.
   *
   * @param user the {@link UserDTO}
   * @return {@link ResponseEntity} with possible registration conflict information in header
   */
  @Override
  public ResponseEntity<Void> registerUser(@Valid @RequestBody UserDTO user) {
    validateUserHasChosenTopicIfTopicsFeatureIsEnabled(user);
    user.setNewUserAccount(true);
    var sessionId = createUserFacade.createUserAccountWithInitializedConsultingType(user);

    HttpStatus status;
    if (user.isConsultantSet() && !messenger.markAsDirectConsultant(sessionId)) {
      status = HttpStatus.INTERNAL_SERVER_ERROR;
    } else {
      status = HttpStatus.CREATED;
    }

    return ResponseEntity.status(status).build();
  }

  private void validateUserHasChosenTopicIfTopicsFeatureIsEnabled(UserDTO user) {
    if (featureTopicsEnabled && user.getMainTopicId() == null) {
      throw new BadRequestException("Main topic id is required");
    }
  }

  /**
   * Creates a new session or chat-agency relation depending on the provided consulting type.
   *
   * @param rcToken Rocket.Chat token (optional for Matrix-backed sessions)
   * @param rcUserId Rocket.Chat user ID (optional for Matrix-backed sessions)
   * @param newRegistrationDto {@link NewRegistrationDto}
   * @return {@link ResponseEntity} containing {@link NewRegistrationResponseDto}
   */
  @Override
  public ResponseEntity<NewRegistrationResponseDto> registerNewConsultingType(
      @Valid @RequestBody NewRegistrationDto newRegistrationDto,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {

    var user = this.userAccountProvider.retrieveValidatedUser();
    var rocketChatCredentials =
        RocketChatCredentials.builder().rocketChatToken(rcToken).rocketChatUserId(rcUserId).build();

    var registrationResponse =
        createNewSessionFacade.initializeNewSession(
            newRegistrationDto,
            user,
            rocketChatCredentials,
            Lists.newArrayList(ONE_SESSION_PER_CONSULTING_TYPE));

    return new ResponseEntity<>(registrationResponse, registrationResponse.getStatus());
  }

  /**
   * Creates a new session or chat-agency relation depending on the provided topic.
   *
   * @param rcToken Rocket.Chat token (optional for Matrix-backed sessions)
   * @param rcUserId Rocket.Chat user ID (optional for Matrix-backed sessions)
   * @param newRegistrationDto {@link NewRegistrationDto}
   * @return {@link ResponseEntity} containing {@link NewRegistrationResponseDto}
   */
  @Override
  public ResponseEntity<NewRegistrationResponseDto> registerNewSession(
      @Valid de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto newRegistrationDto,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {
    var user = this.userAccountProvider.retrieveValidatedUser();
    var rocketChatCredentials =
        RocketChatCredentials.builder().rocketChatToken(rcToken).rocketChatUserId(rcUserId).build();

    /* Additional enquiries from the profile page go through the normal
    enquiry pipeline — the consultant is NOT pre-assigned here. The asker
    lands on the "write first message" screen, the enquiry sits in the
    agency queue, and a consultant picks it up. Direct-chat with a
    specific consultant is a separate flow (QR code / ?cid=… link).
    The empty constraint list keeps this endpoint permissive so existing
    askers can raise new enquiries even when they already had a past
    session for the same topic+agency. */
    var response =
        createNewSessionFacade.initializeNewSession(
            newRegistrationDto, user, rocketChatCredentials, Lists.newArrayList());

    return new ResponseEntity<>(response, response.getStatus());
  }

  /**
   * Assigns the given session to the calling consultant.
   *
   * @param sessionId Session ID (required)
   * @param rcUserId Rocket.Chat user ID (optional - not used in Matrix migration)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> acceptEnquiry(
      @NotNull @PathVariable Long sessionId, @RequestHeader(required = false) String rcUserId) {
    var session = sessionService.getSession(sessionId);

    // MATRIX MIGRATION: Removed groupId check - Matrix sessions don't have RocketChat groupId
    if (session.isEmpty()) {
      log.error("Internal Server Error: Session id {} is invalid, session not found.", sessionId);
      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    this.assignEnquiryFacade.assignRegisteredEnquiry(session.get(), consultant);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * @param sessionId Session Id (required)
   * @param rcToken Rocket.Chat token (optional for Matrix-backed sessions)
   * @param rcUserId Rocket.Chat user ID (optional for Matrix-backed sessions)
   * @param enquiryMessage Enquiry message (required)
   * @return {@link ResponseEntity} containing {@link CreateEnquiryMessageResponseDTO}
   */
  @Override
  public ResponseEntity<CreateEnquiryMessageResponseDTO> createEnquiryMessage(
      @NotNull @PathVariable Long sessionId,
      @Valid @RequestBody EnquiryMessageDTO enquiryMessage,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {

    var user = this.userAccountProvider.retrieveValidatedUser();
    var rocketChatCredentials =
        RocketChatCredentials.builder().rocketChatToken(rcToken).rocketChatUserId(rcUserId).build();
    var language = consultantDtoMapper.languageOf(enquiryMessage.getLanguage());
    var enquiryData =
        new EnquiryData(
            user,
            sessionId,
            enquiryMessage.getMessage(),
            language,
            rocketChatCredentials,
            enquiryMessage.getT(),
            null);

    var response = createEnquiryMessageFacade.createEnquiryMessage(enquiryData);

    return new ResponseEntity<>(response, HttpStatus.CREATED);
  }

  @Override
  public ResponseEntity<Void> deleteSessionAndInactiveUser(@NotNull @PathVariable Long sessionId) {
    sessionDeleteService.deleteSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Returns a list of sessions for the currently authenticated/logged in user.
   *
   * @param rcToken Rocket.Chat token (optional)
   * @return {@link ResponseEntity} of {@link UserSessionListResponseDTO}
   */
  @Override
  public ResponseEntity<UserSessionListResponseDTO> getSessionsForAuthenticatedUser(
      @RequestHeader(required = false) String rcToken) {

    var user = this.userAccountProvider.retrieveValidatedUser();

    // Use dummy RocketChat credentials if no token provided
    String token = rcToken != null ? rcToken : "dummy-rc-token";
    String rcUserId = user.getRcUserId() != null ? user.getRcUserId() : "dummy-rc-user";

    var rocketChatCredentials =
        RocketChatCredentials.builder().rocketChatUserId(rcUserId).rocketChatToken(token).build();

    var userSessionsDTO =
        sessionListFacade.retrieveSortedSessionsForAuthenticatedUser(
            user.getUserId(), rocketChatCredentials);

    consultantDataFacade.addConsultantDisplayNameToSessionList(userSessionsDTO);

    return isNotEmpty(userSessionsDTO.getSessions())
        ? new ResponseEntity<>(userSessionsDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * Returns a list of sessions for the currently authenticated/logged in user and given RocketChat
   * group IDs.
   *
   * @param rcToken Rocket.Chat token (required)
   * @return {@link ResponseEntity} of {@link UserSessionListResponseDTO}
   */
  @Override
  public ResponseEntity<GroupSessionListResponseDTO> getSessionsForGroupIds(
      @NotNull @RequestParam List<String> rcGroupIds,
      @RequestHeader(required = false) String rcToken) {
    GroupSessionListResponseDTO groupSessionList;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      groupSessionList =
          sessionListFacade.retrieveSessionsForAuthenticatedConsultantByGroupIds(
              consultant, rcGroupIds, authenticatedUser.getRoles());
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      var rocketChatCredentials =
          RocketChatCredentials.builder()
              .rocketChatUserId(user.getRcUserId())
              .rocketChatToken(rcToken != null ? rcToken : "")
              .build();
      groupSessionList =
          sessionListFacade.retrieveSessionsForAuthenticatedUserByGroupIds(
              user.getUserId(), rcGroupIds, rocketChatCredentials, authenticatedUser.getRoles());
    }

    consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

    return isNotEmpty(groupSessionList.getSessions())
        ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  // MATRIX MIGRATION: Added manual mapping since generated interface hasn't updated yet
  // Mapped to both direct path and /service/ prefix (API gateway) so both routes resolve
  @GetMapping(
      value = {"/users/sessions/room/{sessionId}", "/service/users/sessions/room/{sessionId}"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<GroupSessionListResponseDTO> getSessionForId(
      @PathVariable Long sessionId,
      @RequestHeader(value = "RCToken", required = false) String rcToken) {
    log.info(
        "🔍 GET /users/sessions/room/{} - sessionId: {}, rcToken: {}",
        sessionId,
        sessionId,
        rcToken != null ? "present" : "null");

    try {
      GroupSessionListResponseDTO groupSessionList;
      if (authenticatedUser.isConsultant()) {
        var consultant = userAccountProvider.retrieveValidatedConsultant();
        log.info("🔍 User is CONSULTANT: {}, id: {}", consultant.getUsername(), consultant.getId());

        // MATRIX MIGRATION: Try to find as session first, then as chat
        log.info("🔍 Step 1: Trying to find as SESSION with ID: {}", sessionId);
        groupSessionList =
            sessionListFacade.retrieveSessionsForAuthenticatedConsultantBySessionIds(
                consultant, singletonList(sessionId), authenticatedUser.getRoles());

        log.info(
            "🔍 Step 1 result: {} sessions found",
            groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);

        // If no session found, try to find as a chat (group chat)
        if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
          log.info("🔍 Step 2: No session found, trying to find as CHAT with ID: {}", sessionId);
          String token = rcToken != null ? rcToken : "dummy-rc-token";
          var rocketChatCredentials =
              RocketChatCredentials.builder()
                  .rocketChatUserId(consultant.getRocketChatId())
                  .rocketChatToken(token)
                  .build();
          groupSessionList =
              sessionListFacade.retrieveChatsForConsultantByChatIds(
                  consultant, singletonList(sessionId), rocketChatCredentials);

          log.info(
              "🔍 Step 2 result: {} chats found",
              groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);
        }
      } else {
        var user = userAccountProvider.retrieveValidatedUser();
        log.info("🔍 User is USER/ASKER: {}, id: {}", user.getUsername(), user.getUserId());

        // MATRIX MIGRATION: Use dummy RocketChat credentials if no token provided
        String token = rcToken != null ? rcToken : "dummy-rc-token";
        String rcUserId = user.getRcUserId() != null ? user.getRcUserId() : "dummy-rc-user";
        var rocketChatCredentials =
            RocketChatCredentials.builder()
                .rocketChatUserId(rcUserId)
                .rocketChatToken(token)
                .build();

        log.info("🔍 Step 1: Trying to find as SESSION with ID: {}", sessionId);
        groupSessionList =
            sessionListFacade.retrieveSessionsForAuthenticatedUserBySessionIds(
                user.getUserId(),
                singletonList(sessionId),
                rocketChatCredentials,
                authenticatedUser.getRoles());

        log.info(
            "🔍 Step 1 result: {} sessions found",
            groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);

        // If no session found, try to find as a chat (group chat)
        if (groupSessionList.getSessions() == null || groupSessionList.getSessions().isEmpty()) {
          log.info("🔍 Step 2: No session found, trying to find as CHAT with ID: {}", sessionId);
          groupSessionList =
              sessionListFacade.retrieveChatsForUserByChatIds(
                  singletonList(sessionId), rocketChatCredentials);

          log.info(
              "🔍 Step 2 result: {} chats found",
              groupSessionList.getSessions() != null ? groupSessionList.getSessions().size() : 0);
        }
      }

      consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

      return isNotEmpty(groupSessionList.getSessions())
          ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
          : new ResponseEntity<>(HttpStatus.NO_CONTENT);
    } catch (Exception e) {
      log.error("Failed to load session room {}: {}", sessionId, e.getMessage(), e);
      return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }
  }

  @Override
  public ResponseEntity<GroupSessionListResponseDTO> getChatById(
      @NotNull String rcToken, @NotNull Long chatId) {
    GroupSessionListResponseDTO groupSessionList;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      var rocketChatCredentials =
          RocketChatCredentials.builder()
              .rocketChatUserId(consultant.getRocketChatId())
              .rocketChatToken(rcToken)
              .build();
      groupSessionList =
          sessionListFacade.retrieveChatsForConsultantByChatIds(
              consultant, singletonList(chatId), rocketChatCredentials);
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      var rocketChatCredentials =
          RocketChatCredentials.builder()
              .rocketChatUserId(user.getRcUserId())
              .rocketChatToken(rcToken)
              .build();
      groupSessionList =
          sessionListFacade.retrieveChatsForUserByChatIds(
              singletonList(chatId), rocketChatCredentials);
    }

    consultantDataFacade.addConsultantDisplayNameToSessionList(groupSessionList);

    return isNotEmpty(groupSessionList.getSessions())
        ? new ResponseEntity<>(groupSessionList, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * Updates the absence (and its message) for the calling consultant.
   *
   * @param absence {@link AbsenceDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updateAbsence(@Valid @RequestBody AbsenceDTO absence) {
    var consultant = userAccountProvider.retrieveValidatedConsultant();
    this.consultantDataFacade.updateConsultantAbsent(consultant, absence);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<EmailNotificationsDTO> getUserEmailNotifications(@NotNull String email) {

    Optional<Consultant> consultantByEmail = userAccountProvider.findConsultantByEmail(email);
    if (consultantByEmail.isPresent()) {
      return new ResponseEntity<>(getEmailNotifications(consultantByEmail.get()), HttpStatus.OK);
    } else {
      Optional<User> userByEmail = userAccountProvider.findUserByEmail(email);
      if (userByEmail.isPresent()) {
        return new ResponseEntity<>(getEmailNotifications(userByEmail.get()), HttpStatus.OK);
      } else {
        throw new NotFoundException("No adviceseeker nor consultant with given email found.");
      }
    }
  }

  private EmailNotificationsDTO getEmailNotifications(Consultant consultant) {
    var consultantDTO = consultantDataProvider.retrieveData(consultant);
    return consultantDTO.getEmailNotifications();
  }

  private EmailNotificationsDTO getEmailNotifications(User user) {
    var userDTO = askerDataProvider.retrieveData(user);
    return userDTO.getEmailNotifications();
  }

  /**
   * Gets the user data for the current logged-in user depending on his user role.
   *
   * @return {@link ResponseEntity} containing {@link UserDataResponseDTO}
   */
  @Override
  public ResponseEntity<UserDataResponseDTO> getUserData() {
    UserDataResponseDTO partialUserData;
    if (authenticatedUser.isConsultant()) {
      var consultant = userAccountProvider.retrieveValidatedConsultant();
      partialUserData = consultantDataProvider.retrieveData(consultant);
      enrichConsultantDisplayName(partialUserData);
      enrichConsultantAvailability(partialUserData);
    } else if (isTenantAdmin() || isAgencyAdmin()) {
      partialUserData = keycloakUserDataProvider.retrieveAuthenticatedUserData();
    } else {
      var user = userAccountProvider.retrieveValidatedUser();
      partialUserData = askerDataProvider.retrieveData(user);
    }
    var otpInfoDTO = retrieveOtpCredentialIfAllowed();

    var fullUserData =
        userDtoMapper.userDataOf(
            partialUserData,
            otpInfoDTO,
            videoChatConfig.getE2eEncryptionEnabled(),
            identityClientConfig.getDisplayNameAllowedForConsultants());

    return new ResponseEntity<>(fullUserData, HttpStatus.OK);
  }

  private void enrichConsultantDisplayName(UserDataResponseDTO userData) {
    try {
      accountManager
          .findConsultant(authenticatedUser.getUserId())
          .ifPresent(
              consultantMap -> userData.setDisplayName(userDtoMapper.displayNameOf(consultantMap)));
    } catch (Exception ex) {
      log.warn(
          "Could not enrich display name for consultant {}; returning user data without display name",
          authenticatedUser.getUserId(),
          ex);
    }
  }

  private void enrichConsultantAvailability(UserDataResponseDTO userData) {
    try {
      userData.setAvailable(messenger.getAvailability(authenticatedUser.getUserId()));
    } catch (Exception ex) {
      log.warn(
          "Could not enrich availability for consultant {}; returning user data without availability",
          authenticatedUser.getUserId(),
          ex);
    }
  }

  private OtpInfoDTO retrieveOtpCredentialIfAllowed() {
    if (!identityClientConfig.isOtpAllowed(authenticatedUser.getRoles())) {
      return null;
    }
    try {
      return identityManager.getOtpCredential(
          usernameTranscoder.encodeUsername(authenticatedUser.getUsername()));
    } catch (Exception ex) {
      log.warn(
          "Could not retrieve OTP credential for authenticated user {}; returning user data without OTP state",
          authenticatedUser.getUserId(),
          ex);
      return null;
    }
  }

  private boolean isAgencyAdmin() {
    return authenticatedUser.isAgencySuperAdmin() || authenticatedUser.isRestrictedAgencyAdmin();
  }

  private boolean isTenantAdmin() {
    return authenticatedUser.isSingleTenantAdmin() || authenticatedUser.isTenantSuperAdmin();
  }

  @Override
  public ResponseEntity<Void> patchUser(@Valid PatchUserDTO patchUserDTO) {
    validateMagicLinkTogglePrerequisites(patchUserDTO);

    var userId = authenticatedUser.getUserId();
    var patchMap =
        userDtoMapper
            .mapOf(patchUserDTO, authenticatedUser)
            .orElseThrow(
                () -> new BadRequestException("Invalid payload: at least one property expected"));

    Optional<Map<String, Object>> patchResponse = accountManager.patchUser(patchMap);
    if (patchResponse.isEmpty()) {
      throw new IllegalStateException("patch response not valid");
    }

    userDtoMapper
        .preferredLanguageOf(patchUserDTO)
        .ifPresent(lang -> identityManager.changeLanguage(userId, lang));

    // MATRIX MIGRATION: Gracefully handle RocketChat unavailability
    userDtoMapper
        .availableOf(patchUserDTO)
        .filter(available -> authenticatedUser.isConsultant())
        .ifPresent(
            available -> {
              try {
                messenger.setAvailability(userId, available);
              } catch (Exception e) {
                log.warn(
                    "RocketChat is not available (expected during Matrix migration), skipping setAvailability: {}",
                    e.getMessage());
              }
            });

    return ResponseEntity.noContent().build();
  }

  private void validateMagicLinkTogglePrerequisites(PatchUserDTO patchUserDTO) {
    if (!Boolean.TRUE.equals(patchUserDTO.getMagicLinkLoginEnabled())) {
      return;
    }

    String email = null;
    if (authenticatedUser.isConsultant()) {
      email = userAccountProvider.retrieveValidatedConsultant().getEmail();
    } else if (authenticatedUser.isAdviceSeeker()) {
      email = userAccountProvider.retrieveValidatedUser().getEmail();
    }

    boolean hasRealEmail =
        nonNull(email)
            && !email.isBlank()
            && !email.endsWith(identityClientConfig.getEmailDummySuffix());
    if (!hasRealEmail) {
      throw new BadRequestException(
          "Magic link login can only be enabled when a profile email is set.");
    }
  }

  /**
   * Updates the data for the current logged in consultant.
   *
   * @param updateConsultantDTO (required) the request {@link UpdateConsultantDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateConsultantData(@Valid UpdateConsultantDTO updateConsultantDTO) {
    var consultantId = authenticatedUser.getUserId();
    var consultant =
        consultantService
            .getConsultant(consultantId)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantId));

    var updateAdminConsultantDTO =
        consultantDtoMapper.updateAdminConsultantOf(updateConsultantDTO, consultant);
    consultantUpdateService.updateConsultant(consultantId, updateAdminConsultantDTO);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<LanguageResponseDTO> getLanguages(@NotNull Long agencyId) {
    var languageCodes = consultantAgencyService.getLanguageCodesOfAgency(agencyId);
    var languageResponseDTO = consultantDtoMapper.languageResponseDtoOf(languageCodes);

    return new ResponseEntity<>(languageResponseDTO, HttpStatus.OK);
  }

  /**
   * Returns a list of sessions for the currently authenticated consultant depending on the
   * submitted sessionStatus.
   *
   * @param rcToken Rocket.Chat token (required, provided by RocketChatConfig as dummy if missing)
   * @param offset Number of items where to start in the query (0 = first item) (required)
   * @param count Number of items which are being returned (required)
   * @param filter Information on how to filter the list (required)
   * @param status Session status type (optional)
   * @return {@link ResponseEntity} containing {@link ConsultantSessionListResponseDTO}
   */
  @Override
  public ResponseEntity<ConsultantSessionListResponseDTO> getSessionsForAuthenticatedConsultant(
      @NotNull @RequestHeader String rcToken,
      @NotNull @Min(value = 0) Integer offset,
      @NotNull @Min(value = 1) Integer count,
      @NotNull @RequestParam String filter,
      @RequestParam Integer status) {

    var consultant = this.userAccountProvider.retrieveValidatedConsultant();

    ConsultantSessionListResponseDTO consultantSessionListResponseDTO = null;
    var optionalSessionFilter = SessionFilter.getByValue(filter);
    if (optionalSessionFilter.isPresent()) {

      var sessionListQueryParameter =
          SessionListQueryParameter.builder()
              .sessionStatus(status)
              .count(count)
              .offset(offset)
              .sessionFilter(optionalSessionFilter.get())
              .build();

      consultantSessionListResponseDTO =
          sessionListFacade.retrieveSessionsDtoForAuthenticatedConsultant(
              consultant, sessionListQueryParameter);
    }

    return nonNull(consultantSessionListResponseDTO)
            && isNotEmpty(consultantSessionListResponseDTO.getSessions())
        ? new ResponseEntity<>(consultantSessionListResponseDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * Returns a list of team consulting sessions for the currently authenticated consultant.
   *
   * @param rcToken Rocket.Chat token (required)
   * @param offset Number of items where to start in the query (0 = first item) (required)
   * @param count Number of items which are being returned (required)
   * @param filter Information on how to filter the list (required)
   * @return {@link ResponseEntity} containing {@link ConsultantSessionListResponseDTO}
   */
  @Override
  public ResponseEntity<ConsultantSessionListResponseDTO> getTeamSessionsForAuthenticatedConsultant(
      @NotNull @RequestHeader String rcToken,
      @NotNull @Min(value = 0) Integer offset,
      @NotNull @Min(value = 1) Integer count,
      @NotNull @RequestParam String filter) {

    var consultant = this.userAccountProvider.retrieveValidatedTeamConsultant();

    ConsultantSessionListResponseDTO teamSessionListDTO = null;
    var optionalSessionFilter = SessionFilter.getByValue(filter);
    if (optionalSessionFilter.isPresent()) {

      var sessionListQueryParameter =
          SessionListQueryParameter.builder()
              .count(count)
              .offset(offset)
              .sessionFilter(optionalSessionFilter.get())
              .build();

      teamSessionListDTO =
          sessionListFacade.retrieveTeamSessionsDtoForAuthenticatedConsultant(
              consultant, rcToken, sessionListQueryParameter);
    }

    return nonNull(teamSessionListDTO) && isNotEmpty(teamSessionListDTO.getSessions())
        ? new ResponseEntity<>(teamSessionListDTO, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  /**
   * Imports a file list of consultants. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importConsultants() {

    consultantImportService.startImport();

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Imports a file list of askers. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importAskers() {

    askerImportService.startImport();

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Imports a file list of askers without a session. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importAskersWithoutSession() {

    askerImportService.startImportForAskersWithoutSession();

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Sends email notifications to the user(s) if there has been a new answer. Uses the provided
   * Keycloak authorization token for user verification (user role). This means that the user that
   * wrote the answer should also call this method.
   *
   * @param newMessageNotificationDTO (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> sendNewMessageNotification(
      @Valid @RequestBody NewMessageNotificationDTO newMessageNotificationDTO) {

    emailNotificationFacade.sendNewMessageNotification(
        newMessageNotificationDTO.getRcGroupId(),
        authenticatedUser.getRoles(),
        authenticatedUser.getUserId(),
        TenantContext.getCurrentTenantData());
    eventNotificationService.createMessageNotificationFromRoom(
        newMessageNotificationDTO.getRcGroupId(), authenticatedUser.getUserId(), null, false);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Sends email notification for reassign request to advice seeker if the property isConfirmed of
   * {@link ReassignmentNotificationDTO} is null or false. Send email confirmation notification to
   * consultant if property isConfirmed of {@link * ReassignmentNotificationDTO} is true.
   *
   * @param reassignmentNotificationDTO (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> sendReassignmentNotification(
      @Valid @RequestBody ReassignmentNotificationDTO reassignmentNotificationDTO) {

    if (isTrue(reassignmentNotificationDTO.getIsConfirmed())) {
      emailNotificationFacade.sendReassignConfirmationNotification(
          reassignmentNotificationDTO, TenantContext.getCurrentTenantData());
    } else {
      emailNotificationFacade.sendReassignRequestNotification(
          reassignmentNotificationDTO.getRcGroupId(), TenantContext.getCurrentTenantData());
    }

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Returns all consultants of the provided agency id as a list of {@link ConsultantResponseDTO}.
   *
   * @param agencyId Agency Id (required)
   * @return {@link ResponseEntity} containing {@link List} of {@link ConsultantResponseDTO}
   */
  @Override
  public ResponseEntity<List<ConsultantResponseDTO>> getConsultants(
      @NotNull @RequestParam Long agencyId) {

    var consultants = consultantAgencyService.getConsultantsOfAgency(agencyId);

    return isNotEmpty(consultants)
        ? new ResponseEntity<>(consultants, HttpStatus.OK)
        : new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  @Override
  public ResponseEntity<ConsultantSearchResultDTO> searchConsultants(
      @NotNull @Size(min = 1) String query,
      @NotNull @Min(value = 1) Integer page,
      @NotNull @Min(value = 1) Integer perPage,
      @NotNull @Pattern(regexp = "(?i)^(FIRSTNAME|LASTNAME|EMAIL|UPDATE_DATE)$") String field,
      @NotNull @Pattern(regexp = "(?i)^(ASC|DESC)$") String order) {
    var decodedInfix = determineDecodedInfix(query).trim();
    var isAscending = order.equalsIgnoreCase("asc");
    var mappedField = consultantDtoMapper.mappedFieldOf(field);
    var resultMap =
        accountManager.findConsultantsByInfix(
            decodedInfix,
            authenticatedUser.hasRestrictedAgencyPriviliges(),
            getAgenciesToFilterConsultants(),
            page - 1,
            perPage,
            mappedField,
            isAscending);

    var result =
        consultantDtoMapper.consultantSearchResultOf(resultMap, query, page, perPage, field, order);

    if (authenticatedUser.hasRestrictedAgencyPriviliges() && result.getEmbedded() != null) {
      result
          .getEmbedded()
          .forEach(
              response ->
                  removeAgenciesWithoutAccessRight(response, getAgenciesToFilterConsultants()));
    }

    return ResponseEntity.ok(result);
  }

  private String determineDecodedInfix(String query) {
    if (EmailValidator.getInstance().isValid(query)) {
      return EmailUrlDecoder.decodeEmailQuery(query);
    } else {
      return URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
    }
  }

  private void removeAgenciesWithoutAccessRight(
      ConsultantAdminResponseDTO response, Collection<Long> agenciesToFilterConsultants) {
    List<AgencyAdminResponseDTO> agencies = response.getEmbedded().getAgencies();
    List<AgencyAdminResponseDTO> filteredAgencies =
        agencies.stream()
            .filter(agency -> agenciesToFilterConsultants.contains(agency.getId()))
            .collect(Collectors.toList());
    response.getEmbedded().setAgencies(filteredAgencies);
  }

  private Collection<Long> getAgenciesToFilterConsultants() {
    Collection<Long> agenciesToFilterConsultants = Lists.newArrayList();
    if (authenticatedUser.hasRestrictedAgencyPriviliges()) {
      agenciesToFilterConsultants =
          adminUserFacade.findAdminUserAgencyIds(authenticatedUser.getUserId());
    }
    return agenciesToFilterConsultants;
  }

  /**
   * Assigns a session (the provided session id) to the provided consultant id.
   *
   * @param sessionId Session Id (required)
   * @param consultantId Consultant Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> assignSession(
      @NotNull @PathVariable Long sessionId, @NotNull @PathVariable String consultantId) {

    var session = sessionService.getSession(sessionId);
    if (session.isEmpty()) {
      log.error("Internal Server Error: Session with id {} not found.", sessionId);

      return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    var userId = authenticatedUser.getUserId();
    // Check if the calling consultant has the correct right to assign the enquiry to a consultant
    if (session.get().getStatus().equals(SessionStatus.NEW)
        && !authenticatedUser
            .getGrantedAuthorities()
            .contains(AuthorityValue.ASSIGN_CONSULTANT_TO_ENQUIRY)) {
      LogService.logForbidden(
          String.format(
              "The calling consultant with id %s does not have the authority to assign the enquiry to a consultant.",
              userId));

      return new ResponseEntity<>(HttpStatus.FORBIDDEN);
    }

    var consultantToAssign = userAccountProvider.retrieveValidatedConsultantById(consultantId);
    var consultantToKeep = consultantService.getConsultant(userId).orElse(null);
    assignSessionFacade.assignSession(session.get(), consultantToAssign, consultantToKeep);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> removeFromSession(
      @NotNull Long sessionId, @NotNull UUID consultantId) {
    var consultantMap =
        accountManager
            .findConsultant(consultantId.toString())
            .orElseThrow(
                () -> new NotFoundException("Consultant (%s) not found", consultantId.toString()));

    var sessionMap =
        messenger
            .findSession(sessionId)
            .orElseThrow(() -> new NotFoundException("Session (%s) not found", sessionId));

    var chatId = consultantDtoMapper.chatIdOf(sessionMap);
    var chatUserId = userDtoMapper.chatUserIdOf(consultantMap);
    if (!messenger.removeUserFromSession(chatUserId, chatId)) {
      var message =
          String.format(
              "Could not remove consultant (%s) from session (%s)", consultantId, sessionId);
      throw new InternalServerErrorException(message);
    }

    return ResponseEntity.noContent().build();
  }

  /**
   * Changes the (Keycloak) password of the currently authenticated user.
   *
   * @param passwordDTO (required) {@link PasswordDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updatePassword(@Valid @RequestBody PasswordDTO passwordDTO) {
    var username = authenticatedUser.getUsername();
    var encodedUsername = usernameTranscoder.encodeUsername(username);
    if (!identityManager.validatePasswordIgnoring2fa(
        encodedUsername, passwordDTO.getOldPassword())) {
      var message = String.format("Could not log in user %s into Keycloak", username);
      throw new BadRequestException(message);
    }

    var userId = authenticatedUser.getUserId();
    if (!identityManager.changePassword(userId, passwordDTO.getNewPassword())) {
      var message = String.format("Could not update password of user %s", userId);
      throw new InternalServerErrorException(message);
    }

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Updates the master key fragment for the en-/decryption of messages.
   *
   * @param masterKey {@link MasterKeyDTO} (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updateKey(@Valid @RequestBody MasterKeyDTO masterKey) {
    if (!decryptionService.getMasterKey().equals(masterKey.getMasterKey())) {
      decryptionService.updateMasterKey(masterKey.getMasterKey());
      LogService.logInfo("MasterKey updated");
      return new ResponseEntity<>(HttpStatus.OK);
    }

    return new ResponseEntity<>(HttpStatus.CONFLICT);
  }

  @Override
  public ResponseEntity<Void> updateE2eInChats(@Valid E2eKeyDTO e2eKeyDTO) {
    var userId = authenticatedUser.getUserId();
    var user =
        authenticatedUser.isConsultant()
            ? accountManager.findConsultant(userId).orElseThrow()
            : accountManager.findAdviceSeeker(userId).orElseThrow();

    var chatUserId = userDtoMapper.chatUserIdOf(user);
    var username = authenticatedUser.getUsername();
    if (isNull(chatUserId)) {
      if (isAdviceSeekerWithoutEnquiryMessageWritten()) {
        return ResponseEntity.accepted().build();
      }
      var message = String.format("Chat-user ID of user %s unknown", username);
      throw new InternalServerErrorException(message);
    }

    if (isFalse(messenger.updateE2eKeys(chatUserId, e2eKeyDTO.getPublicKey()))) {
      var message = String.format("Setting E2E keys in user %s's chats failed", username);
      throw new InternalServerErrorException(message);
    }

    return ResponseEntity.noContent().build();
  }

  private boolean isAdviceSeekerWithoutEnquiryMessageWritten() {
    if (authenticatedUser.isAdviceSeeker()) {
      var adviceSeeker = userAccountProvider.retrieveValidatedUser();
      return adviceSeeker.getCreateDate().isEqual(adviceSeeker.getUpdateDate());
    }
    return false;
  }

  /**
   * Creates a new chat with the given details and returns the generated chat link.
   *
   * <p>The old version (v1) assumed, that the consultant is assigned to exactly one agency.
   *
   * @param chatDTO {@link ChatDTO} (required)
   * @return {@link ResponseEntity} containing {@link CreateChatResponseDTO}
   */
  @Override
  public ResponseEntity<CreateChatResponseDTO> createChatV1(@Valid @RequestBody ChatDTO chatDTO) {
    return userChatControllerDelegate.createChatV1(chatDTO);
  }

  /**
   * Creates a new chat with the given details and returns the generated chat link.
   *
   * <p>The new version (v2) creates chat_agency relations for all agencies the consultant is
   * assigned, but ignores the consulting_type stored in the chat.
   *
   * @param chatDTO {@link ChatDTO} (required)
   * @return {@link ResponseEntity} containing {@link CreateChatResponseDTO}
   */
  @Override
  public ResponseEntity<CreateChatResponseDTO> createChatV2(@Valid @RequestBody ChatDTO chatDTO) {
    return userChatControllerDelegate.createChatV2(chatDTO);
  }

  /**
   * Starts a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> startChat(@NotNull @PathVariable Long chatId) {
    return userChatControllerDelegate.startChat(chatId);
  }

  /**
   * Gets the chat info of provided chat ID.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link ChatInfoResponseDTO}
   */
  @Override
  public ResponseEntity<ChatInfoResponseDTO> getChat(@NotNull Long chatId) {
    return userChatControllerDelegate.getChat(chatId);
  }

  /**
   * Assign a chat, resolved using the group id.
   *
   * @param groupId the rocket chat group uuid part (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> assignChat(@NotNull String groupId) {
    return userChatControllerDelegate.assignChat(groupId);
  }

  /**
   * Join a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> joinChat(@NotNull @PathVariable Long chatId) {
    return userChatControllerDelegate.joinChat(chatId);
  }

  @Override
  public ResponseEntity<Void> verifyCanModerateChat(@NotNull @PathVariable Long chatId) {
    return userChatControllerDelegate.verifyCanModerateChat(chatId);
  }

  /**
   * Stops the given chat (chatId). Deletes all users and messages from the Rocket.Chat room
   * (repetitive chat) or deletes the whole room (singular chat).
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> stopChat(@NotNull Long chatId) {
    return userChatControllerDelegate.stopChat(chatId);
  }

  /**
   * Gets the members of a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link ChatMembersResponseDTO}
   */
  @Override
  public ResponseEntity<ChatMembersResponseDTO> getChatMembers(@NotNull @PathVariable Long chatId) {
    return userChatControllerDelegate.getChatMembers(chatId);
  }

  /**
   * Leave a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> leaveChat(@NotNull @PathVariable Long chatId) {
    return userChatControllerDelegate.leaveChat(chatId);
  }

  /**
   * Updates the settings of the given chat.
   *
   * @param chatId Chat Id (required)
   * @param chatDTO {@link ChatDTO} (required)
   * @return {@link ResponseEntity} containing {@link UpdateChatResponseDTO}
   */
  @Override
  public ResponseEntity<UpdateChatResponseDTO> updateChat(
      @NotNull @PathVariable Long chatId, @Valid @RequestBody ChatDTO chatDTO) {
    return userChatControllerDelegate.updateChat(chatId, chatDTO);
  }

  @Override
  public ResponseEntity<Void> banFromChat(
      @NotNull String token,
      @NotNull @Size(min = 17, max = 17) String chatUserId,
      @NotNull @Min(value = 0L) Long chatId) {
    return userChatControllerDelegate.banFromChat(chatUserId, chatId);
  }

  /**
   * Get a specific {@link ConsultantSessionDTO} for a consultant.
   *
   * @param sessionId Session id (required)
   * @return {@link ResponseEntity} containing {@link ConsultantSessionDTO}
   */
  @Override
  public ResponseEntity<ConsultantSessionDTO> fetchSessionForConsultant(
      @NotNull @PathVariable Long sessionId) {

    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    var consultantSessionDTO = sessionService.fetchSessionForConsultant(sessionId, consultant);
    return new ResponseEntity<>(consultantSessionDTO, HttpStatus.OK);
  }

  /**
   * Updates or sets the email address for the current authenticated user.
   *
   * @param emailAddress the email address to set
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateEmailAddress(@Valid String emailAddress) {
    var lowerCaseEmail = Optional.of(emailAddress.toLowerCase());
    userAccountProvider.changeUserAccountEmailAddress(lowerCaseEmail);

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Sets the user's email address to its default.
   *
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> deleteEmailAddress() {
    userAccountProvider.changeUserAccountEmailAddress(Optional.empty());

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Flags an user account for deletion and deactivates the Keycloak account.
   *
   * @param deleteUserAccountDTO (required) {@link DeleteUserAccountDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> deactivateAndFlagUserAccountForDeletion(
      @Valid DeleteUserAccountDTO deleteUserAccountDTO) {
    var username = authenticatedUser.getUsername();
    var password = deleteUserAccountDTO.getPassword();
    var encodedUsername = usernameTranscoder.encodeUsername(username);
    if (!identityManager.validatePasswordIgnoring2fa(encodedUsername, password)) {
      var message = String.format("Could not log in user %s into Keycloak", username);
      throw new BadRequestException(message);
    }

    userAccountProvider.deactivateAndFlagUserAccountForDeletion();

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Updates or sets the mobile client token for the current authenticated user.
   *
   * @param mobileTokenDTO (required) the mobile device identifier {@link MobileTokenDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateMobileToken(@Valid MobileTokenDTO mobileTokenDTO) {
    this.userAccountProvider.updateUserMobileToken(mobileTokenDTO.getToken());
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Adds a mobile client token for the current authenticated user.
   *
   * @param mobileTokenDTO (required) the mobile device identifier {@link MobileTokenDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> addMobileAppToken(@Valid MobileTokenDTO mobileTokenDTO) {
    this.userAccountProvider.addMobileAppToken(mobileTokenDTO.getToken());
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Updates the session data for the given session.
   *
   * @param sessionId (required) session ID
   * @param sessionDataDTO (required) {@link SessionDataDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateSessionData(
      @NotNull @PathVariable Long sessionId, @Valid SessionDataDTO sessionDataDTO) {
    this.sessionDataService.saveSessionData(sessionId, sessionDataDTO);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Put a session into the archive.
   *
   * @param sessionId (required) session ID
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> archiveSession(@NotNull @PathVariable Long sessionId) {
    this.sessionArchiveService.archiveSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Dearchive a session.
   *
   * @param sessionId (required) session ID
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> dearchiveSession(@NotNull @PathVariable Long sessionId) {
    this.sessionArchiveService.dearchiveSession(sessionId);
    return new ResponseEntity<>(HttpStatus.OK);
  }

  @Override
  public ResponseEntity<Void> startTwoFactorAuthByEmailSetup(@Valid EmailDTO emailDTO) {
    var username = usernameTranscoder.encodeUsername(authenticatedUser.getUsername());
    var email = emailDTO.getEmail().toLowerCase();

    if (!identityManager.isEmailAvailableOrOwn(username, email)) {
      return new ResponseEntity<>(HttpStatus.PRECONDITION_FAILED);
    }

    identityManager
        .setUpOneTimePassword(username, email)
        .ifPresent(
            message -> {
              throw new InternalServerErrorException(message);
            });

    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> finishTwoFactorAuthByEmailSetup(
      @NotNull @Pattern(regexp = "[0-9]{6}") String tan) {
    var username = usernameTranscoder.encodeUsername(authenticatedUser.getUsername());
    var validationResult = identityManager.validateOneTimePassword(username, tan);

    if (Boolean.parseBoolean(validationResult.get("created"))) {
      var patchMap = userDtoMapper.mapOf(validationResult.get("email"), authenticatedUser);
      accountManager.patchUser(patchMap);
      return ResponseEntity.noContent().build();
    }
    if (Boolean.parseBoolean(validationResult.get("attemptsLeft"))) {
      return ResponseEntity.badRequest().build();
    }
    if (Boolean.parseBoolean(validationResult.get("createdBefore"))) {
      return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).build();
    }

    return new ResponseEntity<>(HttpStatus.TOO_MANY_REQUESTS);
  }

  /**
   * Activates 2FA by mobile app for the calling user.
   *
   * @param oneTimePasswordDTO (required) {@link OneTimePasswordDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> activateTwoFactorAuthByApp(OneTimePasswordDTO oneTimePasswordDTO) {
    if (authenticatedUser.isAdviceSeeker()
        && isFalse(identityClientConfig.getOtpAllowedForUsers())) {
      throw new ConflictException("2FA is disabled for user role");
    }
    if (authenticatedUser.isConsultant()
        && isFalse(identityClientConfig.getOtpAllowedForConsultants())) {
      throw new ConflictException("2FA is disabled for consultant role");
    }
    if (authenticatedUser.isSingleTenantAdmin()
        && isFalse(identityClientConfig.getOtpAllowedForSingleTenantAdmins())) {
      throw new ConflictException("2FA is disabled for single tenant admin role");
    }
    if (authenticatedUser.isTenantSuperAdmin()
        && isFalse(identityClientConfig.getOtpAllowedForTenantSuperAdmins())) {
      throw new ConflictException("2FA is disabled for tenant admin role");
    }

    var isValid =
        identityManager.setUpOneTimePassword(
            usernameTranscoder.encodeUsername(authenticatedUser.getUsername()),
            oneTimePasswordDTO.getOtp(),
            oneTimePasswordDTO.getSecret());

    return isValid ? ResponseEntity.ok().build() : ResponseEntity.badRequest().build();
  }

  /**
   * Deactivates 2FA by mobile app for the calling user.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> deactivateTwoFactorAuthByApp() {
    identityManager.deleteOneTimePassword(
        usernameTranscoder.encodeUsername(authenticatedUser.getUsername()));

    return new ResponseEntity<>(HttpStatus.OK);
  }

  /**
   * Returns all agencies of given consultant.
   *
   * @param consultantId Consultant Id (required)
   * @return {@link ResponseEntity} containing all agencies of consultant
   */
  @Override
  public ResponseEntity<ConsultantResponseDTO> getConsultantPublicData(@NotNull UUID consultantId) {
    var consultantIdString = consultantId.toString();
    var consultant =
        consultantService
            .getConsultant(consultantIdString)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantIdString));
    var onlineAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant(consultantIdString);
    var consultantDto =
        consultantDtoMapper.consultantResponseDtoOf(consultant, onlineAgencies, false);

    return new ResponseEntity<>(consultantDto, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<RocketChatGroupIdDTO> getRocketChatGroupId(
      @NotNull @Valid String consultantId, @NotNull @Valid String askerId) {
    String groupId = sessionService.findGroupIdByConsultantAndUser(consultantId, askerId);
    return new ResponseEntity<>(new RocketChatGroupIdDTO().groupId(groupId), HttpStatus.OK);
  }
}
