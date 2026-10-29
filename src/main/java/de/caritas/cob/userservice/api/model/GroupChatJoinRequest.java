package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A "knock" that grants nothing until it is admitted. Two shapes share the lifecycle:
 *
 * <ul>
 *   <li>{@link Via#INVITE_LINK}: a consultant knocks on a self-help group Series; a Series Owner or
 *       Co-Moderator admits it ({@code seriesId}, {@code consultantId}).
 *   <li>{@link Via#DIRECT_LINK}: a person knocks on an audio or video call in their own 1:1
 *       conversation opened through a counsellor direct link; that counsellor admits it ({@code
 *       sessionId}, {@code userId}, #1475, ADR-029 §2). Never enters the Matrix admission path.
 *   <li>{@link Via#CALL_LINK}: a guest knocks on one running call through its share link ({@code
 *       inviteLinkId}, {@code userId}, #1487); the link's counsellor admits it.
 * </ul>
 */
@Entity
@Table(
    name = "group_chat_join_request",
    indexes = {
      @Index(name = "idx_gcjr_series_status", columnList = "series_id, status"),
      @Index(name = "idx_gcjr_consultant_series", columnList = "consultant_id, series_id"),
      @Index(name = "idx_gcjr_session_status", columnList = "session_id, status")
    })
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class GroupChatJoinRequest {

  public enum Status {
    PENDING,
    ADMITTING,
    ADMITTED,
    DECLINED,
    CANCELLED
  }

  public enum Via {
    INVITE_LINK,
    DIRECT_LINK,
    /** A guest knocks on one running call through its share link (#1487). */
    CALL_LINK
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  /** References {@code chat.id} (the Series), like {@link GroupChatParticipant#getSeriesId()}. */
  @Column(name = "series_id", updatable = false)
  private Long seriesId;

  @Column(name = "consultant_id", updatable = false, length = 36)
  private String consultantId;

  /** {@link Via#DIRECT_LINK}: the 1:1 conversation whose call is knocked on. */
  @Column(name = "session_id", updatable = false)
  private Long sessionId;

  /** {@link Via#DIRECT_LINK}: the person knocking. */
  @Column(name = "user_id", updatable = false, length = 36)
  private String userId;

  /** {@link Via#DIRECT_LINK}: the link used; no foreign key, so deleting the link keeps it. */
  @Column(name = "invite_link_id", updatable = false)
  private Long inviteLinkId;

  /** {@link Via#DIRECT_LINK}: {@code AUDIO} or {@code VIDEO}. */
  @Column(name = "medium", updatable = false, length = 16)
  private String medium;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private Status status;

  @Builder.Default
  @Enumerated(EnumType.STRING)
  @Column(name = "via", nullable = false, length = 32)
  private Via via = Via.INVITE_LINK;

  @Enumerated(EnumType.STRING)
  @Column(name = "admitted_role", length = 16)
  private ParticipantRole admittedRole;

  @Column(name = "requested_at", nullable = false, updatable = false)
  private LocalDateTime requestedAt;

  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  @Column(name = "decided_by", length = 36)
  private String decidedBy;

  /** Durable admission intent. No participant row or group access exists until Matrix joins. */
  @Column(name = "admission_requested_at")
  private LocalDateTime admissionRequestedAt;

  @Builder.Default
  @Column(name = "admission_attempt_count", nullable = false)
  private int admissionAttemptCount = 0;

  @Column(name = "admission_last_attempt_at")
  private LocalDateTime admissionLastAttemptAt;

  public boolean isPending() {
    return status == Status.PENDING;
  }
}
