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

  /**
   * FE#2209 round 5: how the call ended. {@link #END_CAUSE_ENDPOINT}: a counsellor ended it through
   * the end endpoint. {@link #END_CAUSE_LIFECYCLE}: the server closed it (invite ran out, never
   * observed, everybody left, closure). Null for calls ended before this was recorded, and for
   * running calls; null counts as an explicit end (fail closed).
   */
  @Column(name = "end_cause", length = 16)
  private String endCause;

  @Column(name = "media_observed_at")
  private Long mediaObservedAt;

  @Column(name = "next_observation_attempt_at")
  private Long nextObservationAttemptAt;

  /**
   * FE#2209 round 8: when the reconcile sweep last passed this running session call through the
   * lifecycle; the sweep takes the least recently swept candidates first. Null: never swept.
   */
  @Column(name = "last_swept_at")
  private Long lastSweptAt;

  public void recordSweep(long timestamp) {
    lastSweptAt = timestamp;
  }

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

  /**
   * FE#2209 round 11 (F1): a dropped sync batch may hold any device's departure, so no membership
   * seen before {@code at} proves presence any more: every device's membership ends at {@code at}
   * at the latest. Only a new membership event makes a device live again (an event already stored
   * is never applied twice). Attendance is kept.
   */
  public void losePresence(long at) {
    for (var key : java.util.List.copyOf(devices.keySet())) {
      var device = devices.get(key);
      if (device.getExpiresAt() > at)
        devices.put(
            key,
            new MatrixCallDevice(
                device.getSenderMatrixId(),
                device.getEventTimestamp(),
                at,
                device.isAttended(),
                device.getEventId()));
    }
  }

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(name = "matrix_call_device", joinColumns = @JoinColumn(name = "binding_id"))
  @MapKeyColumn(name = "state_key", length = 255)
  @Builder.Default
  private java.util.Map<String, MatrixCallDevice> devices = new java.util.HashMap<>();

  public void recordAttendance(long timestamp) {
    if (endedAt == null && (startedAt == null || timestamp < startedAt)) startedAt = timestamp;
  }

  /**
   * Running or still ringing: not ended, and either nobody has been seen yet and the invite has not
   * run out, or a seen device's membership has not expired. Share links, knocks and guest seats all
   * ask this (FE#2209).
   */
  public boolean isLive(long now) {
    if (endedAt != null) return false;
    if (startedAt == null) return inviteExpiresAt > now;
    return devices.values().stream().anyMatch(device -> device.getExpiresAt() > now);
  }

  /**
   * FE#2209 review: whether {@code matrixId} holds a live MatrixRTC membership in this call room,
   * the same source {@link #isLive} reads. A departure or an expired membership is not in the call.
   */
  public boolean isInCall(String matrixId, long now) {
    return endedAt == null
        && matrixId != null
        && devices.values().stream()
            .anyMatch(
                device ->
                    matrixId.equals(device.getSenderMatrixId()) && device.getExpiresAt() > now);
  }

  /**
   * Who may end this call explicitly (FE#2209, Frank 13 Nov; #1662, Frank 17 Nov: group calls
   * follow the same rule): the caller who registered it, or a counsellor who is in the call right
   * now and whose account is active. {@code activeCounsellor} is asked only for somebody in the
   * call.
   */
  public boolean mayBeEndedBy(
      String enderMatrixId, java.util.function.BooleanSupplier activeCounsellor, long now) {
    if (enderMatrixId == null) return false;
    if (enderMatrixId.equals(callerMatrixId)) return true;
    return isInCall(enderMatrixId, now) && activeCounsellor.getAsBoolean();
  }

  /**
   * FE#2209 review: the session's assigned counsellor takes over a binding that somebody else's
   * plaintext invite created for the same call (same source room and call id, so the unique key
   * stays). It becomes a fresh, not yet observed call in the counsellor's call room; nothing of the
   * other call room carries over.
   */
  public void takeOver(
      String caller,
      String mediaRoomId,
      long now,
      long inviteExpiresAt,
      boolean video,
      java.util.Set<String> invitees) {
    this.callerMatrixId = caller;
    this.mediaRoomId = mediaRoomId;
    this.invitedAt = now;
    this.inviteExpiresAt = inviteExpiresAt;
    this.video = video;
    this.invitedMatrixIds.clear();
    this.invitedMatrixIds.addAll(invitees);
    this.devices.clear();
    this.startedAt = null;
    this.endedAt = null;
    this.endCause = null;
    this.mediaObservedAt = null;
    this.nextObservationAttemptAt = null;
  }

  public static final String END_CAUSE_ENDPOINT = "ENDPOINT";
  public static final String END_CAUSE_LIFECYCLE = "LIFECYCLE";

  /** Ends the call without recording why; such an end counts as explicit (fail closed). */
  public void finish(long timestamp) {
    if (endedAt == null) endedAt = Math.max(startedAt == null ? invitedAt : startedAt, timestamp);
  }

  /** A counsellor ended the call through the end endpoint. */
  public void finishExplicitly(long timestamp) {
    finish(timestamp, END_CAUSE_ENDPOINT);
  }

  /** The server closed the call: invite ran out, never observed, everybody left, closure. */
  public void finishByLifecycle(long timestamp) {
    finish(timestamp, END_CAUSE_LIFECYCLE);
  }

  private void finish(long timestamp, String cause) {
    if (endedAt != null) return;
    finish(timestamp);
    endCause = cause;
  }
}
