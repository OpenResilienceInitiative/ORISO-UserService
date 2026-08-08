package de.caritas.cob.userservice.api.facade;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateEnquiryMessageResponseDTO;
import de.caritas.cob.userservice.api.container.CreateEnquiryExceptionInformation;
import de.caritas.cob.userservice.api.exception.CreateEnquiryException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CreateEnquiryMessageException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.EnquiryData;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortContext;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortModality;
import de.caritas.cob.userservice.api.service.erstantwort.ErstantwortPayloadBuilder;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.AgencyPreAssignmentRoomService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Creates an enquiry message in the Matrix room owned by the session. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreateEnquiryMessageFacade {

  private final @NonNull SessionService sessionService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull EmailNotificationFacade emailNotificationFacade;
  private final @NonNull ConsultantAgencyService consultantAgencyService;
  private final @NonNull TopicConsultantRoutingService topicConsultantRoutingService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;
  private final @NonNull ErstantwortPayloadBuilder erstantwortPayloadBuilder;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

  public CreateEnquiryMessageResponseDTO createEnquiryMessage(EnquiryData enquiryData) {
    try {
      Session session =
          fetchSessionForEnquiryMessage(enquiryData.getSessionId(), enquiryData.getUser());
      checkIfNotAnonymousEnquiry(session);
      checkIfEnquiryMessageIsAlreadyWrittenForSession(session);

      List<ConsultantAgency> agencyList = resolveConsultantAgenciesForEnquiry(session);
      return createMatrixEnquiryMessage(enquiryData, session, agencyList);
    } catch (CreateEnquiryException exception) {
      log.error("CreateEnquiryMessageFacade error: ", exception);
      throw new InternalServerErrorException(exception.getMessage(), exception);
    }
  }

  private CreateEnquiryMessageResponseDTO createMatrixEnquiryMessage(
      EnquiryData enquiryData, Session session, List<ConsultantAgency> agencyList)
      throws CreateEnquiryException {
    String matrixRoomId = ensureMatrixRoomForEnquiry(session, enquiryData.getUser());
    if (isBlank(enquiryData.getUser().getMatrixUserId())) {
      throw new InternalServerErrorException(
          String.format(
              "Enquiry user %s has no Matrix account", enquiryData.getUser().getUserId()));
    }

    String matrixMessageEventId = "";
    if (!isAppointmentEnquiryMessage(enquiryData)) {
      matrixMessageEventId =
          validateEncryptedMatrixEvent(enquiryData, matrixRoomId, enquiryData.getMatrixEventId());
    }

    var exceptionInformation =
        CreateEnquiryExceptionInformation.builder()
            .session(session)
            .matrixRoomId(matrixRoomId)
            .build();
    updateMatrixSession(session, enquiryData.getLanguage(), matrixRoomId, exceptionInformation);
    sendEnquiryNotifications(session, agencyList);
    postErstantwort(session);

    return new CreateEnquiryMessageResponseDTO()
        .matrixRoomId(matrixRoomId)
        .sessionId(enquiryData.getSessionId())
        .t(matrixMessageEventId);
  }

  private String validateEncryptedMatrixEvent(
      EnquiryData enquiryData, String matrixRoomId, String matrixEventId) {
    if (isBlank(matrixEventId)) {
      throw new InternalServerErrorException(
          String.format(
              "Initial enquiry for session %s requires an encrypted Matrix event",
              enquiryData.getSessionId()));
    }

    String matrixUserId = enquiryData.getUser().getMatrixUserId();
    String matrixAccessToken = matrixSynapseService.loginAsUserAccessToken(matrixUserId);
    if (isBlank(matrixAccessToken)) {
      throw new InternalServerErrorException(
          String.format(
              "Could not validate encrypted Matrix enquiry event for user %s",
              enquiryData.getUser().getUserId()));
    }

    var event =
        matrixSynapseService
            .getRoomEvent(matrixRoomId, matrixEventId, matrixAccessToken)
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        String.format(
                            "Could not read Matrix enquiry event %s in room %s",
                            matrixEventId, matrixRoomId)));

    if (!matrixEventId.equals(event.get("event_id"))) {
      throw new InternalServerErrorException(
          String.format("Matrix enquiry event response did not match %s", matrixEventId));
    }
    if (!"m.room.encrypted".equals(event.get("type"))) {
      throw new InternalServerErrorException(
          String.format("Matrix enquiry event %s is not an encrypted Matrix event", matrixEventId));
    }
    if (!matrixUserId.equals(event.get("sender"))) {
      throw new InternalServerErrorException(
          String.format("Matrix enquiry event %s was not sent by enquiry user", matrixEventId));
    }
    return matrixEventId;
  }

  private String ensureMatrixRoomForEnquiry(Session session, User user) {
    if (!isBlank(session.getMatrixRoomId())) {
      return session.getMatrixRoomId();
    }

    agencyPreAssignmentRoomService.ensureHoldingRoom(session, user);
    if (!isBlank(session.getMatrixRoomId())) {
      return session.getMatrixRoomId();
    }

    throw new InternalServerErrorException(
        String.format("Could not create Matrix room for enquiry session %s", session.getId()));
  }

  private void sendEnquiryNotifications(Session session, List<ConsultantAgency> agencyList) {
    if (session.getIsConsultantDirectlySet()) {
      emailNotificationFacade.sendNewDirectEnquiryEmailNotification(
          session.getConsultant().getId(),
          session.getAgencyId(),
          session.getPostcode(),
          TenantContext.getCurrentTenantData());
    } else {
      emailNotificationFacade.sendNewEnquiryEmailNotification(
          session, TenantContext.getCurrentTenantData());
    }

    persistNewClientRequestNotifications(session, agencyList);
  }

  /**
   * ADR-018 / ORISO-UserService#926: the Erstantwort, posted once, right after the enquiry has been
   * dispatched and the counsellors notified.
   *
   * <p><b>After the notifications on purpose.</b> The person's message reaching a counsellor is the
   * thing that must happen; the platform's own greeting is second. Everything in here is wrapped so
   * that no failure — serialisation, Matrix, the timeline row — can roll back a dispatched enquiry.
   *
   * <p>In this slice only the platform level of the resolution chain has content. The chain itself
   * is already implemented in {@link ErstantwortPayloadBuilder}, so ORISO-Admin#601 adding the
   * Träger editor is a form, not a migration.
   */
  /**
   * The asker's own German variant. Defaults to formal when no user is resolvable — the safer
   * error: addressing somebody formally who expected "Du" is a mismatch, while addressing somebody
   * informally who expected "Sie" reads as a service that does not know who it is talking to.
   */
  private boolean isFormalLanguage(Session session) {
    return session.getUser() == null || session.getUser().isLanguageFormal();
  }

  private void postErstantwort(Session session) {
    try {
      var body =
          erstantwortPayloadBuilder.buildFirstResponseBody(
              ErstantwortContext.builder()
                  /* This facade only ever handles enquiries, and an enquiry exists
                  only in Agency Counselling — checkIfNotAnonymousEnquiry rejects
                  the anonymous Live Chat path before we get here, and Self-Help
                  groups never call it. Pinning the modality rather than deriving
                  it keeps the Live-Chat exclusions honest instead of accidental. */
                  .modality(ErstantwortModality.AGENCY_COUNSELLING)
                  /* Without this every German wording defaulted to the formal
                  variant, so an informal Träger's advice seekers were addressed
                  with "Sie" throughout their own Erstantwort. `languageFormal` on
                  the User is the same flag AskerDataProvider already uses to pick
                  the German variant everywhere else. */
                  .informal(!isFormalLanguage(session))
                  .build());
      if (body == null) {
        return;
      }
      matrixSessionSystemMessageService.postFirstResponseMessage(session, body);
      eventNotificationService.createFirstResponseNotification(session);
    } catch (RuntimeException exception) {
      log.warn("Could not post the Erstantwort for session {}", session.getId(), exception);
    }
  }

  private boolean isAppointmentEnquiryMessage(EnquiryData enquiryData) {
    return enquiryData.getConsultantEmail() != null;
  }

  private List<ConsultantAgency> resolveConsultantAgenciesForEnquiry(Session session) {
    if (session.getMainTopicId() != null) {
      List<String> topicConsultantIds =
          topicConsultantRoutingService.findEligibleConsultantIds(session.getMainTopicId());
      if (!topicConsultantIds.isEmpty()) {
        List<ConsultantAgency> topicConsultantAgencies =
            consultantAgencyService.getConsultantAgenciesByConsultantIds(topicConsultantIds);
        if (!topicConsultantAgencies.isEmpty()) {
          return topicConsultantAgencies;
        }
      }
    }

    return consultantAgencyService.findConsultantsByAgencyId(session.getAgencyId());
  }

  private void persistNewClientRequestNotifications(
      Session session, List<ConsultantAgency> agencyList) {
    try {
      List<String> consultantIds =
          agencyList.stream()
              .map(agency -> agency.getConsultant() != null ? agency.getConsultant().getId() : null)
              .filter(id -> id != null && !id.isBlank())
              .collect(Collectors.toList());
      eventNotificationService.createNewClientRequestNotifications(session, consultantIds);
    } catch (RuntimeException ex) {
      log.warn("Could not persist request.new notifications for session {}", session.getId(), ex);
    }
  }

  private Session fetchSessionForEnquiryMessage(Long sessionId, User user) {
    Optional<Session> session = sessionService.getSession(sessionId);
    if (session.isPresent() && session.get().getUser().getUserId().equals(user.getUserId())) {
      return session.get();
    }
    throw new CreateEnquiryMessageException(
        String.format("Session %s not found for user %s", sessionId, user.getUserId()));
  }

  private void checkIfNotAnonymousEnquiry(Session session) {
    if (session.getRegistrationType().equals(ANONYMOUS)) {
      throw new CreateEnquiryMessageException(
          String.format(
              "Session %s is anonymous and therefore can't have an enquiry message.",
              session.getId()));
    }
  }

  private void checkIfEnquiryMessageIsAlreadyWrittenForSession(Session session) {
    if (nonNull(session.getEnquiryMessageDate())) {
      throw new ConflictException(
          String.format("Enquiry message already written for session %s", session.getId()));
    }
  }

  private void updateMatrixSession(
      Session session,
      String language,
      String matrixRoomId,
      CreateEnquiryExceptionInformation exceptionInformation)
      throws CreateEnquiryException {
    try {
      session.setMatrixRoomId(matrixRoomId);
      session.setStatus(SessionStatus.NEW);
      session.setEnquiryMessageDate(nowInUtc());
      if (nonNull(language)) {
        session.setLanguageCode(LanguageCode.getByCode(language));
      }
      if (nonNull(session.getConsultant())) {
        session.setStatus(SessionStatus.IN_PROGRESS);
      }
      sessionService.saveSession(session);
    } catch (InternalServerErrorException exception) {
      throw new CreateEnquiryException(
          String.format(
              "Could not update session %s with Matrix room %s", session.getId(), matrixRoomId),
          exception,
          exceptionInformation);
    }
  }
}
