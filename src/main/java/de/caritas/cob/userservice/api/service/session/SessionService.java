package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionTopic;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/** Service for sessions */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull SessionAccessService sessionAccessService;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @Nullable ConsultantSessionTopicEnrichmentService sessionTopicEnrichmentService;

  @Value("${feature.topics.enabled}")
  private boolean topicsFeatureEnabled;

  /**
   * Returns the sessions for a user
   *
   * @return the sessions
   */
  public List<Session> getSessionsForUser(User user) {
    return sessionRepository.findByUser(user);
  }

  /**
   * Returns the session for the provided sessionId.
   *
   * @param sessionId the session ID
   * @return {@link Session}
   */
  public Optional<Session> getSession(Long sessionId) {
    return sessionRepository.findById(sessionId);
  }

  /** Returns the session while holding a write lock for the surrounding transaction. */
  public Optional<Session> getSessionForUpdate(Long sessionId) {
    return sessionRepository.findByIdForUpdate(sessionId);
  }

  /**
   * Returns the sessions for the given user and consultingType.
   *
   * @param user {@link User}
   * @return list of {@link Session}
   */
  public List<Session> getSessionsForUserByConsultingTypeId(User user, int consultingTypeId) {
    return sessionRepository.findByUserAndConsultingTypeId(user, consultingTypeId);
  }

  public List<Session> getSessionsForUserByMainTopicId(User user, Long topicId) {
    return sessionRepository.findByUserAndMainTopicId(user, topicId);
  }

  /**
   * Updates the given session by assigning the provided consultant and {@link SessionStatus}.
   *
   * @param session the session
   * @param consultant the consultant
   * @param status the status of the session
   */
  public void updateConsultantAndStatusForSession(
      Session session, Consultant consultant, SessionStatus status) {
    session.setConsultant(consultant);
    session.setStatus(status);
    saveSession(session);
  }

  /**
   * Returns a list of current sessions (no matter if an enquiry message has been written or not)
   * for the provided user ID.
   *
   * @param userId Keycloak/MariaDB user ID
   * @return {@link List} of {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> getSessionsForUserId(String userId) {
    List<UserSessionResponseDTO> sessionResponseDTOs = new ArrayList<>();
    List<Session> sessions = sessionRepository.findByUserUserId(userId);
    if (isNotEmpty(sessions)) {
      List<Long> agencyIds =
          sessions.stream()
              .map(Session::getAgencyId)
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
      List<AgencyDTO> agencies = getAgenciesSafely(agencyIds, "user " + userId);
      sessionResponseDTOs = convertToUserSessionResponseDTO(sessions, agencies);
    }
    return sessionResponseDTOs;
  }

  /**
   * Initialize a {@link Session} and assign given consultant directly.
   *
   * @param user the user
   * @param userDto the dto of the user
   * @return the initialized session
   */
  public Session initializeDirectSession(
      Consultant consultant, User user, UserDTO userDto, boolean isTeamSession) {
    var session =
        initializeSession(
            user, userDto, isTeamSession, RegistrationType.REGISTERED, SessionStatus.NEW);
    session.setConsultant(consultant);
    return saveSession(session);
  }

  /**
   * Initialize a {@link Session} as initial registered enquiry.
   *
   * @param user the user
   * @param userDto the dto of the user
   * @return the initialized session
   */
  public Session initializeSession(User user, UserDTO userDto, boolean isTeamSession) {
    return initializeSession(
        user, userDto, isTeamSession, RegistrationType.REGISTERED, SessionStatus.NEW);
  }

  /**
   * Initialize a {@link Session}.
   *
   * @param user {@link User}
   * @param userDto {@link UserDTO}
   * @param isTeamSession is team session flag
   * @param registrationType {@link RegistrationType}
   * @param sessionStatus {@link SessionStatus}
   * @return the initialized {@link Session}
   */
  public Session initializeSession(
      User user,
      UserDTO userDto,
      boolean isTeamSession,
      RegistrationType registrationType,
      SessionStatus sessionStatus) {
    var extendedConsultingTypeResponseDTO = obtainConsultingTypeSettings(userDto);

    var session =
        Session.builder()
            .user(user)
            .tenantId(TenantContext.getCurrentTenant())
            .consultingTypeId(obtainCheckedConsultingTypeId(extendedConsultingTypeResponseDTO))
            .registrationType(registrationType)
            .postcode(userDto.getPostcode())
            .agencyId(userDto.getAgencyId())
            .languageCode(LanguageCode.de)
            .status(sessionStatus)
            .teamSession(isTeamSession)
            .createDate(nowInUtc())
            .updateDate(nowInUtc())
            .mainTopicId(userDto.getMainTopicId())
            .userGender(userDto.getUserGender())
            .userAge(userDto.getUserAge())
            .counsellingRelation(userDto.getCounsellingRelation())
            .referer(userDto.getReferer())
            .isConsultantDirectlySet(false)
            .build();

    Session savedSession = saveSession(session);
    savedSession.setSessionTopics(createSessionTopics(userDto.getTopicIds(), savedSession));
    return saveSession(savedSession);
  }

  private List<SessionTopic> createSessionTopics(
      Collection<Long> topicsOfInterest, Session session) {
    if (topicsOfInterest != null) {
      return topicsOfInterest.stream()
          .map(topicId -> createNewSessionTopic(session, topicId))
          .collect(Collectors.toList());
    } else {
      return Lists.newArrayList();
    }
  }

  private SessionTopic createNewSessionTopic(Session session, Long topicId) {
    return SessionTopic.builder()
        .topicId(topicId)
        .session(session)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now())
        .build();
  }

  private ExtendedConsultingTypeResponseDTO obtainConsultingTypeSettings(UserDTO userDTO) {
    return consultingTypeManager.getConsultingTypeSettings(userDTO.getConsultingType());
  }

  private Integer obtainCheckedConsultingTypeId(
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO) {
    var consultingTypeId = extendedConsultingTypeResponseDTO.getId();
    if (isNull(consultingTypeId)) {
      throw new BadRequestException("Consulting type id must not be null");
    }
    return consultingTypeId;
  }

  /**
   * Save a {@link Session} to the database.
   *
   * @param session the session
   * @return the {@link Session}
   */
  public Session saveSession(Session session) {
    if (session.getConversationType() == null) {
      session.setConversationType(
          session.isTeamSession()
              ? ConversationType.INTERNAL_GROUP
              : session.getRegistrationType() == Session.RegistrationType.ANONYMOUS
                  ? ConversationType.LIVE_CHAT
                  : ConversationType.AGENCY_COUNSELLING);
    }
    return sessionRepository.save(session);
  }

  private List<UserSessionResponseDTO> convertToUserSessionResponseDTO(
      List<Session> sessions, List<AgencyDTO> agencies) {
    return sessions.stream()
        .map(session -> buildUserSessionDTO(session, agencies))
        .collect(Collectors.toList());
  }

  private UserSessionResponseDTO buildUserSessionDTO(Session session, List<AgencyDTO> agencies) {
    return new UserSessionResponseDTO()
        .session(new SessionMapper().convertToSessionDTO(session))
        .agency(
            agencies.stream()
                .filter(agency -> agency.getId().longValue() == session.getAgencyId().longValue())
                .findAny()
                .orElse(null))
        .consultant(
            nonNull(session.getConsultant())
                ? convertToSessionConsultantForUserDTO(session.getConsultant())
                : null);
  }

  private SessionConsultantForUserDTO convertToSessionConsultantForUserDTO(Consultant consultant) {
    return new SessionConsultantForUserDTO(
        consultant.getId(),
        consultant.getUsername(),
        consultant.isAbsent(),
        consultant.getAbsenceMessage(),
        null);
  }

  /**
   * Delete a {@link Session}
   *
   * @param session the {@link Session}
   */
  public void deleteSession(Session session) {
    sessionRepository.delete(session);
  }

  /**
   * Retrieves user sessions by user ID and Matrix room IDs.
   *
   * @param userId the user ID
   * @param matrixRoomIds Matrix room IDs
   * @param roles the roles of the given user
   * @return {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> getSessionsByUserAndRoomIds(
      String userId, Set<String> matrixRoomIds, Set<String> roles) {
    sessionAccessService.checkForAskerRoles(roles);
    var sessions = sessionRepository.findByMatrixRoomIdIn(matrixRoomIds);
    sessions.forEach(
        session -> sessionAccessService.checkAskerPermissionForSession(session, userId, roles));
    List<AgencyDTO> agencies = fetchAgencies(sessions);
    return convertToUserSessionResponseDTO(sessions, agencies);
  }

  /**
   * Retrieves user sessions by user ID and session IDs
   *
   * @param userId the user ID
   * @param sessionIds the session IDs
   * @param roles the roles of the given user
   * @return {@link UserSessionResponseDTO}
   */
  public List<UserSessionResponseDTO> getSessionsByUserAndSessionIds(
      String userId, Set<Long> sessionIds, Set<String> roles) {
    sessionAccessService.checkForAskerRoles(roles);
    var sessions =
        StreamSupport.stream(sessionRepository.findAllById(sessionIds).spliterator(), false)
            .collect(Collectors.toList());
    sessions.forEach(
        session -> sessionAccessService.checkAskerPermissionForSession(session, userId, roles));
    List<AgencyDTO> agencies = fetchAgencies(sessions);
    return convertToUserSessionResponseDTO(sessions, agencies);
  }

  private List<AgencyDTO> fetchAgencies(List<Session> sessions) {
    Set<Long> agencyIds =
        sessions.stream()
            .map(Session::getAgencyId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return getAgenciesSafely(new ArrayList<>(agencyIds), "session list lookup");
  }

  private List<AgencyDTO> getAgenciesSafely(List<Long> agencyIds, String context) {
    if (agencyIds == null || agencyIds.isEmpty()) {
      return emptyList();
    }
    try {
      return agencyService.getAgencies(agencyIds);
    } catch (HttpClientErrorException.Forbidden e) {
      // Do not break login/session bootstrap when agency service denies access for this token.
      log.warn("Forbidden while loading agencies for {}: {}", context, e.getMessage());
      return emptyList();
    }
  }

  public Session getSessionByMatrixRoomId(String matrixRoomId) {
    return sessionRepository
        .findByMatrixRoomId(matrixRoomId)
        .orElseThrow(
            () -> new NotFoundException("Session with Matrix room ID %s not found.", matrixRoomId));
  }

  /**
   * Returns a {@link ConsultantSessionDTO} for a specific session.
   *
   * @param sessionId the session ID to fetch
   * @param consultant the calling consultant
   * @return {@link ConsultantSessionDTO} entity for the specific session
   */
  public ConsultantSessionDTO fetchSessionForConsultant(
      @NonNull Long sessionId, @NonNull Consultant consultant) {

    var session =
        getSession(sessionId)
            .orElseThrow(() -> new NotFoundException("Session with id %s not found.", sessionId));

    sessionAccessService.checkPermissionForConsultantSession(session, consultant);
    return toConsultantSessionDTO(session);
  }

  private ConsultantSessionDTO toConsultantSessionDTO(Session session) {

    var consultantSessionDTO =
        new ConsultantSessionDTO()
            .isTeamSession(session.isTeamSession())
            .agencyId(session.getAgencyId())
            .consultingType(session.getConsultingTypeId())
            .id(session.getId())
            .status(session.getStatus().getValue())
            .askerId(session.getUser().getUserId())
            .askerMatrixUserId(session.getUser().getMatrixUserId())
            .askerUserName(session.getUser().getUsername())
            .matrixRoomId(session.getMatrixRoomId())
            .postcode(session.getPostcode())
            .consultantId(nonNull(session.getConsultant()) ? session.getConsultant().getId() : null)
            .consultantMatrixUserId(
                nonNull(session.getConsultant()) ? session.getConsultant().getMatrixUserId() : null)
            .age(session.getUserAge())
            .gender(session.getUserGender())
            .counsellingRelation(session.getCounsellingRelation())
            .referer(session.getReferer());

    if (topicsFeatureEnabled) {
      consultantSessionDTO
          .mainTopic(new SessionTopicDTO().id(session.getMainTopicId()))
          .topics(
              session.getSessionTopics().stream()
                  .map(topic -> new SessionTopicDTO().id(topic.getTopicId()))
                  .collect(Collectors.toList()));
      sessionTopicEnrichmentService.enrichSessionWithMainTopicData(consultantSessionDTO);
      sessionTopicEnrichmentService.enrichSessionWithTopicsData(consultantSessionDTO);
    } else {
      consultantSessionDTO.topics(null);
    }

    return consultantSessionDTO;
  }

  /**
   * Find one session by assigned consultant and user.
   *
   * @param consultant the consultant
   * @param user the user
   * @param consultingTypeId the id of the consulting type
   * @return an {@link Optional} of the result
   */
  public Optional<Session> findSessionByConsultantAndUserAndConsultingType(
      Consultant consultant, User user, Integer consultingTypeId) {
    if (nonNull(consultant) && nonNull(user)) {
      return sessionRepository.findByConsultantAndUserAndConsultingTypeId(
          consultant, user, consultingTypeId);
    }
    return Optional.empty();
  }

  public List<Session> findSessionsByUser(User user) {
    if (nonNull(user)) {
      return sessionRepository.findByUserWithSessionData(user);
    }
    return emptyList();
  }
}
