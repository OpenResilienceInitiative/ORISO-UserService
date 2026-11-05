package de.caritas.cob.userservice.api.service.matrixgroup;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Native ordinary-client join veto. No membership/history call, lock or admission grant. */
@Service
@RequiredArgsConstructor
public class MatrixGroupJoinPolicyService {
  private final ChatMatrixRoomHistory rooms;
  private final GroupCounsellingDpaPolicy ownerPolicy;

  @Transactional(readOnly = true)
  public Optional<ChatMatrixRoomHistory.Context> context(String room) {
    return rooms.context(room);
  }

  @Transactional(readOnly = true)
  public boolean applicableAndAllowed(String room) {
    try {
      var context = rooms.context(room);
      if (context.isEmpty() || !context.get().applicable()) return false;
      ownerPolicy.requireNewEnrolment(context.get().series());
      return true;
    } catch (CustomValidationHttpStatusException refusal) {
      throw refusal;
    } catch (RuntimeException unavailable) {
      throw MatrixGroupParticipationHistory.unavailable();
    }
  }
}
