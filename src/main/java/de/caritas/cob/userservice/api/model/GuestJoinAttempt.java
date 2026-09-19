package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Durable request binding. Retain terminal rows so an old capability cannot create a new guest. */
@Entity
@Table(name = "guest_join_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GuestJoinAttempt {

  public static final int MAX_CANDIDATES = 3;

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "key_hash", nullable = false, unique = true, updatable = false, length = 64)
  private String keyHash;

  @Column(name = "invite_link_id", nullable = false, updatable = false)
  private Long inviteLinkId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "topic_id", nullable = false, updatable = false)
  private Long topicId;

  @Column(name = "consulting_type_id", nullable = false, updatable = false)
  private Integer consultingTypeId;

  @Column(name = "identity_user_id", length = 36)
  private String identityUserId;

  @Column(name = "matrix_user_id", length = 255)
  private String matrixUserId;

  @Column(name = "original_username", nullable = false, updatable = false, length = 30)
  private String originalUsername;

  @Column(name = "original_avatar_key", nullable = false, updatable = false, length = 128)
  private String originalAvatarKey;

  /**
   * The name actually being provisioned. It starts as the requested one and only differs after a
   * confirmed collision; the original stays bound so a replay of the guest's own request still
   * matches.
   */
  @Column(name = "actual_username", length = 30)
  private String actualUsername;

  /** How many actual candidates this attempt has used, the requested one included. */
  @Column(name = "candidate_ordinal")
  private Integer candidateOrdinal;

  @Column(
      name = "language_formal",
      nullable = false,
      updatable = false,
      columnDefinition = "tinyint")
  private boolean languageFormal;

  @Enumerated(EnumType.STRING)
  @Column(name = "phase", nullable = false, length = 32)
  private Phase phase;

  @Column(
      name = "created_at",
      nullable = false,
      updatable = false,
      columnDefinition = "datetime(6)")
  private LocalDateTime createdAt;

  @Column(
      name = "expires_at",
      nullable = false,
      updatable = false,
      columnDefinition = "datetime(6)")
  private LocalDateTime expiresAt;

  @Column(name = "session_id", unique = true)
  private Long sessionId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  public static GuestJoinAttempt prepare(
      String keyHash,
      GuestJoinTarget target,
      String username,
      String avatarKey,
      boolean languageFormal,
      LocalDateTime now,
      LocalDateTime expiresAt) {
    var attempt = new GuestJoinAttempt();
    attempt.keyHash = keyHash;
    attempt.inviteLinkId = target.inviteLinkId();
    attempt.tenantId = target.tenantId();
    attempt.topicId = target.topicId();
    attempt.consultingTypeId = target.consultingTypeId();
    attempt.originalUsername = username;
    attempt.originalAvatarKey = avatarKey;
    attempt.languageFormal = languageFormal;
    attempt.phase = Phase.PREPARED;
    attempt.createdAt = now;
    attempt.expiresAt = expiresAt;
    return attempt;
  }

  public void beginIdentity() {
    requirePhase(Phase.PREPARED);
    phase = Phase.IDENTITY_PENDING;
  }

  public void identityReady(String id) {
    if (phase != Phase.IDENTITY_PENDING && phase != Phase.IDENTITY_RECONCILING)
      throw new IllegalStateException("Invalid guest Join phase transition");
    if (id == null || id.isBlank() || id.length() > 36)
      throw new IllegalArgumentException("Invalid identity ID");
    identityUserId = id;
    phase = Phase.IDENTITY_READY;
  }

  public void beginMatrix() {
    requirePhase(Phase.IDENTITY_READY);
    phase = Phase.MATRIX_PENDING;
  }

  public void matrixReady(String id) {
    if (phase != Phase.MATRIX_PENDING && phase != Phase.MATRIX_RECONCILING)
      throw new IllegalStateException("Invalid guest Join phase transition");
    if (id == null || id.isBlank() || id.length() > 255)
      throw new IllegalArgumentException("Invalid chat identity ID");
    matrixUserId = id;
    phase = Phase.MATRIX_READY;
  }

  public void complete(Long sessionId) {
    requirePhase(Phase.MATRIX_READY);
    if (sessionId == null || sessionId < 0)
      throw new IllegalArgumentException("Invalid session ID");
    this.sessionId = sessionId;
    phase = Phase.COMPLETE;
  }

  public void reconcileIdentity() {
    requirePhase(Phase.IDENTITY_PENDING);
    phase = Phase.IDENTITY_RECONCILING;
  }

  public void identityCollision() {
    requirePhase(Phase.IDENTITY_PENDING);
    phase = Phase.IDENTITY_COLLISION;
  }

  public void reconcileMatrix() {
    requirePhase(Phase.MATRIX_PENDING);
    phase = Phase.MATRIX_RECONCILING;
  }

  public void matrixCollision() {
    requirePhase(Phase.MATRIX_PENDING);
    phase = Phase.MATRIX_COLLISION;
  }

  /** The name to provision: the replacement once there is one, the requested name otherwise. */
  public String actualUsername() {
    return actualUsername == null || actualUsername.isBlank() ? originalUsername : actualUsername;
  }

  public int candidateOrdinal() {
    return candidateOrdinal == null ? 1 : candidateOrdinal;
  }

  /** At most three actual candidates, the requested one included. */
  public boolean mayTryAnotherCandidate() {
    return (phase == Phase.IDENTITY_COLLISION || phase == Phase.MATRIX_COLLISION)
        && candidateOrdinal() < MAX_CANDIDATES;
  }

  /**
   * Binds the next actual candidate and reopens the attempt for it. Only a committed collision may
   * lead here: an uncertain outcome must never cost the guest their name.
   */
  public void advanceCandidate(String nextUsername) {
    if (!mayTryAnotherCandidate()) {
      throw new IllegalStateException("Guest Join attempt may not try another candidate");
    }
    this.actualUsername = nextUsername;
    this.candidateOrdinal = candidateOrdinal() + 1;
    this.identityUserId = null;
    this.matrixUserId = null;
    this.phase = Phase.PREPARED;
  }

  public String ownershipMarker() {
    if (id == null) throw new IllegalStateException("Guest attempt must be committed first");
    return "guest-join-" + id + "-candidate-1";
  }

  private void requirePhase(Phase expected) {
    if (phase != expected) throw new IllegalStateException("Invalid guest Join phase transition");
  }

  public enum Phase {
    PREPARED,
    IDENTITY_PENDING,
    IDENTITY_RECONCILING,
    IDENTITY_COLLISION,
    IDENTITY_READY,
    MATRIX_PENDING,
    MATRIX_RECONCILING,
    MATRIX_COLLISION,
    MATRIX_READY,
    COMPLETE,
    TERMINAL
  }
}
