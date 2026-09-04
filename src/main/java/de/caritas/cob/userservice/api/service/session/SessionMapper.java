package de.caritas.cob.userservice.api.service.session;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.toIsoTime;
import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.toUnixTime;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GroupSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionSupervisionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionUserDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserSessionResponseDTO;
import de.caritas.cob.userservice.api.helper.SessionDataKeyRegistration;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorMarkerRow;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;

/** Mapper class to map a {@link Session} to possible dto objects. */
@RequiredArgsConstructor
public class SessionMapper {

  /**
   * Maps the given {@link Session} to a {@link ConsultantSessionResponseDTO}.
   *
   * @return the mapped {@link ConsultantSessionResponseDTO}
   */
  public ConsultantSessionResponseDTO toConsultantSessionDto(Session session) {
    return new ConsultantSessionResponseDTO()
        .session(convertToSessionDTO(session))
        .user(convertToSessionUserDTO(session))
        .consultant(convertToSessionConsultantForConsultantDTO(session.getConsultant()))
        .latestMessage(extractEnquiryMessageDate(session));
  }

  private Date extractEnquiryMessageDate(Session session) {
    LocalDateTime enquiryMessageDate = session.getEnquiryMessageDate();
    return nonNull(enquiryMessageDate) ? Date.valueOf(enquiryMessageDate.toLocalDate()) : null;
  }

  /**
   * Maps the given {@link Session} to a {@link SessionDTO}.
   *
   * @param session the session to be mapped
   * @return the mapped {@link SessionDTO}
   */
  public SessionDTO convertToSessionDTO(Session session) {
    return new SessionDTO()
        .id(session.getId())
        .agencyId(session.getAgencyId())
        .consultingType(session.getConsultingTypeId())
        .status(session.getStatus().getValue())
        .conversationType(
            session.getConversationType() == null
                ? null
                : de.caritas.cob.userservice.api.adapters.web.dto.ConversationType.fromValue(
                    session.getConversationType().name()))
        .postcode(session.getPostcode())
        .matrixRoomId(session.getMatrixRoomId())
        .askerMatrixUserId(
            nonNull(session.getUser()) && nonNull(session.getUser().getMatrixUserId())
                ? session.getUser().getMatrixUserId()
                : null)
        .messageDate(toUnixTime(session.getEnquiryMessageDate()))
        .isTeamSession(session.isTeamSession())
        .language(LanguageCode.fromValue(session.getLanguageCode().name()))
        .registrationType(session.getRegistrationType().name())
        .createDate(toIsoTime(session.getCreateDate()))
        /* ADR-022 decision 2, read path. The pointer itself travels so a client that already
        holds the currently published legal-text version can tell a stale consent from a
        current one; consentRequired answers the part the server can decide on its own —
        Gate 2 applies to this room and nothing has been recorded yet. */
        .consentedLegalVersionId(session.getConsentedLegalVersionId())
        .consentRequired(
            session.isConsentGateApplicable() && isNull(session.getConsentedLegalVersionId()))
        .topic(new SessionTopicDTO().id(session.getMainTopicId()));
  }

  /**
   * Builds the ADR-008 supervision marker of one session as seen by the requesting consultant.
   *
   * <p>{@code supervisedByMe} is true exactly when the requester is one of the active supervisors —
   * the reason a supervised case shows up in their list at all. Ids and display names are parallel
   * lists in row order. Always returns an object (empty marker when nothing is supervised), so the
   * frontend needs no null check on consultant-facing lists.
   *
   * @param activeSupervisors the active supervisor rows of this session (nullable = none)
   * @param requestingConsultantId the requester's keycloak id (nullable = nobody)
   * @param displayName resolves the display name shown to colleagues for one row
   * @return the marker, never null
   */
  public SessionSupervisionDTO toSupervisionDTO(
      List<SessionSupervisorMarkerRow> activeSupervisors,
      String requestingConsultantId,
      Function<SessionSupervisorMarkerRow, String> displayName) {
    List<String> ids = new ArrayList<>();
    List<String> names = new ArrayList<>();
    boolean supervisedByMe = false;
    if (nonNull(activeSupervisors)) {
      for (var row : activeSupervisors) {
        ids.add(row.consultantId());
        names.add(displayName.apply(row));
        supervisedByMe |=
            nonNull(requestingConsultantId) && requestingConsultantId.equals(row.consultantId());
      }
    }
    return new SessionSupervisionDTO()
        .supervisedByMe(supervisedByMe)
        .supervisorConsultantIds(ids)
        .supervisorDisplayNames(names);
  }

