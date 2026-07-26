package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.keycloak.dto.KeycloakLoginResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AbsenceDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatInfoResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ChatMembersResponseDTO;
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
import de.caritas.cob.userservice.api.adapters.web.dto.GetChatSeriesOccurrences200ResponseInner;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkConsumeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MagicLinkRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MasterKeyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.MobileTokenDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewMessageNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationDto;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.OccurrenceOverrideRequest;
import de.caritas.cob.userservice.api.adapters.web.dto.OneTimePasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetConfirmDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PasswordResetRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ReassignmentNotificationDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.RocketChatGroupIdDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.RoleRequest;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TransferOwnershipRequest;
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
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.CreateNewSessionFacade;
import de.caritas.cob.userservice.api.facade.CreateUserFacade;
import de.caritas.cob.userservice.api.facade.userdata.AskerDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataFacade;
import de.caritas.cob.userservice.api.facade.userdata.ConsultantDataProvider;
import de.caritas.cob.userservice.api.facade.userdata.KeycloakUserDataProvider;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Chat.ChatModality;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.in.IdentityManaging;
import de.caritas.cob.userservice.api.port.in.Messaging;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantPublicSlugService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.archive.SessionArchiveService;
import de.caritas.cob.userservice.api.service.archive.SessionDeleteService;
import de.caritas.cob.userservice.api.service.auth.MagicLinkLoginService;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceCommandService;
import de.caritas.cob.userservice.api.service.chat.ChatOccurrenceQueryService;
import de.caritas.cob.userservice.api.service.chat.GroupChatRoleService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UsersApi;
import io.swagger.annotations.Api;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Controller for user api requests */
@Slf4j
@RestController
@Validated
@RequiredArgsConstructor
@Api(tags = "user-controller")
public class UserController implements UsersApi {

  private final @NotNull UserAccountService userAccountProvider;
  private final @NotNull UserRegistrationControllerDelegate userRegistrationControllerDelegate;
  private final @NotNull UserSessionControllerDelegate userSessionControllerDelegate;
  private final @NotNull UserAccountControllerDelegate userAccountControllerDelegate;
  private final @NotNull UserConsultantControllerDelegate userConsultantControllerDelegate;
  private final @NotNull UserSupportControllerDelegate userSupportControllerDelegate;
  private final @NotNull UserTwoFactorAuthControllerDelegate userTwoFactorAuthControllerDelegate;
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
  private final @NonNull ConsultantPublicSlugService consultantPublicSlugService;
  private final @NonNull ConsultantUpdateService consultantUpdateService;
  private final @NonNull ConsultantDataProvider consultantDataProvider;
  private final @NonNull AskerDataProvider askerDataProvider;
  private final @NonNull VideoChatConfig videoChatConfig;
  private final @NonNull KeycloakUserDataProvider keycloakUserDataProvider;
  private final @NotNull IdentityClient identityClient;
  private final @NonNull MagicLinkLoginService magicLinkLoginService;
  private final @NonNull ConsultantAgencyService consultantAgencyService;

  private final @NotNull AdminUserFacade adminUserFacade;

  @Value("${feature.topics.enabled}")
  private boolean featureTopicsEnabled;

  private final @NonNull SessionDeleteService sessionDeleteService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NotNull ChatOccurrenceQueryService chatOccurrenceQueryService;
  private final @NotNull ChatOccurrenceCommandService chatOccurrenceCommandService;
  private final @NotNull GroupChatRoleService groupChatRoleService;
  private final @NotNull AuthenticatedUser authenticatedUser;

  @Override
  public ResponseEntity<Void> userExists(String username) {
    return userRegistrationControllerDelegate.userExists(username);
  }

