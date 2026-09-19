package de.caritas.cob.userservice.api.service.consultant;

import static com.google.common.collect.Lists.newArrayList;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.consultant.TransactionalStep;
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
import org.springframework.transaction.annotation.Transactional;

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
  @Transactional
  public Consultant provisionMissingChatIdentity(String consultantId) {
    var consultant =
        consultantRepository
            .findByIdAndDeleteDateIsNull(consultantId)
            .orElseThrow(
                () -> new NotFoundException("Consultant with id %s does not exist", consultantId));

    if (hasChatIdentity(consultant)) {
      log.info("Consultant {} already owns a chat identity, nothing to repair", consultant.getId());
      return consultant;
    }

    var plainUsername = usernameTranscoder.decodeUsername(consultant.getUsername());
    String matrixUserId;
    try {
      matrixUserId =
          matrixUserClient.createUserId(
              plainUsername,
              userHelper.getRandomPassword(),
              consultant.getFirstName() + " " + consultant.getLastName());
    } catch (Exception e) {
      throw chatServerFailed(e, consultant);
    }

    if (isBlank(matrixUserId)) {
      // Synapse answering without a user_id is the same hole as Synapse throwing: nothing was
      // provisioned, so the record must stay untouched and the administrator must be told.
      throw chatServerFailed(
          new MatrixCreateUserException(
              String.format("Matrix answered without a user_id for user (%s)", plainUsername)),
          consultant);
    }

    consultant.setMatrixUserId(matrixUserId);
    var repaired = consultantRepository.save(consultant);
    log.info(
        "Repaired the chat identity of consultant {}: Matrix ID {}",
        repaired.getId(),
        repaired.getMatrixUserId());
    return repaired;
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
