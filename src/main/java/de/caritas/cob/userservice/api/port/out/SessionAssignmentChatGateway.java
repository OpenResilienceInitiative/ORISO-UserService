package de.caritas.cob.userservice.api.port.out;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import java.util.List;

/**
 * Transport-neutral chat boundary for assigning a session.
 *
 * <p>The assignment module works with domain objects and stable room/member identifiers only.
 * Matrix transport details stay behind the adapter.
 */
public interface SessionAssignmentChatGateway {

  void prepareAssignment(Session session, Consultant consultant);

  List<String> findMemberIds(String roomId);

  void removeConsultants(
      Session session, Consultant actingConsultant, List<Consultant> consultants);
}
