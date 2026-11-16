package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.UserAgencyService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;

/** Creates the domain relation between a user and a group-chat agency. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreateUserChatRelationFacade {

  private final @NonNull UserAgencyService userAgencyService;
  private final @NonNull RollbackFacade rollbackFacade;
  private final AuditingHandler auditingHandler;

  /**
   * Makes sure the user has a relation to the agency of the registration. The call is idempotent:
   * when the relation already exists it is reused and nothing is written. An existing asker who
   * asks for further counselling at an agency they already know must get the new session, not a 400
   * (#1646). Duplicate sessions are still guarded by the session validation constraints in {@link
   * CreateSessionFacade}.
   */
  public void initializeUserChatAgencyRelation(UserDTO userDTO, User user) {
    if (isAlreadyAssignedToAgency(user, userDTO.getAgencyId())) {
      log.info(
          "User {} already has a relation to agency {}; reusing it",
          user.getUserId(),
          userDTO.getAgencyId());
      return;
    }

    UserAgency userAgency = new UserAgency(user, userDTO.getAgencyId());
    try {
      auditingHandler.markCreated(userAgency);
      userAgencyService.saveUserAgency(userAgency);
    } catch (InternalServerErrorException serviceException) {
      rollbackFacade.rollBackUserAccount(
          RollbackUserAccountInformation.builder()
              .userId(user.getUserId())
              .user(user)
              .userAgency(userAgency)
              .rollBackUserAccount(Boolean.parseBoolean(userDTO.getTermsAccepted()))
              .build());
      throw new InternalServerErrorException(
          "Could not create user-agency relation for group chat registration",
          LogService::logDatabaseError);
    }
  }

  private boolean isAlreadyAssignedToAgency(User user, Long agencyId) {
    return userAgencyService.getUserAgenciesByUser(user).stream()
        .anyMatch(agency -> agency.getAgencyId().equals(agencyId));
  }
}
