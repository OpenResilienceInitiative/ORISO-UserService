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
import de.caritas.cob.userservice.api.service.liveevents.LiveEventNotificationService;
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
  private final @NonNull LiveEventNotificationService liveEventNotificationService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull AgencyPreAssignmentRoomService agencyPreAssignmentRoomService;

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
      String matrixAccessToken =
          matrixSynapseService.loginAsUserAccessToken(enquiryData.getUser().getMatrixUserId());
      if (isBlank(matrixAccessToken)) {
        throw new InternalServerErrorException(
            String.format(
                "Could not create Matrix token for enquiry user %s",
                enquiryData.getUser().getUserId()));
      }

      var matrixResponse =
          matrixSynapseService.sendMessage(
              matrixRoomId, enquiryData.getMessage(), matrixAccessToken);
      if (matrixResponse == null || matrixResponse.containsKey("error")) {
        throw new InternalServerErrorException(
            String.format(
                "Could not post Matrix enquiry message to room %s for session %s",
                matrixRoomId, session.getId()));
      }
      matrixMessageEventId = String.valueOf(matrixResponse.getOrDefault("event_id", ""));
    }

    var exceptionInformation =
        CreateEnquiryExceptionInformation.builder()
            .session(session)
            .rcGroupId(matrixRoomId)
            .build();
    updateMatrixSession(session, enquiryData.getLanguage(), matrixRoomId, exceptionInformation);
    sendEnquiryNotifications(session, agencyList);

    return new CreateEnquiryMessageResponseDTO()
        .rcGroupId(matrixRoomId)
        .sessionId(enquiryData.getSessionId())
        .t(matrixMessageEventId);
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

    notifyEligibleConsultantsAboutLiveChatEnquiry(session);
    persistNewClientRequestNotifications(session, agencyList);
  }

  private boolean isAppointmentEnquiryMessage(EnquiryData enquiryData) {
    return enquiryData.getConsultantEmail() != null;
  }

  private List<ConsultantAgency> resolveConsultantAgenciesForEnquiry(Session session) {
    if (session.getMainTopicId() != null) {
      List<String> topicConsultantIds =
          topicConsultantRoutingService.findEligibleConsultantIds(
              session.getMainTopicId(), session.getConsultingTypeId());
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

  private void notifyEligibleConsultantsAboutLiveChatEnquiry(Session session) {
    if (!sessionService.isAnonymousStyleRegistration(session)) {
      return;
    }

    List<String> consultantIds;
    if (session.getMainTopicId() != null) {
      consultantIds =
          topicConsultantRoutingService.findEligibleConsultantIds(
              session.getMainTopicId(), session.getConsultingTypeId());
    } else {
      consultantIds =
          consultantAgencyService.findConsultantsByAgencyId(session.getAgencyId()).stream()
              .map(agency -> agency.getConsultant().getId())
              .collect(Collectors.toList());
    }

    if (!consultantIds.isEmpty()) {
      liveEventNotificationService.sendLiveNewAnonymousEnquiryEventToUsers(
          consultantIds, session.getId());
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
      session.setGroupId(matrixRoomId);
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
