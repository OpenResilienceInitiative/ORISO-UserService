package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import com.google.api.client.util.Lists;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
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
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Service for sessions */
@Service
@RequiredArgsConstructor
public class SessionService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultingTypeManager consultingTypeManager;

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

  /**
   * Delete a {@link Session}
   *
   * @param session the {@link Session}
   */
  public void deleteSession(Session session) {
    sessionRepository.delete(session);
  }

  public Session getSessionByMatrixRoomId(String matrixRoomId) {
    return sessionRepository
        .findByMatrixRoomId(matrixRoomId)
        .orElseThrow(
            () -> new NotFoundException("Session with Matrix room ID %s not found.", matrixRoomId));
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