  @GetMapping("/users/availability/{username}")
  public ResponseEntity<Void> usernameAvailability(@PathVariable String username) {
    boolean usernameAvailable;
    try {
      usernameAvailable = identityClient.isUsernameAvailable(username);
    } catch (RuntimeException exception) {
      log.warn(
          "Could not check username availability for {}. Treating it as available so registration is not blocked.",
          username,
          exception);
      usernameAvailable = true;
    }
    return usernameAvailable
        ? ResponseEntity.noContent().build()
        : ResponseEntity.status(HttpStatus.CONFLICT).build();
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/magic-link/request")
  public ResponseEntity<Void> requestMagicLink(@Valid @RequestBody MagicLinkRequestDTO requestDTO) {
    return userRegistrationControllerDelegate.requestMagicLink(requestDTO);
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/magic-link/consume")
  public ResponseEntity<KeycloakLoginResponseDTO> consumeMagicLink(
      @Valid @RequestBody MagicLinkConsumeDTO consumeDTO) {
    return userRegistrationControllerDelegate.consumeMagicLink(consumeDTO);
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/password-reset/request")
  public ResponseEntity<Void> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequestDTO requestDTO) {
    return userRegistrationControllerDelegate.requestPasswordReset(requestDTO);
  }

  @org.springframework.web.bind.annotation.PostMapping("/users/password-reset/confirm")
  public ResponseEntity<Void> confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmDTO confirmDTO) {
    return userRegistrationControllerDelegate.confirmPasswordReset(confirmDTO);
  }

  /**
   * Creates an user account and returns a 201 CREATED on success.
   *
   * @param user the {@link UserDTO}
   * @return {@link ResponseEntity} with possible registration conflict information in header
   */
  @org.springframework.web.bind.annotation.PostMapping(
      value = {"/users/askers/new", "/service/users/askers/new"},
      consumes = MediaType.APPLICATION_JSON_VALUE)
  @Override
  public ResponseEntity<Void> registerUser(@RequestBody UserDTO user) {
    return userRegistrationControllerDelegate.registerUser(user);
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
      @RequestBody NewRegistrationDto newRegistrationDto,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {
    return userRegistrationControllerDelegate.registerNewConsultingType(newRegistrationDto);
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
      NewRegistrationDto newRegistrationDto,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {
    return userRegistrationControllerDelegate.registerNewSession(newRegistrationDto);
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
      @PathVariable Long sessionId, @RequestHeader(required = false) String rcUserId) {
    return userRegistrationControllerDelegate.acceptEnquiry(sessionId);
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
      @PathVariable Long sessionId,
      @RequestBody EnquiryMessageDTO enquiryMessage,
      @RequestHeader(value = "RCToken", required = false) String rcToken,
      @RequestHeader(value = "RCUserId", required = false) String rcUserId) {
    return userRegistrationControllerDelegate.createEnquiryMessage(sessionId, enquiryMessage);
  }

  @Override
  public ResponseEntity<Void> deleteSessionAndInactiveUser(@PathVariable Long sessionId) {
    return userRegistrationControllerDelegate.deleteSessionAndInactiveUser(sessionId);
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
    return userSessionControllerDelegate.getSessionsForAuthenticatedUser(rcToken);
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
      @RequestParam List<String> rcGroupIds, @RequestHeader(required = false) String rcToken) {
    return userSessionControllerDelegate.getSessionsForGroupIds(rcGroupIds, rcToken);
  }

  // MATRIX MIGRATION: Added manual mapping since generated interface hasn't updated yet
  // Mapped to both direct path and /service/ prefix (API gateway) so both routes resolve
  @GetMapping(
      value = {"/users/sessions/room/{sessionId}", "/service/users/sessions/room/{sessionId}"},
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<GroupSessionListResponseDTO> getSessionForId(
      @PathVariable Long sessionId,
      @RequestHeader(value = "RCToken", required = false) String rcToken) {
    return userSessionControllerDelegate.getSessionForId(sessionId, rcToken);
  }

  @Override
  public ResponseEntity<GroupSessionListResponseDTO> getChatById(Long chatId, String rcToken) {
    return userSessionControllerDelegate.getChatById(rcToken, chatId);
  }

  /**
   * Updates the absence (and its message) for the calling consultant.
   *
   * @param absence {@link AbsenceDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updateAbsence(@RequestBody AbsenceDTO absence) {
    return userAccountControllerDelegate.updateAbsence(absence);
  }

  @Override
  public ResponseEntity<EmailNotificationsDTO> getUserEmailNotifications(String email) {
    return userAccountControllerDelegate.getUserEmailNotifications(email);
  }

  /**
   * Gets the user data for the current logged-in user depending on his user role.
   *
   * @return {@link ResponseEntity} containing {@link UserDataResponseDTO}
   */
  @Override
  public ResponseEntity<UserDataResponseDTO> getUserData() {
    return userAccountControllerDelegate.getUserData();
  }

  @Override
  public ResponseEntity<Void> patchUser(PatchUserDTO patchUserDTO) {
    return userAccountControllerDelegate.patchUser(patchUserDTO);
  }

  /**
   * Updates the data for the current logged in consultant.
   *
   * @param updateConsultantDTO (required) the request {@link UpdateConsultantDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateConsultantData(UpdateConsultantDTO updateConsultantDTO) {
    return userAccountControllerDelegate.updateConsultantData(updateConsultantDTO);
  }

  @Override
  public ResponseEntity<LanguageResponseDTO> getLanguages(Long agencyId) {
    return userConsultantControllerDelegate.getLanguages(agencyId);
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
      Integer offset,
      Integer count,
      @RequestParam String filter,
      @RequestHeader(required = false) String rcToken,
      @RequestParam Integer status) {
    return userSessionControllerDelegate.getSessionsForAuthenticatedConsultant(
        rcToken, offset, count, filter, status);
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
      Integer offset,
      Integer count,
      @RequestParam String filter,
      @RequestHeader(required = false) String rcToken) {
    return userSessionControllerDelegate.getTeamSessionsForAuthenticatedConsultant(
        rcToken, offset, count, filter);
  }

  /**
   * Imports a file list of consultants. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importConsultants() {
    return userSupportControllerDelegate.importConsultants();
  }

  /**
   * Imports a file list of askers. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importAskers() {
    return userSupportControllerDelegate.importAskers();
  }

  /**
   * Imports a file list of askers without a session. Technical user authorization required.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> importAskersWithoutSession() {
    return userSupportControllerDelegate.importAskersWithoutSession();
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
      @RequestBody NewMessageNotificationDTO newMessageNotificationDTO) {
    return userSupportControllerDelegate.sendNewMessageNotification(newMessageNotificationDTO);
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
      @RequestBody ReassignmentNotificationDTO reassignmentNotificationDTO) {
    return userSupportControllerDelegate.sendReassignmentNotification(reassignmentNotificationDTO);
  }

  /**
   * Returns all consultants of the provided agency id as a list of {@link ConsultantResponseDTO}.
   *
   * @param agencyId Agency Id (required)
   * @return {@link ResponseEntity} containing {@link List} of {@link ConsultantResponseDTO}
   */
  @Override
  public ResponseEntity<List<ConsultantResponseDTO>> getConsultants(@RequestParam Long agencyId) {
    return userConsultantControllerDelegate.getConsultants(agencyId);
  }

  @Override
  public ResponseEntity<ConsultantSearchResultDTO> searchConsultants(
      String query, Integer page, Integer perPage, String field, String order) {
    return userConsultantControllerDelegate.searchConsultants(query, page, perPage, field, order);
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
      @PathVariable Long sessionId, @PathVariable String consultantId) {
    return userSessionControllerDelegate.assignSession(sessionId, consultantId);
  }

  @Override
  public ResponseEntity<Void> removeFromSession(Long sessionId, UUID consultantId) {
    return userSessionControllerDelegate.removeFromSession(sessionId, consultantId);
  }

  /**
   * Changes the (Keycloak) password of the currently authenticated user.
   *
   * @param passwordDTO (required) {@link PasswordDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updatePassword(@RequestBody PasswordDTO passwordDTO) {
    return userAccountControllerDelegate.updatePassword(passwordDTO);
  }

  /**
   * Updates the master key fragment for the en-/decryption of messages.
   *
   * @param masterKey {@link MasterKeyDTO} (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> updateKey(@RequestBody MasterKeyDTO masterKey) {
    return userAccountControllerDelegate.updateKey(masterKey);
  }

  @Override
  public ResponseEntity<Void> updateE2eInChats(E2eKeyDTO e2eKeyDTO) {
    return userAccountControllerDelegate.updateE2eInChats(e2eKeyDTO);
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
  public ResponseEntity<CreateChatResponseDTO> createChatV1(@RequestBody ChatDTO chatDTO) {
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
  public ResponseEntity<CreateChatResponseDTO> createChatV2(@RequestBody ChatDTO chatDTO) {
    return userChatControllerDelegate.createChatV2(chatDTO);
  }

  @Override
  public ResponseEntity<List<GetChatSeriesOccurrences200ResponseInner>> getChatSeriesOccurrences(
      Long seriesId, OffsetDateTime from, OffsetDateTime to, Integer limit) {
    var occurrences =
        chatOccurrenceQueryService.getOccurrences(
            seriesId, toUtcLocalDateTime(from), toUtcLocalDateTime(to), limit);
    return ResponseEntity.ok(
        occurrences.stream()
            .map(
                occurrence ->
                    new GetChatSeriesOccurrences200ResponseInner()
                        .seriesId(occurrence.seriesId())
                        .occurrenceIndex(occurrence.occurrenceIndex())
                        .originalStart(occurrence.originalStart().atOffset(ZoneOffset.UTC))
                        .start(occurrence.start().atOffset(ZoneOffset.UTC))
                        .duration(occurrence.duration())
                        .capacity(occurrence.capacity())
                        .modality(
                            GetChatSeriesOccurrences200ResponseInner.ModalityEnum.fromValue(
                                occurrence.modality().name())))
            .toList());
  }

  @Override
  public ResponseEntity<Void> skipChatSeriesOccurrence(
      Long seriesId, OffsetDateTime originalStartUtc) {
    chatOccurrenceCommandService.skip(
        seriesId, authenticatedUser.getUserId(), toUtcLocalDateTime(originalStartUtc));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> overrideChatSeriesOccurrence(
      Long seriesId, OccurrenceOverrideRequest request) {
    var overrideStart = request.getOverrideStartUtc();
    var modality = request.getModality();
    chatOccurrenceCommandService.override(
        seriesId,
        authenticatedUser.getUserId(),
        toUtcLocalDateTime(request.getOriginalStartUtc()),
        overrideStart == null ? null : toUtcLocalDateTime(overrideStart),
        request.getDuration(),
        request.getCapacity(),
        modality == null ? null : ChatModality.valueOf(modality.getValue()));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> changeChatSeriesParticipantRole(
      Long seriesId, String consultantId, RoleRequest request) {
    groupChatRoleService.changeRole(
        seriesId,
        authenticatedUser.getUserId(),
        consultantId,
        ParticipantRole.valueOf(request.getRole().getValue()));
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<Void> removeChatSeriesParticipant(Long seriesId, String consultantId) {
    groupChatRoleService.removeParticipant(seriesId, authenticatedUser.getUserId(), consultantId);
    return ResponseEntity.noContent().build();
  }

  @Override
  public ResponseEntity<List<ConsultantResponseDTO>> getChatSeriesConsultants() {
    return userConsultantControllerDelegate.getTenantConsultants();
  }

  @Override
  public ResponseEntity<Void> transferChatSeriesOwnership(
      Long seriesId, TransferOwnershipRequest request) {
    groupChatRoleService.transferPrimaryOwnership(
        seriesId, authenticatedUser.getUserId(), request.getConsultantId());
    return ResponseEntity.noContent().build();
  }

  private static java.time.LocalDateTime toUtcLocalDateTime(OffsetDateTime value) {
    return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
  }

  /**
   * Starts a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> startChat(@PathVariable Long chatId) {
    return userChatControllerDelegate.startChat(chatId);
  }

  /**
   * Gets the chat info of provided chat ID.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link ChatInfoResponseDTO}
   */
  @Override
  public ResponseEntity<ChatInfoResponseDTO> getChat(Long chatId) {
    return userChatControllerDelegate.getChat(chatId);
  }

  /**
   * Assign a chat, resolved using the group id.
   *
   * @param groupId the rocket chat group uuid part (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> assignChat(String groupId) {
    return userChatControllerDelegate.assignChat(groupId);
  }

  /**
   * Join a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> joinChat(@PathVariable Long chatId) {
    return userChatControllerDelegate.joinChat(chatId);
  }

  @Override
  public ResponseEntity<Void> verifyCanModerateChat(@PathVariable Long chatId) {
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
  public ResponseEntity<Void> stopChat(Long chatId) {
    return userChatControllerDelegate.stopChat(chatId);
  }

  /**
   * Gets the members of a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link ChatMembersResponseDTO}
   */
  @Override
  public ResponseEntity<ChatMembersResponseDTO> getChatMembers(@PathVariable Long chatId) {
    return userChatControllerDelegate.getChatMembers(chatId);
  }

  /**
   * Leave a chat.
   *
   * @param chatId Chat Id (required)
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> leaveChat(@PathVariable Long chatId) {
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
      @PathVariable Long chatId, @RequestBody ChatDTO chatDTO) {
    return userChatControllerDelegate.updateChat(chatId, chatDTO);
  }

  @Override
  public ResponseEntity<Void> banFromChat(String token, String chatUserId, Long chatId) {
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
      @PathVariable Long sessionId) {
    return userSessionControllerDelegate.fetchSessionForConsultant(sessionId);
  }

  /**
   * Updates or sets the email address for the current authenticated user.
   *
   * @param emailAddress the email address to set
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateEmailAddress(String emailAddress) {
    return userAccountControllerDelegate.updateEmailAddress(emailAddress);
  }

  /**
   * Sets the user's email address to its default.
   *
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> deleteEmailAddress() {
    return userAccountControllerDelegate.deleteEmailAddress();
  }

  /**
   * Flags an user account for deletion and deactivates the Keycloak account.
   *
   * @param deleteUserAccountDTO (required) {@link DeleteUserAccountDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> deactivateAndFlagUserAccountForDeletion(
      DeleteUserAccountDTO deleteUserAccountDTO) {
    return userAccountControllerDelegate.deactivateAndFlagUserAccountForDeletion(
        deleteUserAccountDTO);
  }

  /**
   * Updates or sets the mobile client token for the current authenticated user.
   *
   * @param mobileTokenDTO (required) the mobile device identifier {@link MobileTokenDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> updateMobileToken(MobileTokenDTO mobileTokenDTO) {
    return userAccountControllerDelegate.updateMobileToken(mobileTokenDTO);
  }

  /**
   * Adds a mobile client token for the current authenticated user.
   *
   * @param mobileTokenDTO (required) the mobile device identifier {@link MobileTokenDTO}
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> addMobileAppToken(MobileTokenDTO mobileTokenDTO) {
    return userAccountControllerDelegate.addMobileAppToken(mobileTokenDTO);
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
      @PathVariable Long sessionId, SessionDataDTO sessionDataDTO) {
    return userSupportControllerDelegate.updateSessionData(sessionId, sessionDataDTO);
  }

  /**
   * Put a session into the archive.
   *
   * @param sessionId (required) session ID
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> archiveSession(@PathVariable Long sessionId) {
    return userSessionControllerDelegate.archiveSession(sessionId);
  }

  /**
   * Dearchive a session.
   *
   * @param sessionId (required) session ID
   * @return {@link ResponseEntity}
   */
  @Override
  public ResponseEntity<Void> dearchiveSession(@PathVariable Long sessionId) {
    return userSessionControllerDelegate.dearchiveSession(sessionId);
  }

  @Override
  public ResponseEntity<Void> startTwoFactorAuthByEmailSetup(EmailDTO emailDTO) {
    return userTwoFactorAuthControllerDelegate.startTwoFactorAuthByEmailSetup(emailDTO);
  }

  @Override
  public ResponseEntity<Void> finishTwoFactorAuthByEmailSetup(String tan) {
    return userTwoFactorAuthControllerDelegate.finishTwoFactorAuthByEmailSetup(tan);
  }

  /**
   * Activates 2FA by mobile app for the calling user.
   *
   * @param oneTimePasswordDTO (required) {@link OneTimePasswordDTO}
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> activateTwoFactorAuthByApp(OneTimePasswordDTO oneTimePasswordDTO) {
    return userTwoFactorAuthControllerDelegate.activateTwoFactorAuthByApp(oneTimePasswordDTO);
  }

  /**
   * Deactivates 2FA by mobile app for the calling user.
   *
   * @return {@link ResponseEntity} containing {@link HttpStatus}
   */
  @Override
  public ResponseEntity<Void> deactivateTwoFactorAuthByApp() {
    return userTwoFactorAuthControllerDelegate.deactivateTwoFactorAuthByApp();
  }

  /**
   * Returns all agencies of given consultant.
   *
   * @param consultantId Consultant Id (required)
   * @return {@link ResponseEntity} containing all agencies of consultant
   */
  @Override
  public ResponseEntity<ConsultantResponseDTO> getConsultantPublicData(String consultantId) {
    var consultantIdString = consultantId.trim();
    var consultant =
        consultantPublicSlugService
            .resolveActiveConsultant(consultantIdString)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s not found", consultantIdString));
    var onlineAgencies = consultantAgencyService.getOnlineAgenciesOfConsultant(consultant.getId());
    var consultantDto =
        consultantDtoMapper.consultantResponseDtoOf(consultant, onlineAgencies, false);

    return new ResponseEntity<>(consultantDto, HttpStatus.OK);
  }

  @Override
  public ResponseEntity<RocketChatGroupIdDTO> getRocketChatGroupId(
      String consultantId, String askerId) {
    return userSupportControllerDelegate.getRocketChatGroupId(consultantId, askerId);
  }
}
