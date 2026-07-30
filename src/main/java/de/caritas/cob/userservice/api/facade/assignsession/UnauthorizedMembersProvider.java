package de.caritas.cob.userservice.api.facade.assignsession;

import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.ConsultantService;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Provides Matrix-room consultants who no longer have authorization for a session. */
@Service
@RequiredArgsConstructor
public class UnauthorizedMembersProvider {

  private final @NonNull ConsultantService consultantService;

  /**
   * Obtains consultants who are not authorized to view the given Matrix room and should be removed.
   *
   * @param roomId the Matrix room ID
   * @param session {@link Session}
   * @param consultant {@link Consultant}
   * @param memberIds current stable chat member identifiers
   * @return list of {@link Consultant}s to be removed
   */
  @Transactional
  public List<Consultant> obtainConsultantsToRemove(
      String roomId,
      Session session,
      Consultant consultant,
      List<String> memberIds,
      Consultant consultantToKeep) {
    if (memberIds.isEmpty()) {
      return List.of();
    }
    var authorizedMembers = obtainAuthorizedMembers(session, consultant);
    if (nonNull(consultantToKeep)) {
      authorizedMembers.add(consultantToKeep.getMatrixUserId());
    }

    return memberIds.stream()
        .filter(memberMatrixId -> !authorizedMembers.contains(memberMatrixId))
        .map(consultantService::getConsultantByMatrixUserId)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.toList());
  }

  @Transactional
  public List<Consultant> obtainConsultantsToRemove(
      String roomId, Session session, Consultant consultant, List<String> memberIds) {

    return obtainConsultantsToRemove(roomId, session, consultant, memberIds, null);
  }

  private List<String> obtainAuthorizedMembers(Session session, Consultant consultant) {
    List<String> authorizedMembers = new ArrayList<>();
    addConsultantAndAskerOfSession(session, consultant, authorizedMembers);
    addTeamConsultantsIfNecessary(session, authorizedMembers);

    return authorizedMembers;
  }

  private void addConsultantAndAskerOfSession(
      Session session, Consultant consultant, List<String> authorizedMembers) {
    authorizedMembers.add(session.getUser().getMatrixUserId());
    authorizedMembers.add(consultant.getMatrixUserId());
  }

  private void addTeamConsultantsIfNecessary(Session session, List<String> authorizedMembers) {
    List<Consultant> consultantsOfAgency =
        consultantService.findConsultantsByAgencyId(session.getAgencyId());
    addTeamConsultantsIfTeamSession(session, authorizedMembers, consultantsOfAgency);
  }

  private void addTeamConsultantsIfTeamSession(
      Session session, List<String> authorizedMembers, List<Consultant> consultantsOfAgency) {
    if (session.isTeamSession()) {
      consultantsOfAgency.stream()
          .filter(Consultant::isTeamConsultant)
          .map(Consultant::getMatrixUserId)
          .filter(UnauthorizedMembersProvider::hasMatrixIdentity)
          .filter(
              matrixUserId ->
                  !matrixUserId.equalsIgnoreCase(session.getConsultant().getMatrixUserId()))
          .forEach(authorizedMembers::add);
    }
  }

  private static boolean hasMatrixIdentity(String matrixUserId) {
    return isNotBlank(matrixUserId);
  }
}
