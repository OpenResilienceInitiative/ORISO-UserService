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
 * there, who is missing one, and how to complete it afterwards.
 *
 * <p>Why this exists (#1194): {@code CreateConsultantSaga} deliberately keeps creating a consultant
 * when the chat server cannot be reached — the integration and E2E suites run without a Synapse at
 * all, and refusing creation during a chat outage would stop counsellor onboarding. The cost of
 * that carried decision is a record that looks complete and is refused by every counselling room
 * operation. This service is the other half of the bargain: the state is findable and it can be
 * repaired without a database session.
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
   *
   * <p>#1194: the four facades that need one used to say "does not have Matrix credentials", which
   * reads like a misconfigured account. It is not: the account was created while the chat server
   * was unreachable, and there is a repair for it. Naming both turns an unexplained 4xx/5xx into
   * something an administrator can act on.
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
   * <p>Idempotent by construction: a consultant that already owns a chat identity is returned
   * unchanged and the chat server is not called at all, so repeating the call cannot create a
   * second account or overwrite a working one. A failure leaves the record exactly as it was, which
   * is what makes a later retry safe.
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

    try {
      var repaired = consultantChatIdentityWriter.attachChatIdentity(consultantId, matrixUserId);
      log.info("Repaired the chat identity of consultant {}", repaired.getId());
      return repaired;
    } catch (Exception e) {
      // Compensation is not deletion here: the chat account is valid and correct, only unreferenced
      // for the moment. Destroying it would throw away the counsellor's future room membership for
      // a transient database fault. The reconciliation is the retry, which adopts this very
      // account through provisionOrAdopt instead of trying to mint it again - that is what keeps
      // this interleaving recoverable rather than terminal.
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
   * Mints the chat account, or adopts the one a previous attempt left behind.
   *
   * <p>The homeserver refuses to mint the same localpart twice, so without this a single failed
   * write after a successful provisioning would make the consultant permanently unrepairable - the
   * repair tool would manufacture exactly the state it exists to remove.
   */
  private String provisionOrAdopt(Consultant consultant) {
    var localpart = usernameTranscoder.decodeUsername(consultant.getUsername());
    String matrixUserId;
    try {
      matrixUserId =
          matrixUserClient.createUserId(
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
      // Also the answer for an account the homeserver has deactivated: MatrixUserClient refuses to
      // offer one for adoption, because attaching it would show PROVISIONED here and be refused by
      // the homeserver — the state this repair exists to remove, manufactured by the repair.
      throw chatServerFailed(cause, consultant);
    }
    assertNotHeldByAnotherConsultant(existing, consultant);
    log.warn(
        "Adopting the chat account that already exists for consultant {}; an earlier repair"
            + " provisioned it without storing it",
        consultant.getId());
    return existing;
  }

  /**
   * Adoption keys on the localpart, which is not unique over time: this table's uniqueness is by
   * username among non-deleted rows, so a soft-deleted consultant frees their username while still
   * owning the chat account behind it. Whoever takes that username next would otherwise inherit
   * their rooms and their counselling history, and the two rows would share one {@code
   * matrixUserId} — which is what makes {@code findByMatrixUserIdAndDeleteDateIsNull} throw
   * afterwards. Refusing is the only safe answer: an administrator has to decide which account the
   * chat identity belongs to.
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
