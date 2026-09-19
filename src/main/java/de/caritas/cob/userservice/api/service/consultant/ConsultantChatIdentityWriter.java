package de.caritas.cob.userservice.api.service.consultant;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only MariaDB writes of the chat-identity repair, each in a transaction of its own.
 *
 * <p>This exists so the Synapse call can sit <em>outside</em> any database transaction (#1194).
 * Provisioning from inside one is the defect the repair was written to cure: Matrix succeeds, the
 * commit then fails, and the rollback leaves an orphaned chat account behind that no later attempt
 * can turn into a repaired consultant. It is a separate bean rather than a method on the service
 * because Spring's transaction proxy does not intercept self-invocation, so {@code REQUIRES_NEW} on
 * a sibling method of the same class would silently do nothing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConsultantChatIdentityWriter {

  private final @NonNull ConsultantRepository consultantRepository;

  /**
   * Reads the consultant in its own short transaction.
   *
   * @param consultantId the consultant to read
   * @return the consultant, or empty when no active consultant has that id
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<Consultant> find(String consultantId) {
    return consultantRepository.findByIdAndDeleteDateIsNull(consultantId);
  }

  /**
   * Stores a provisioned chat identity, in a transaction that contains nothing else.
   *
   * <p>Write-side idempotence: the consultant is re-read inside this transaction and an identity
   * that appeared meanwhile is kept, so two repairs racing each other cannot overwrite one another
   * and the second is a no-op rather than a second truth.
   *
   * @param consultantId the consultant to complete
   * @param matrixUserId the provisioned chat identity
   * @return the stored consultant
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Consultant attachChatIdentity(String consultantId, String matrixUserId) {
    var consultant = consultantRepository.findByIdAndDeleteDateIsNull(consultantId).orElse(null);
    if (consultant == null) {
      // The consultant was deleted between provisioning and this write. The chat account is an
      // orphan; say so once, loudly, rather than failing silently.
      log.error(
          "Consultant {} disappeared while its chat identity was being provisioned; the chat"
              + " account provisioned for it is now unreferenced",
          consultantId);
      throw new IllegalStateException(
          "Consultant " + consultantId + " no longer exists; chat identity cannot be attached");
    }
    if (ConsultantChatIdentityService.hasChatIdentity(consultant)) {
      log.info(
          "Consultant {} gained a chat identity while this repair was provisioning; keeping the"
              + " stored one",
          consultantId);
      return consultant;
    }
    consultant.setMatrixUserId(matrixUserId);
    return consultantRepository.save(consultant);
  }
}
