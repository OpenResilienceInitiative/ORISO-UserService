package de.caritas.cob.userservice.api.facade.assignsession;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.exception.MessageClientException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import de.caritas.cob.userservice.api.service.ConsultantService;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Provides consultants of Rocket.Chat group that don't have the authorization for it. */
@Service
@RequiredArgsConstructor
public class UnauthorizedMembersProvider {

  @Value("${rocket.systemuser.id}")
  private String rocketChatSystemUserId;

  private final @NonNull ConsultantService consultantService;
  private final @NonNull SessionAssignmentChatGateway sessionAssignmentChatGateway;

  /**
   * Obtains a list of {@link Consultant}s which are not authorized to view the given Rocket.Chat
   * group and therefore should be removed.
   *
   * @param rcGroupId the Rocket.Chat group ID
   * @param session {@link Session}
   * @param consultant {@link Consultant}
   * @param memberIds current stable chat member identifiers
   * @return list of {@link Consultant}s to be removed
   */
  @Transactional
  public List<Consultant> obtainConsultantsToRemove(
      String rcGroupId,
      Session session,
      Consultant consultant,
      List<String> memberIds,
      Consultant consultantToKeep) {
    if (memberIds.isEmpty()) {
      return List.of();
    }
    var authorizedMembers = obtainAuthorizedMembers(rcGroupId, session, consultant);
    if (nonNull(consultantToKeep)) {
      authorizedMembers.add(consultantToKeep.getRocketChatId());
    }

    return memberIds.stream()
        .filter(memberRcId -> !authorizedMembers.contains(memberRcId))
        .map(consultantService::getConsultantByRcUserId)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toList());
  }

  @Transactional
  public List<Consultant> obtainConsultantsToRemove(
      String rcGroupId, Session session, Consultant consultant, List<String> memberIds) {

    return obtainConsultantsToRemove(rcGroupId, session, consultant, memberIds, null);
  }

  private List<String> obtainAuthorizedMembers(
      String rcGroupId, Session session, Consultant consultant) {
    List<String> authorizedMembers = new ArrayList<>();
    addConsultantAndAskerOfSession(session, consultant, authorizedMembers);
    addTechnicalUsers(authorizedMembers);
    addTeamConsultantsIfNecessary(rcGroupId, session, authorizedMembers);

    return authorizedMembers;
  }

  private void addConsultantAndAskerOfSession(
      Session session, Consultant consultant, List<String> authorizedMembers) {
    authorizedMembers.add(session.getUser().getRcUserId());
    authorizedMembers.add(consultant.getRocketChatId());
  }

  private void addTechnicalUsers(List<String> authorizedMembers) {
    try {
      authorizedMembers.add(sessionAssignmentChatGateway.technicalUserId());
    } catch (MessageClientException e) {
      throw new InternalServerErrorException("Rocket.Chat technical user not initialized.");
    }
    authorizedMembers.add(rocketChatSystemUserId);
  }

  private void addTeamConsultantsIfNecessary(
      String rcGroupId, Session session, List<String> authorizedMembers) {
    List<Consultant> consultantsOfAgency =
        consultantService.findConsultantsByAgencyId(session.getAgencyId());
    addTeamConsultantsIfTeamSession(session, authorizedMembers, consultantsOfAgency);
  }

  private void addTeamConsultantsIfTeamSession(
      Session session, List<String> authorizedMembers, List<Consultant> consultantsOfAgency) {
    if (session.isTeamSession()) {
      consultantsOfAgency.stream()
          .filter(Consultant::isTeamConsultant)
          .map(Consultant::getRocketChatId)
          .filter(
              rocketChatId ->
                  !rocketChatId.equalsIgnoreCase(session.getConsultant().getRocketChatId()))
          .forEach(authorizedMembers::add);
    }
  }
}
