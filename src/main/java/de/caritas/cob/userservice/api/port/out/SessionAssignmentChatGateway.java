package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.exception.MessageClientException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import java.util.List;

/**
 * Transport-neutral legacy-chat boundary for assigning a session.
 *
 * <p>The assignment module works with stable user and room identifiers only. Rocket.Chat DTOs,
 * credentials and rollback implementation details stay behind the adapter.
 */
public interface SessionAssignmentChatGateway {

  void addUserToGroup(String chatUserId, String roomId);

  void removeSystemMessages(String roomId);

  List<String> findMemberIds(String roomId);

  String technicalUserId() throws MessageClientException;

  void removeConsultantsOrRollback(Session session, List<Consultant> consultants);

  void removeConsultantsIgnoringMissingGroup(Session session, List<Consultant> consultants);
}
