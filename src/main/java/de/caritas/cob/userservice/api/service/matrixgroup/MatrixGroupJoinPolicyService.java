package de.caritas.cob.userservice.api.service.matrixgroup;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Native ordinary-client join veto. No membership/history call, lock or admission grant. */
@Service
@RequiredArgsConstructor
public class MatrixGroupJoinPolicyService {
  private final ChatRepository chats;
  private final GroupCounsellingDpaPolicy ownerPolicy;

  @Transactional(readOnly = true)
  public boolean applicableAndAllowed(String room) {
    try {
      return TenantContext.supplyAcrossTenants(
          () -> {
            var mapped = chats.findByMatrixRoomIdIn(Set.of(room));
            if (mapped.isEmpty()) return false;
            if (mapped.size() != 1) throw MatrixGroupParticipationHistory.unavailable();
            var chat = mapped.getFirst();
            if (chat.getCurrentOccurrenceIndex() != 0
                || ChatConverter.conversationTypeOf(chat) != ConversationType.SELF_HELP)
              return false;
            ownerPolicy.requireNewEnrolment(chat);
            return true;
          });
    } catch (CustomValidationHttpStatusException refusal) {
      throw refusal;
    } catch (RuntimeException unavailable) {
      throw MatrixGroupParticipationHistory.unavailable();
    }
  }
}