  private SessionUserDTO convertToSessionUserDTO(Session session) {
    if (isNull(session.getUser())) {
      return null;
    }

    var sessionUserDto = new SessionUserDTO();
    sessionUserDto.setId(session.getUser().getUserId());
    sessionUserDto.setUsername(
        new UsernameTranscoder().decodeUsername(session.getUser().getUsername()));
    sessionUserDto.setDeleted(session.getUser().getDeleteDate() != null);
    if (nonNull(session.getSessionData())) {
      var sessionData = buildSessionDataMapFromSession(session);
      sessionUserDto.setSessionData(sessionData);
      var displayName = sessionData.get(SessionDataKeyRegistration.DISPLAY_NAME.getValue());
      if (displayName instanceof String value) {
        sessionUserDto.setDisplayName(value);
      }
    }
    return sessionUserDto;
  }

  private SessionConsultantForConsultantDTO convertToSessionConsultantForConsultantDTO(
      Consultant consultant) {
    return nonNull(consultant)
        ? new SessionConsultantForConsultantDTO()
            .id(consultant.getId())
            .firstName(consultant.getFirstName())
            .lastName(consultant.getLastName())
        : null;
  }

  public Map<String, Object> buildSessionDataMapFromSession(Session session) {
    Map<String, Object> sessionDataMap = new LinkedHashMap<>();
    session.getSessionData().stream()
        .filter(sessionData -> SessionDataKeyRegistration.containsKey(sessionData.getKey()))
        .forEach(sessionData -> sessionDataMap.put(sessionData.getKey(), sessionData.getValue()));

    return sessionDataMap;
  }

  public GroupSessionResponseDTO toGroupSessionResponse(
      UserSessionResponseDTO userSessionResponse) {
    var response =
        new GroupSessionResponseDTO()
            .session(userSessionResponse.getSession())
            .agency(userSessionResponse.getAgency())
            .chat(userSessionResponse.getChat())
            .latestMessage(userSessionResponse.getLatestMessage());

    var sessionConsultant = userSessionResponse.getConsultant();
    if (sessionConsultant == null) {
      return response;
    }
    var consultant =
        GroupSessionConsultantDTO.builder()
            .id(sessionConsultant.getConsultantId())
            .username(sessionConsultant.getUsername())
            .displayName(sessionConsultant.getDisplayName())
            .isAbsent(sessionConsultant.isAbsent())
            .absenceMessage(sessionConsultant.getAbsenceMessage());
    return response.consultant(consultant.build());
  }

  public GroupSessionResponseDTO toGroupSessionResponse(
      ConsultantSessionResponseDTO consultantSessionResponse) {
    var response =
        new GroupSessionResponseDTO()
            .session(consultantSessionResponse.getSession())
            .user(consultantSessionResponse.getUser())
            .chat(consultantSessionResponse.getChat())
            .latestMessage(consultantSessionResponse.getLatestMessage());

    var sessionConsultant = consultantSessionResponse.getConsultant();
    if (sessionConsultant == null) {
      return response;
    }
    var consultant =
        GroupSessionConsultantDTO.builder()
            .id(sessionConsultant.getId())
            .firstName(sessionConsultant.getFirstName())
            .lastName(sessionConsultant.getLastName())
            .displayName(sessionConsultant.getDisplayName());
    return response.consultant(consultant.build());
  }
}
