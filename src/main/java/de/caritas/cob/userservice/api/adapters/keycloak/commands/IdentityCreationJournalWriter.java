package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import de.caritas.cob.userservice.api.port.out.IdentityCreationAttemptRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Separate transactions keep captured receipt and failed external finalization available for retry.
 */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class IdentityCreationJournalWriter {
  private final IdentityCreationAttemptRepository repository;
  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
  private final Clock clock;
  private final long leaseSeconds;

  @Autowired
  public IdentityCreationJournalWriter(
      IdentityCreationAttemptRepository repository,
      @Value("${oriso.commands.creation-execution-lease-seconds:300}") long leaseSeconds) {
    this(repository, Clock.systemUTC(), leaseSeconds);
  }

  public IdentityCreationJournalWriter(IdentityCreationAttemptRepository repository) {
    this(repository, Clock.systemUTC(), 300);
  }

  IdentityCreationJournalWriter(
      IdentityCreationAttemptRepository repository, Clock clock, long leaseSeconds) {
    if (leaseSeconds < 60 || leaseSeconds > 3600)
      throw new IllegalArgumentException(
          "Creation execution lease must be between 60 and 3600 seconds");
    this.repository = repository;
    this.clock = clock;
    this.leaseSeconds = leaseSeconds;
  }

  public record CreationExecution(UUID attemptId, UUID claim) {}

  public CreationExecution begin(UUID id, IdentityCreationOrigin origin, String username) {
    String key = requestKey(origin, username);
    var pending =
        repository.findFirstByRequestKeyAndStatusInOrderByUpdateDateDesc(
            key,
            CreationStatus.codes(
                CreationStatus.CREATION_REQUESTED,
                CreationStatus.OPEN,
                CreationStatus.LOCAL_RECONCILIATION_REQUIRED,
                CreationStatus.RECOVERY_REQUESTED,
                CreationStatus.LOCAL_CLEANUP_REQUESTED,
                CreationStatus.COMMIT_REQUESTED,
                CreationStatus.COMPENSATION_REQUESTED,
                CreationStatus.COMMITTED));
    if (pending.isPresent()) {
      verifyOrigin(pending.get(), origin);
      return claimRequested(pending.get());
    }
    var found = repository.findByIdForUpdate(id.toString());
    if (found.isPresent()) {
      verifyOrigin(found.get(), origin);
      return claimRequested(found.get());
    }
    var row = new IdentityCreationAttempt();
    row.setRequestKey(key);
    row.setId(id.toString());
    row.setOriginKind(origin.originKind());
    row.setRegistrationKind(origin.registrationKind());
    row.setTenantId(origin.tenantId());
    row.setInitialRoles(String.join(",", origin.roles()));
    row.setProvenance(origin.provenance());
    row.setAuthorizedAgencyIds(
        origin.agencyIds().stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(",")));
    CreationStatus.CREATION_REQUESTED.persist(row);
    return claimRequested(row);
  }

  public void created(
      KeycloakTaskCommands.CreationResult receipt,
      IdentityCreationOrigin origin,
      CreationExecution execution) {
    var row = require(receipt.attemptId());
    verifyOrigin(row, origin);
    verifyExecution(row, execution);
    if (receipt.accountId() == null
        || receipt.accountId().isBlank()
        || receipt.creationProof() == null
        || receipt.creationProof().isBlank())
      throw new IllegalStateException("Identity provider returned no creation receipt");
    if (row.getAccountId() != null && !row.getAccountId().equals(receipt.accountId()))
      throw denied();
    if (!CreationStatus.CREATION_REQUESTED.matches(row)) throw denied();
    row.setAccountId(receipt.accountId());
    row.setCreationProof(receipt.creationProof());
    CreationStatus.OPEN.persist(row);
    row.setExecutionExpiresAt(now().plusSeconds(leaseSeconds));
    save(row);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public void request(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin, String state) {
    var row = require(receipt.attemptId());
    verifyReceipt(row, receipt, origin);
    var requested = CreationStatus.fromCode(state);
    if (!requested.acceptsRequestFrom(row)) throw denied();
    requested.persist(row);
    save(row);
  }

  /**
   * Written in the SAME transaction as the last local saga step; invisible to retries until commit.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void prepareCommitInSaga(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin) {
    request(receipt, origin, "COMMIT_REQUESTED");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void requestAfterSaga(
      KeycloakTaskCommands.CreationResult receipt, IdentityCreationOrigin origin, String state) {
    request(receipt, origin, state);
  }

  public void finish(KeycloakTaskCommands.CreationResult receipt, String terminal) {
    var row = require(receipt.attemptId());
    var terminalState = CreationStatus.fromCode(terminal);
    if (!terminalState.requiredRequest().matches(row)) throw denied();
    terminalState.persist(row);
    if (terminalState == CreationStatus.COMPENSATED) row.setRequestKey(null);
    save(row);
  }

  @Transactional(propagation = Propagation.REQUIRED)
  public IdentityCreationAttempt ownedAttempt(String accountId) {
    var row =
        repository.findByAccountId(accountId).orElseThrow(IdentityCreationJournalWriter::denied);
    row = require(UUID.fromString(row.getId()));
    IdentityCreationOrigin.pendingFinalization(row);
    return row;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void captureAnonymousBootstrap(UUID attemptId, Long sessionId) {
    var row = require(attemptId);
    if (!CreationStatus.OPEN.matches(row)
        || !"ANONYMOUS".equals(row.getRegistrationKind())
        || sessionId == null) throw denied();
    row.setBootstrapSessionId(sessionId);
    row.setBootstrapExpiresAt(now().plusSeconds(leaseSeconds));
    row.setBootstrapFailedAt(null);
    save(row);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordAnonymousBootstrapFailure(UUID attemptId, Long sessionId) {
    var row = require(attemptId);
    if (!CreationStatus.in(row, CreationStatus.COMMIT_REQUESTED, CreationStatus.COMMITTED)
        || !"ANONYMOUS".equals(row.getRegistrationKind())
        || !Objects.equals(row.getBootstrapSessionId(), sessionId)) throw denied();
    row.setBootstrapFailedAt(now());
    save(row);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void finishAnonymousBootstrap(UUID attemptId, Long sessionId) {
    var row = require(attemptId);
    if (!CreationStatus.COMMITTED.matches(row)
        || !"ANONYMOUS".equals(row.getRegistrationKind())
        || !Objects.equals(row.getBootstrapSessionId(), sessionId)) throw denied();
    row.setBootstrapSessionId(null);
    row.setBootstrapExpiresAt(null);
    save(row);
  }

  public boolean anonymousBootstrapExpired(IdentityCreationAttempt row) {
    return row.getBootstrapExpiresAt() != null && !row.getBootstrapExpiresAt().isAfter(now());
  }

  public List<IdentityCreationAttempt> pendingAnonymousBootstraps() {
    return repository.findByBootstrapSessionIdIsNotNullAndStatus(
        CreationStatus.COMMITTED.name(), org.springframework.data.domain.PageRequest.of(0, 100));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public IdentityCreationAttempt attemptInSaga(UUID id) {
    return require(id);
  }

  public IdentityCreationAttempt attempt(UUID id) {
    return require(id);
  }

  public List<IdentityCreationAttempt> pending() {
    return repository.findByStatusInOrderByUpdateDateAsc(
        CreationStatus.codes(
            CreationStatus.COMMIT_REQUESTED, CreationStatus.COMPENSATION_REQUESTED),
        org.springframework.data.domain.PageRequest.of(0, 100));
  }

  /** Only the execution that saw an uncertain create result can release its lease for a retry. */
  public void createResultUncertain(CreationExecution execution, IdentityCreationOrigin origin) {
    var row = require(execution.attemptId());
    verifyOrigin(row, origin);
    verifyExecution(row, execution);
    if (!CreationStatus.CREATION_REQUESTED.matches(row)) throw denied();
    row.setExecutionExpiresAt(null);
    save(row);
  }

  /** Claim crashed executions under the same row lock used by every local creation transaction. */
  public List<IdentityCreationAttempt> reconciliationRequired() {
    for (var candidate :
        repository.findByStatusInOrderByUpdateDateAsc(
            CreationStatus.codes(
                CreationStatus.CREATION_REQUESTED,
                CreationStatus.OPEN,
                CreationStatus.LOCAL_RECONCILIATION_REQUIRED),
            org.springframework.data.domain.PageRequest.of(0, 100))) {
      var row = require(UUID.fromString(candidate.getId()));
      if (CreationStatus.in(
              row,
              CreationStatus.CREATION_REQUESTED,
              CreationStatus.OPEN,
              CreationStatus.LOCAL_RECONCILIATION_REQUIRED)
          && (row.getExecutionExpiresAt() == null || !row.getExecutionExpiresAt().isAfter(now()))) {
        row.setExecutionClaim(UUID.randomUUID().toString());
        CreationStatus.RECOVERY_REQUESTED.persist(row);
        save(row);
      }
    }
    return repository.findByStatusInOrderByUpdateDateAsc(
        CreationStatus.codes(
            CreationStatus.RECOVERY_REQUESTED, CreationStatus.LOCAL_CLEANUP_REQUESTED),
        org.springframework.data.domain.PageRequest.of(0, 100));
  }

  /** Hold this lock through ALL local writes, not just the final save. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void acquireLocalSaga(KeycloakTaskCommands.CreationResult receipt) {
    var row = require(receipt.attemptId());
    if (receipt.executionClaim() == null
        || !Objects.equals(row.getExecutionClaim(), receipt.executionClaim().toString())
        || !CreationStatus.OPEN.matches(row)
        || row.getExecutionExpiresAt() == null
        || !row.getExecutionExpiresAt().isAfter(now())) throw denied();
    verifyReceipt(row, receipt, IdentityCreationOrigin.pendingFinalization(row));
  }

  public void recovered(
      IdentityCreationAttempt candidate, KeycloakTaskCommands.RecoveryResult result) {
    var row = require(UUID.fromString(candidate.getId()));
    if (!CreationStatus.RECOVERY_REQUESTED.matches(row)
        || !Objects.equals(row.getExecutionClaim(), candidate.getExecutionClaim())
        || !UUID.fromString(row.getId()).equals(result.attemptId())) throw denied();
    if ("ABANDONED".equals(result.status())) {
      if (row.getAccountId() != null
          || result.accountId() != null
          || result.creationProof() != null) throw denied();
      CreationStatus.COMPENSATED.persist(row);
      row.setRequestKey(null);
    } else if (Set.of("RECOVERY_CLAIMED", "COMPENSATED").contains(result.status())) {
      if (result.accountId() == null
          || result.creationProof() == null
          || result.creationProof().isBlank()
          || (row.getAccountId() != null && !row.getAccountId().equals(result.accountId()))
          || (row.getCreationProof() != null
              && !row.getCreationProof().equals(result.creationProof()))) throw denied();
      row.setAccountId(result.accountId());
      row.setCreationProof(result.creationProof());
      CreationStatus.LOCAL_CLEANUP_REQUESTED.persist(row);
    } else throw denied(); // COMMITTED cannot be inferred into either local success or deletion.
    save(row);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public IdentityCreationAttempt cleanupAttempt(UUID attemptId) {
    var row = require(attemptId);
    if (!CreationStatus.in(
            row, CreationStatus.LOCAL_CLEANUP_REQUESTED, CreationStatus.COMPENSATION_REQUESTED)
        || row.getAccountId() == null
        || row.getCreationProof() == null) throw denied();
    return row;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void cleanedLocally(UUID attemptId) {
    var row = cleanupAttempt(attemptId);
    CreationStatus.COMPENSATION_REQUESTED.persist(row);
    save(row);
  }

  private CreationExecution claimRequested(IdentityCreationAttempt row) {
    if (!CreationStatus.CREATION_REQUESTED.matches(row)
        || (row.getExecutionExpiresAt() != null && row.getExecutionExpiresAt().isAfter(now())))
      throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
          "Account creation is already in progress or awaiting local reconciliation");
    var claim = UUID.randomUUID();
    row.setExecutionClaim(claim.toString());
    row.setExecutionExpiresAt(now().plusSeconds(leaseSeconds));
    save(row);
    return new CreationExecution(UUID.fromString(row.getId()), claim);
  }

  private static void verifyExecution(IdentityCreationAttempt row, CreationExecution execution) {
    if (!row.getId().equals(execution.attemptId().toString())
        || !Objects.equals(row.getExecutionClaim(), execution.claim().toString())) throw denied();
  }

  private IdentityCreationAttempt require(UUID id) {
    var row =
        repository
            .findByIdForUpdate(id.toString())
            .orElseThrow(IdentityCreationJournalWriter::denied);
    // A candidate query may have cached OPEN before waiting for another transaction's lock.
    // Acquiring the lock alone does not refresh the persistence context's old entity state.
    if (entityManager != null)
      entityManager.refresh(row, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    return row;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(clock);
  }

  private void save(IdentityCreationAttempt row) {
    row.setUpdateDate(now());
    repository.saveAndFlush(row);
  }

  private static void verifyOrigin(IdentityCreationAttempt row, IdentityCreationOrigin origin) {
    if (!Objects.equals(row.getTenantId(), origin.tenantId())
        || !row.getOriginKind().equals(origin.originKind())
        || !row.getRegistrationKind().equals(origin.registrationKind())
        || !row.getInitialRoles().equals(String.join(",", origin.roles()))
        || !row.getProvenance().equals(origin.provenance())) throw denied();
  }

  private static void verifyReceipt(
      IdentityCreationAttempt row,
      KeycloakTaskCommands.CreationResult receipt,
      IdentityCreationOrigin origin) {
    verifyOrigin(row, origin);
    if (!Objects.equals(row.getAccountId(), receipt.accountId())
        || row.getCreationProof() == null
        || receipt.creationProof() == null
        || !MessageDigest.isEqual(
            row.getCreationProof().getBytes(StandardCharsets.UTF_8),
            receipt.creationProof().getBytes(StandardCharsets.UTF_8))) throw denied();
  }

  private static String requestKey(IdentityCreationOrigin origin, String username) {
    try {
      String source =
          origin.originKind()
              + "\n"
              + origin.registrationKind()
              + "\n"
              + origin.tenantId()
              + "\n"
              + origin.provenance()
              + "\n"
              + username.toLowerCase(java.util.Locale.ROOT);
      return java.util.HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("Required digest unavailable");
    }
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException(
        "Creation finalization is outside its recorded authorized attempt");
  }
}
