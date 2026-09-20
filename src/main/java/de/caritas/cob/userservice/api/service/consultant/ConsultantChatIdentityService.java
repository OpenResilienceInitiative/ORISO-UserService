package de.caritas.cob.userservice.api.service.consultant;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionInfo;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Everything about the chat (Matrix) identity of an <em>existing</em> consultant: whether it is
 * there, who is missing one, and how to complete it afterwards. Creation deliberately succeeds
 * without one when the chat server is unreachable; this is the other half of that bargain.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantChatIdentityService {

  private static final String REPAIR_TRANSACTION = "repairConsultantChatIdentity";

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantChatIdentityWriter consultantChatIdentityWriter;
  private final @NonNull MatrixUserClient matrixUserClient;
  private final @NonNull UserHelper userHelper;

  private final UsernameTranscoder usernameTranscoder = new UsernameTranscoder();

  /**
   * Whether the given consultant can be used for counselling at all. A blank {@code matrixUserId}
   * counts as absent, because every facade that needs one rejects a blank the same way a null.
   *
   * @param consultant the consultant to check, may be null
   * @return true if the consultant owns a chat identity
   */
  public static boolean hasChatIdentity(Consultant consultant) {
    return consultant != null && !isBlank(consultant.getMatrixUserId());
  }

  /**
   * The message every caller that has to refuse a consultant without a chat identity should use.
   * Names the cause and the repair, so an administrator can act on it.
   *
   * @param who what the consultant is in this operation, e.g. "Consultant" or "Supervisor"
   * @param consultantId the id of the consultant that owns no chat identity
   * @return the message
   */
  public static String missingChatIdentityMessage(String who, String consultantId) {
    return String.format(
        "%s (id %s) has no chat identity: the account was created while the chat server was"
            + " unreachable, so chat provisioning never completed. Repair it with POST"
            + " /useradmin/consultants/%s/chat-identity.",
        who, consultantId, consultantId);
  }

  /**
   * Every active consultant that owns no chat identity.
   *
   * @return the affected consultants, empty when there are none
   */
  public List<Consultant> findConsultantsWithoutChatIdentity() {
    return consultantRepository.findWithoutChatIdentity();
  }

  /**
   * Completes the chat provisioning of an existing consultant.
   *
   * <p>Idempotent: a consultant that already owns a chat identity is returned unchanged and the
   * chat server is not called. A failure leaves the record exactly as it was.
   *
   * @param consultantId the id of the consultant to repair
   * @return the consultant, now owning a chat identity
   * @throws NotFoundException if no active consultant has that id
   * @throws DistributedTransactionException (424) if the chat server could not provision the
   *     account
   */
  public Consultant provisionMissingChatIdentity(String consultantId) {
    var consultant =
        consultantChatIdentityWriter
            .find(consultantId)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s does not exist", consultantId));

    if (hasChatIdentity(consultant)) {
      log.info("Consultant {} already owns a chat identity, nothing to repair", consultant.getId());
      return consultant;
    }

    // Outside every database transaction, deliberately. See the class javadoc.
    var matrixUserId = provisionOrAdopt(consultant);
    // On the id that is about to be stored, whichever path produced it.
    assertNotHeldByAnotherConsultant(matrixUserId, consultant);

    try {
      var repaired = consultantChatIdentityWriter.attachChatIdentity(consultantId, matrixUserId);
      log.info("Repaired the chat identity of consultant {}", repaired.getId());
      return repaired;
    } catch (Exception e) {
      // Not deletion: the chat account is valid, only unreferenced. The retry adopts it through
      // provisionOrAdopt rather than minting it again.
      log.error(
          "Chat identity for consultant {} was provisioned but could not be stored. The chat"
              + " account exists and is unreferenced; repeat the repair and it will be adopted"
              + " rather than provisioned again",
          consultantId,
          e);
      throw new DistributedTransactionException(
          e,
          DistributedTransactionInfo.builder()
              .name(REPAIR_TRANSACTION)
              .completedTransactionalOperations(
                  newArrayList(TransactionalStep.CREATE_ACCOUNT_IN_MATRIX))
              .failedStep(TransactionalStep.SAVE_CONSULTANT_IN_MARIADB)
              .build());
    }
  }

  /**
   * Mints the chat account, or adopts the one a previous attempt left behind. The homeserver
   * refuses to mint the same localpart twice, so without this a failed write would leave the
   * consultant permanently unrepairable.
   *
   * <p>Minting must not reactivate: a localpart is unique only at a point in time, so an account
   * the homeserver still holds for it may belong to a soft-deleted colleague.
   */
  private String provisionOrAdopt(Consultant consultant) {
    var localpart = usernameTranscoder.decodeUsername(consultant.getUsername());
    String matrixUserId;
    try {
      matrixUserId =
          matrixUserClient.createUserIdWithoutReactivation(
              localpart,
              userHelper.getRandomPassword(),
              consultant.getFirstName() + " " + consultant.getLastName());
    } catch (Exception e) {
      matrixUserId = adoptExisting(consultant, e);
    }

    if (isBlank(matrixUserId)) {
      // Synapse answering without a user_id is the same hole as Synapse throwing.
      matrixUserId =
          adoptExisting(
              consultant, new MatrixCreateUserException("Matrix answered without a user_id"));
    }
    return matrixUserId;
  }

  private String adoptExisting(Consultant consultant, Exception cause) {
    var localpart = usernameTranscoder.decodeUsername(consultant.getUsername());
    String existing = null;
    try {
      existing = matrixUserClient.findUserId(localpart);
    } catch (Exception lookupFailure) {
      log.warn(
          "Could not establish whether a chat account already exists for consultant {}",
          consultant.getId(),
          lookupFailure);
    }
    if (isBlank(existing)) {
      // Also covers a deactivated account: MatrixUserClient does not offer one for adoption.
      throw chatServerFailed(cause, consultant);
    }
    log.warn(
        "Adopting the chat account that already exists for consultant {}; an earlier repair"
            + " provisioned it without storing it",
        consultant.getId());
    return existing;
  }

  /**
   * Adoption keys on the localpart, which is not unique over time: a soft-deleted consultant frees
   * their username while still owning the chat account. Refusing is the only safe answer — an
   * administrator has to decide which account the identity belongs to.
   */
  private void assertNotHeldByAnotherConsultant(String matrixUserId, Consultant consultant) {
    var heldByAnother =
        consultantRepository.findByMatrixUserId(matrixUserId).stream()
            .anyMatch(other -> !consultant.getId().equals(other.getId()));
    if (!heldByAnother) {
      return;
    }
    log.error(
        "Refusing to repair the chat identity of consultant {}: the chat account behind this"
            + " username is already held by another consultant. Adopting it would hand this"
            + " consultant that colleague's rooms and history. Resolve the collision before"
            + " repeating the repair",
        consultant.getId());
    throw new ConflictException(
        String.format(
            "The chat account for consultant %s is already held by another consultant; it cannot"
                + " be adopted",
            consultant.getId()));
  }

  private DistributedTransactionException chatServerFailed(Exception cause, Consultant consultant) {
    log.error(
        "Could not provision the missing chat identity of consultant {}; the record is unchanged"
            + " and the repair can be repeated",
        consultant.getId(),
        cause);
    return new DistributedTransactionException(
        cause,
        DistributedTransactionInfo.builder()
            .name(REPAIR_TRANSACTION)
            .completedTransactionalOperations(newArrayList())
            .failedStep(TransactionalStep.CREATE_ACCOUNT_IN_MATRIX)
            .build());
  }
}
