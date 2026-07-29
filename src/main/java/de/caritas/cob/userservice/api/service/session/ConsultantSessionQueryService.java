package de.caritas.cob.userservice.api.service.session;

import static java.util.Collections.emptyList;
import static java.util.Objects.nonNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionSupervisor;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.SessionSupervisorRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Focused read boundary for consultant-facing session lists and visibility queries. */
@Service
@RequiredArgsConstructor
public class ConsultantSessionQueryService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;
  private final @NonNull GroupChatParticipantRepository groupChatParticipantRepository;
  private final @NonNull SessionAccessService sessionAccessService;
  private final @NonNull SessionSupervisorRepository sessionSupervisorRepository;

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getTeamSessionsForConsultant(Consultant consultant) {
    List<Session> sessions = new ArrayList<>();

    Set<ConsultantAgency> consultantAgencies = consultant.getConsultantAgencies();
    if (nonNull(consultantAgencies)) {
      List<Long> consultantAgencyIds =
          consultantAgencies.stream()
              .map(ConsultantAgency::getAgencyId)
              .collect(Collectors.toList());

      List<Session> teamSessions =
          sessionRepository
              .findByAgencyIdInAndConsultantNotAndStatusAndTeamSessionOrderByCreateDateAsc(
                  consultantAgencyIds, consultant, SessionStatus.IN_PROGRESS, true);
      if (teamSessions != null) {
        sessions.addAll(teamSessions);
      }

      List<Session> ownedGroupChats =
          sessionRepository.findByConsultantAndTeamSessionAndStatus(
              consultant, true, SessionStatus.IN_PROGRESS);
      if (ownedGroupChats != null) {
        sessions.addAll(ownedGroupChats);
      }

      List<GroupChatParticipant> participations =
          groupChatParticipantRepository.findByConsultantId(consultant.getId());
      if (participations != null && !participations.isEmpty()) {
        List<Long> participantSessionIds =
            participations.stream()
                .map(GroupChatParticipant::getChatId)
                .collect(Collectors.toList());
        Iterable<Session> participantSessionsIterable =
            sessionRepository.findAllById(participantSessionIds);
        List<Session> participantSessions = new ArrayList<>();
        participantSessionsIterable.forEach(participantSessions::add);
        if (!participantSessions.isEmpty()) {
          sessions.addAll(participantSessions);
        }
      }

      List<SessionSupervisor> supervisions =
          sessionSupervisorRepository.findActiveSupervisionsByConsultantId(consultant.getId());
      if (supervisions != null && !supervisions.isEmpty()) {
        List<Session> supervisedSessions =
            supervisions.stream()
                .map(SessionSupervisor::getSession)
                .filter(session -> session.getStatus() == SessionStatus.IN_PROGRESS)
                .collect(Collectors.toList());
        if (!supervisedSessions.isEmpty()) {
          sessions.addAll(supervisedSessions);
        }
      }
    }

    return mapSessionsToConsultantSessionDto(sessions);
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getRegisteredEnquiriesForConsultant(
      Consultant consultant) {
    List<Session> mergedSessions = new ArrayList<>();

    Set<ConsultantAgency> consultantAgencies = consultant.getConsultantAgencies();
    if (isNotEmpty(consultantAgencies)) {
      List<Long> consultantAgencyIds =
          consultantAgencies.stream()
              .map(ConsultantAgency::getAgencyId)
              .collect(Collectors.toList());
      mergedSessions.addAll(retrieveRegisteredSessions(consultantAgencyIds));
    }

    List<Long> consultantTopicIds =
        consultantTopicRepository.findTopicIdsByConsultantId(consultant.getId());
    if (isNotEmpty(consultantTopicIds)) {
      mergedSessions.addAll(retrieveRegisteredSessionsByMainTopicIds(consultantTopicIds));
    }

    if (mergedSessions.isEmpty()) {
      return emptyList();
    }

    List<Session> dedupedSessions =
        mergedSessions.stream()
            .filter(Objects::nonNull)
            .collect(
                Collectors.collectingAndThen(
                    Collectors.toMap(
                        Session::getId,
                        session -> session,
                        (left, right) -> left,
                        LinkedHashMap::new),
                    map -> new ArrayList<>(map.values())));

    dedupedSessions.sort(
        Comparator.comparing(
                Session::getCreateDate, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed());

    return mapSessionsToConsultantSessionDto(dedupedSessions);
  }

  private List<Session> retrieveRegisteredSessions(List<Long> consultantAgencyIds) {
    return sessionRepository
        .findByAgencyIdInAndConsultantIsNullAndStatusAndRegistrationTypeOrderByCreateDateDesc(
            consultantAgencyIds, SessionStatus.NEW, RegistrationType.REGISTERED)
        .stream()
        .filter(this::isVisibleRegisteredEnquiryForConsultant)
        .collect(Collectors.toList());
  }

  private List<Session> retrieveRegisteredSessionsByMainTopicIds(List<Long> topicIds) {
    return sessionRepository
        .findByMainTopicIdInAndConsultantIsNullAndStatusAndRegistrationTypeOrderByCreateDateDesc(
            topicIds, SessionStatus.NEW, RegistrationType.REGISTERED)
        .stream()
        .filter(sessionAccessService::isAnonymousStyleRegistration)
        .filter(this::isVisibleRegisteredEnquiryForConsultant)
        .collect(Collectors.toList());
  }

  private boolean isVisibleRegisteredEnquiryForConsultant(Session session) {
    if (!sessionAccessService.isAnonymousStyleRegistration(session)) {
      return true;
    }
    return nonNull(session.getUser()) && nonNull(session.getUser().getDataPrivacyConfirmation());
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getActiveAndDoneSessionsForConsultant(
      Consultant consultant) {
    return Stream.of(
            getSessionsForConsultantByStatus(consultant, SessionStatus.IN_PROGRESS),
            getSessionsForConsultantByStatus(consultant, SessionStatus.DONE))
        .flatMap(Collection::stream)
        .map(session -> new SessionMapper().toConsultantSessionDto(session))
        .collect(Collectors.toList());
  }

  private List<Session> getSessionsForConsultantByStatus(
      Consultant consultant, SessionStatus sessionStatus) {
    List<Session> assignedSessions =
        sessionRepository.findByConsultantAndStatus(consultant, sessionStatus);
    List<SessionSupervisor> supervisions =
        sessionSupervisorRepository.findActiveSupervisionsByConsultantId(consultant.getId());
    List<Session> supervisedSessions =
        supervisions.stream()
            .map(SessionSupervisor::getSession)
            .filter(session -> session.getStatus() == sessionStatus)
            .collect(Collectors.toList());

    List<Session> allSessions = new ArrayList<>(assignedSessions);
    for (Session supervised : supervisedSessions) {
      if (allSessions.stream().noneMatch(session -> session.getId().equals(supervised.getId()))) {
        allSessions.add(supervised);
      }
    }
    return allSessions;
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getAllowedSessionsByConsultantAndRoomIds(
      Consultant consultant, Set<String> matrixRoomIds, Set<String> roles) {
    sessionAccessService.checkForUserOrConsultantRole(roles);
    var sessions = sessionRepository.findByMatrixRoomIdIn(matrixRoomIds);
    List<Session> allowedSessions =
        sessions.stream()
            .filter(
                session -> sessionAccessService.isConsultantPermittedToSession(consultant, session))
            .collect(Collectors.toList());
    return mapSessionsToConsultantSessionDto(allowedSessions);
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getSessionsByIds(
      Consultant consultant, Set<Long> sessionIds, Set<String> roles) {
    sessionAccessService.checkForUserOrConsultantRole(roles);
    var sessions =
        StreamSupport.stream(sessionRepository.findAllById(sessionIds).spliterator(), false)
            .filter(
                session -> sessionAccessService.isConsultantPermittedToSession(consultant, session))
            .collect(Collectors.toList());
    return mapSessionsToConsultantSessionDto(sessions);
  }

  public List<ConsultantSessionResponseDTO> getVisibleAnonymousLiveChatEnquiriesByIds(
      Consultant consultant, Set<Long> sessionIds) {
    if (!isNotEmpty(sessionIds)) {
      return emptyList();
    }
    var topicIds = consultantTopicRepository.findTopicIdsByConsultantId(consultant.getId());
    if (topicIds == null || topicIds.isEmpty()) {
      return emptyList();
    }
    var sessions =
        runCrossTenant(
            () ->
                sessionRepository.findVisibleAnonymousLiveChatEnquiriesForConsultantByIds(
                    sessionIds,
                    new HashSet<>(topicIds),
                    SessionStatus.NEW,
                    RegistrationType.ANONYMOUS));
    return mapSessionsToConsultantSessionDto(sessions);
  }

  public List<ConsultantSessionResponseDTO> getDirectlyAssignedSessionsByIdsCrossTenant(
      Consultant consultant, Set<Long> sessionIds) {
    if (!isNotEmpty(sessionIds)) {
      return emptyList();
    }
    var sessions =
        runCrossTenant(
            () ->
                StreamSupport.stream(sessionRepository.findAllById(sessionIds).spliterator(), false)
                    .filter(session -> session.isAdvisedBy(consultant))
                    .collect(Collectors.toList()));
    return mapSessionsToConsultantSessionDto(sessions);
  }

  private <T> T runCrossTenant(Supplier<T> query) {
    var callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      return query.get();
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getArchivedSessionsForConsultant(
      Consultant consultant) {
    return mapSessionsToConsultantSessionDto(
        sessionRepository.findByConsultantAndStatusOrderByUpdateDateDesc(
            consultant, SessionStatus.IN_ARCHIVE));
  }

  @Transactional(readOnly = true)
  public List<ConsultantSessionResponseDTO> getArchivedTeamSessionsForConsultant(
      Consultant consultant) {
    Set<ConsultantAgency> consultantAgencies = consultant.getConsultantAgencies();
    if (isNotEmpty(consultantAgencies)) {
      List<Long> consultantAgencyIds =
          consultantAgencies.stream()
              .map(ConsultantAgency::getAgencyId)
              .collect(Collectors.toList());
      return mapSessionsToConsultantSessionDto(
          sessionRepository
              .findByAgencyIdInAndConsultantNotAndStatusAndTeamSessionIsTrueOrderByUpdateDateDesc(
                  consultantAgencyIds, consultant, SessionStatus.IN_ARCHIVE));
    }
    return emptyList();
  }

  private List<ConsultantSessionResponseDTO> mapSessionsToConsultantSessionDto(
      List<Session> sessions) {
    if (nonNull(sessions)) {
      return sessions.stream()
          .map(session -> new SessionMapper().toConsultantSessionDto(session))
          .collect(Collectors.toList());
    }
    return emptyList();
  }
}
