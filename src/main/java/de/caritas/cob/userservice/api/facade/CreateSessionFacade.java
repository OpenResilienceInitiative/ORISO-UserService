package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.SessionDataProvider.fromUserDTO;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.NewRegistrationResponseDto;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.helper.AgencyVerifier;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.NewSessionValidationConstraint;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.DirectSessionMatrixRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.consultingtypeservice.generated.web.model.ExtendedConsultingTypeResponseDTO;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/** Facade to encapsulate the steps to initialize a new session. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateSessionFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull AgencyVerifier agencyVerifier;
  private final @NonNull SessionDataService sessionDataService;
  private final @NonNull RollbackFacade rollbackFacade;
  private final @NonNull UserAccountService userAccountProvider;

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;
  private final @NonNull DirectSessionMatrixRoomService directSessionMatrixRoomService;

  /**
   * Creates a new session for the provided user.
   *
   * @param userDTO {@link UserDTO}
   * @param user {@link User}
   * @param extendedConsultingTypeResponseDTO {@link ExtendedConsultingTypeResponseDTO}
   * @param validationConstraints
   */
  public Long createUserSession(
      UserDTO userDTO,
      User user,
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO,
      List<NewSessionValidationConstraint> validationConstraints) {

    if (validationConstraints.contains(
        NewSessionValidationConstraint.ONE_SESSION_PER_CONSULTING_TYPE)) {
      checkIfAlreadyRegisteredToConsultingType(user, extendedConsultingTypeResponseDTO.getId());
    }

    var agencyDTO = obtainVerifiedAgency(userDTO, extendedConsultingTypeResponseDTO);
    verifyAgencyServesMainTopic(userDTO, agencyDTO);

    if (validationConstraints.contains(
        NewSessionValidationConstraint.ONE_SESSION_PER_TOPIC_ID_AND_AGENCY_ID)) {
      checkIfAlreadyRegisteredToTopicAndSameAgency(
          user, userDTO.getMainTopicId(), agencyDTO.getId());
    }
    var session = initializeSession(userDTO, user, agencyDTO);

    try {
      agencyPreAssignmentRoomService.ensureHoldingRoom(session, user);
    } catch (Exception ex) {
      log.error(
          "Failed to provision Matrix holding room for session {}: {}",
          session != null ? session.getId() : null,
          ex.getMessage());
    }

    return session != null ? session.getId() : null;
  }

  /**
   * Creates a new session for the provided user and assignes it to given consultant.
   *
   * @param userDTO {@link UserDTO}
   * @param user {@link User}
   * @param extendedConsultingTypeResponseDTO {@link ExtendedConsultingTypeResponseDTO}
   */
  public NewRegistrationResponseDto createDirectUserSession(
      String consultantId,
      UserDTO userDTO,
      User user,
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO) {
    var consultant = userAccountProvider.retrieveValidatedConsultantById(consultantId);

    var existingSession =
        sessionService.findSessionByConsultantAndUserAndConsultingType(
            consultant, user, extendedConsultingTypeResponseDTO.getId());
    if (existingSession.isPresent()) {
      var session = existingSession.get();
      return new NewRegistrationResponseDto()
          .sessionId(session.getId())
          .matrixRoomId(session.getMatrixRoomId())
          .status(HttpStatus.CONFLICT);
    }

    return initializeNewDirectSession(userDTO, user, extendedConsultingTypeResponseDTO, consultant);
  }

  private NewRegistrationResponseDto initializeNewDirectSession(
      UserDTO userDTO,
      User user,
      ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO,
      Consultant consultant) {
    var agencyDTO = obtainVerifiedAgency(userDTO, extendedConsultingTypeResponseDTO);
    var session =
        sessionService.initializeDirectSession(
            consultant, user, userDTO, agencyDTO.getTeamAgency());
    sessionDataService.saveSessionData(session, fromUserDTO(userDTO));
    session.setConsultant(consultant);
    session.setStatus(SessionStatus.IN_PROGRESS);
    session = sessionService.saveSession(session);

    directSessionMatrixRoomService.provisionRoomForDirectSession(session, consultant);

    return new NewRegistrationResponseDto().sessionId(session.getId()).status(HttpStatus.CREATED);
  }

  private Session initializeSession(UserDTO userDTO, User user, AgencyDTO agencyDTO) {
    var sessionData = fromUserDTO(userDTO);
    var isTeaming = isTrue(agencyDTO.getTeamAgency());
    boolean initialized = false;
    Long sessionId = null;

    try {
      var session = sessionService.initializeSession(user, userDTO, isTeaming);
      initialized = nonNull(session) && nonNull(session.getId());
      sessionId = initialized ? session.getId() : null;
      sessionDataService.saveSessionData(session, sessionData);

      return session;
    } catch (Exception ex) {
      if (initialized) {
        log.warn(
            "Session {} for user {} was already persisted; skipping user rollback after"
                + " session data failure",
            sessionId,
            user.getUsername(),
            ex);
      } else {
        rollbackFacade.rollBackUserAccount(
            RollbackUserAccountInformation.builder()
                .userId(user.getUserId())
                .user(user)
                .rollBackUserAccount(Boolean.parseBoolean(userDTO.getTermsAccepted()))
                .build());
      }

      var stepWord = initialized ? "save session data" : "initialize session";
      var message = String.format("Could not %s for user %s.", stepWord, user.getUsername());
      throw new InternalServerErrorException(message, ex);
    }
  }

  private AgencyDTO obtainVerifiedAgency(
      UserDTO userDTO, ExtendedConsultingTypeResponseDTO extendedConsultingTypeResponseDTO) {
    AgencyDTO agencyDTO;
    try {
      agencyDTO =
          agencyVerifier.getVerifiedAgency(
              userDTO.getAgencyId(), requireNonNull(extendedConsultingTypeResponseDTO.getId()));
    } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized ex) {
      log.warn(
          "Agency verification unavailable while creating session (agencyId={}): {}. "
              + "Using registration agency id as fallback.",
          userDTO.getAgencyId(),
          ex.getMessage());
      agencyDTO = new AgencyDTO().id(userDTO.getAgencyId()).teamAgency(false);
    }

    if (isNull(agencyDTO)) {
      throw new BadRequestException(
          String.format(
              "Agency %s is not assigned to given consulting type %d",
              userDTO.getAgencyId(), extendedConsultingTypeResponseDTO.getId()));
    }

    return agencyDTO;
  }

  /**
   * The agency id and the main topic id reach us as two independent request fields, so a client can
   * pair a topic with an agency that never offered it. Registration then puts the advice seeker's
   * disclosure in front of a counselling centre that is not responsible for the subject, which is
   * exactly what agency search prevents by only listing agencies for the selected topic. Reject the
   * pairing here as well (ORISO-Frontend#1143).
   *
   * <p>A registration without a main topic keeps working, and so does one where the agency lookup
   * degraded to an unverified stub: a {@code null} topic list means "topics unknown", not "topics
   * empty", and failing closed on missing data would take registration down with the agency
   * service.
   */
  private void verifyAgencyServesMainTopic(UserDTO userDTO, AgencyDTO agencyDTO) {
    var mainTopicId = userDTO.getMainTopicId();
    var agencyTopicIds = agencyDTO.getTopicIds();

    if (isNull(mainTopicId) || isNull(agencyTopicIds)) {
      return;
    }

    if (!agencyTopicIds.contains(mainTopicId)) {
      log.warn(
          "Rejected enquiry for agency {} with topic {}; the agency serves topics {}.",
          agencyDTO.getId(),
          mainTopicId,
          agencyTopicIds);
      throw new BadRequestException(
          String.format("Agency %s does not serve topic %s", agencyDTO.getId(), mainTopicId));
    }
  }

  private void checkIfAlreadyRegisteredToTopicAndSameAgency(
      User user, Long topicId, Long agencyId) {
    List<Session> sessions = sessionService.getSessionsForUserByMainTopicId(user, topicId);

    /* Only ACTIVE sessions (NEW / IN_PROGRESS) block a new enquiry. A user
    whose previous topic+agency session is DONE or IN_ARCHIVE must be able
    to re-open a fresh enquiry from their profile. */
    var sessionsWithSameAgencyAndTopic =
        sessions.stream()
            .filter(
                session -> session.getAgencyId() != null && session.getAgencyId().equals(agencyId))
            .filter(
                session ->
                    session.getStatus() == Session.SessionStatus.NEW
                        || session.getStatus() == Session.SessionStatus.IN_PROGRESS)
            .collect(Collectors.toList());

    if (CollectionUtils.isNotEmpty(sessionsWithSameAgencyAndTopic)) {
      log.error(
          String.format(
              "User %s is already registered to topic %d and agency %d",
              user.getUserId(), topicId, agencyId));
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.USER_ALREADY_REGISTERED_WITH_AGENCY_AND_TOPIC,
          HttpStatus.CONFLICT);
    }
  }

  private void checkIfAlreadyRegisteredToConsultingType(User user, int consultingTypeId) {
    List<Session> sessions =
        sessionService.getSessionsForUserByConsultingTypeId(user, consultingTypeId);

    if (CollectionUtils.isNotEmpty(sessions)) {
      log.error(
          String.format(
              "User %s is already registered to consulting type %d",
              user.getUserId(), consultingTypeId));
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.USER_ALREADY_REGISTERED_TO_CONSULTING_TYPE,
          HttpStatus.CONFLICT);
    }
  }
}
