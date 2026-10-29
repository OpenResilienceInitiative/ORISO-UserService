package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import lombok.*;

/** Immutable identity of a dedicated media room, independent of listener process lifetime. */
@Entity
@Table(
    name = "matrix_call_binding",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uk_call_binding_source_call",
          columnNames = {"source_room_id", "call_id"}),
      @UniqueConstraint(name = "uk_call_binding_media_room", columnNames = "media_room_id")
    })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCallBinding {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_room_id", nullable = false, length = 255)
  private String sourceRoomId;

  @Column(name = "call_id", nullable = false, length = 191)
  private String callId;

  @Column(name = "media_room_id", nullable = false, length = 255)
  private String mediaRoomId;

  @Column(name = "caller_matrix_id", nullable = false, length = 255)
  private String callerMatrixId;

  @Column(name = "session_id")
  private Long sessionId;

  @Column(name = "chat_id")
  private Long chatId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "invited_at", nullable = false)
  private long invitedAt;

  @Column(name = "invite_expires_at", nullable = false)
  private long inviteExpiresAt;

  @Column(name = "video", nullable = false)
  private boolean video;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "matrix_call_invitee", joinColumns = @JoinColumn(name = "binding_id"))
  @Column(name = "matrix_user_id", nullable = false, length = 255)
  @Builder.Default
  private java.util.Set<String> invitedMatrixIds = new java.util.HashSet<>();

  @Column(name = "started_at")
  private Long startedAt;

  @Column(name = "ended_at")
  private Long endedAt;

  @Column(name = "media_observed_at")
  private Long mediaObservedAt;

  @Column(name = "next_observation_attempt_at")
  private Long nextObservationAttemptAt;

  public void deferObservationAttempt(long timestamp) {
    nextObservationAttemptAt = timestamp;
  }

  public void recordMediaObservation(long timestamp) {
    mediaObservedAt = timestamp;
  }

  public void loseMediaObservation() {
    mediaObservedAt = null;
    nextObservationAttemptAt = null;
  }

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "matrix_call_device", joinColumns = @JoinColumn(name = "binding_id"))
  @MapKeyColumn(name = "state_key", length = 255)
  @Builder.Default
  private java.util.Map<String, MatrixCallDevice> devices = new java.util.HashMap<>();

  public void recordAttendance(long timestamp) {
    if (endedAt == null && (startedAt == null || timestamp < startedAt)) startedAt = timestamp;
  }

  public void finish(long timestamp) {
    if (endedAt == null) endedAt = Math.max(startedAt == null ? invitedAt : startedAt, timestamp);
  }
}
