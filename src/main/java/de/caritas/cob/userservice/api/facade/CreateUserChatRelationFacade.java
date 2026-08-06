package de.caritas.cob.userservice.api.facade;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.facade.rollback.RollbackFacade;
import de.caritas.cob.userservice.api.facade.rollback.RollbackUserAccountInformation;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserAgency;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.UserAgencyService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.auditing.AuditingHandler;
import org.springframework.stereotype.Service;

/** Creates the domain relation between a user and a group-chat agency. */
@Service
@RequiredArgsConstructor
public class CreateUserChatRelationFacade {

  private final @NonNull UserAgencyService userAgencyService;
  private final @NonNull RollbackFacade rollbackFacade;
  private final AuditingHandler auditingHandler;

  public void initializeUserChatAgencyRelation(UserDTO userDTO, User user) {
    UserAgency userAgency = new UserAgency(user, userDTO.getAgencyId());
    checkIfAlreadyAssignedToAgency(user, userAgency);

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

  private void checkIfAlreadyAssignedToAgency(User user, UserAgency userAgency) {
    List<UserAgency> userAgencies = userAgencyService.getUserAgenciesByUser(user);

    if (userAgencies.stream()
        .anyMatch(agency -> agency.getAgencyId().equals(userAgency.getAgencyId()))) {
      throw new BadRequestException(
          String.format(
              "User %s already assigned to chat relation agency %s",
              user.getUserId(), userAgency.getAgencyId()));
    }
  }
}
