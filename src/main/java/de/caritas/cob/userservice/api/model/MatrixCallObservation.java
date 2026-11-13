package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Observation-only registration of an encrypted call (#2166/#1624) in an internal group or, since
 * #2237, a 1:1 session. Exactly one of {@code chatId} or {@code sessionId} is set. It is NOT a call
 * binding: it never admits a guest, never backs a share link or knock and never grants access.
 */
@Entity
@Table(name = "matrix_call_observation")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCallObservation {
  public static final String ORIGIN_CREATOR_REGISTRATION = "CREATOR_REGISTRATION";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "origin", nullable = false, length = 40)
  private String origin;

  @Column(name = "source_room_id", nullable = false, length = 255)
  private String sourceRoomId;

  @Column(name = "call_id", nullable = false, length = 191)
  private String callId;

  @Column(name = "media_room_id", nullable = false, length = 255)
  private String mediaRoomId;

  @Column(name = "creator_matrix_id", nullable = false, length = 255)
  private String creatorMatrixId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  /** Internal group source; null for a session call. */
  @Column(name = "chat_id")
  private Long chatId;

  /** Session source (#2237); null for an internal-group call. */
  @Column(name = "session_id")
  private Long sessionId;

  @Column(name = "video", nullable = false)
  private boolean video;

  /** When UserService learned of the call. Never the invitation time. */
  @Column(name = "registered_at", nullable = false)
  private long registeredAt;

  /** Verified invitation time; null while unproven. */
  @Column(name = "invited_at")
  private Long invitedAt;

  @Column(name = "last_seen_at", nullable = false)
  private long lastSeenAt;

  @Column(name = "expires_at", nullable = false)
  private long expiresAt;

  @Column(name = "ended_at")
  private Long endedAt;

  public void renew(long now, long expiry) {
    if (endedAt == null && now < expiresAt) {
      lastSeenAt = now;
      expiresAt = expiry;
    }
  }

  public void finish(long now) {
    if (endedAt == null) endedAt = Math.min(now, expiresAt);
  }

  public boolean isLive(long now) {
    return endedAt == null && now < expiresAt;
  }
}
