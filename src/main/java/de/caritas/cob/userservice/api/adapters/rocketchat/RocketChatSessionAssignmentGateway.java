package de.caritas.cob.userservice.api.adapters.rocketchat;

import de.caritas.cob.userservice.api.admin.service.rocketchat.RocketChatRemoveFromGroupOperationService;
import de.caritas.cob.userservice.api.exception.MessageClientException;
import de.caritas.cob.userservice.api.exception.rocketchat.RocketChatUserNotInitializedException;
import de.caritas.cob.userservice.api.facade.RocketChatFacade;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.SessionAssignmentChatGateway;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Rocket.Chat adapter for session-assignment group operations. */
@Component
@RequiredArgsConstructor
public class RocketChatSessionAssignmentGateway implements SessionAssignmentChatGateway {

  private final RocketChatFacade rocketChatFacade;
  private final RocketChatCredentialsProvider credentialsProvider;
  private final IdentityClient identityClient;
  private final ConsultingTypeManager consultingTypeManager;

  @Override
  public void addUserToGroup(String chatUserId, String roomId) {
    rocketChatFacade.addUserToRocketChatGroup(chatUserId, roomId);
  }

  @Override
  public void removeSystemMessages(String roomId) {
    rocketChatFacade.removeSystemMessagesFromRocketChatGroup(roomId);
  }

  @Override
  public List<String> findMemberIds(String roomId) {
    return rocketChatFacade.retrieveRocketChatMemberIds(roomId);
  }

  @Override
  public String technicalUserId() throws MessageClientException {
    try {
      return credentialsProvider.getTechnicalUser().getRocketChatUserId();
    } catch (RocketChatUserNotInitializedException exception) {
      throw new MessageClientException(exception);
    }
  }

  @Override
  public void removeConsultantsOrRollback(Session session, List<Consultant> consultants) {
    operation(session, consultants).removeFromGroupOrRollbackOnFailure();
  }

  @Override
  public void removeConsultantsIgnoringMissingGroup(Session session, List<Consultant> consultants) {
    operation(session, consultants).removeFromGroupAndIgnoreGroupNotFound();
  }

  private RocketChatRemoveFromGroupOperationService operation(
      Session session, List<Consultant> consultants) {
    return RocketChatRemoveFromGroupOperationService.getInstance(
            rocketChatFacade, identityClient, consultingTypeManager)
        .onSessionConsultants(Map.of(session, consultants));
  }
}
