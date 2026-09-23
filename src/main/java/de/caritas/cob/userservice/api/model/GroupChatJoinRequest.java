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
 * A consultant's "knock" on a self-help group Series they reached through an invite link. Until a
 * Series Owner or Co-Moderator admits it, the request grants no access to the group.
 */
@Entity
@Table(
    name = "group_chat_join_request",
    indexes = {
      @Index(name = "idx_gcjr_series_status", columnList = "series_id, status"),
      @Index(name = "idx_gcjr_consultant_series", columnList = "consultant_id, series_id")
    })
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
public class GroupChatJoinRequest {

  public enum Status {
    PENDING,
    ADMITTED,
    DECLINED,
    CANCELLED
  }

  public enum Via {
    INVITE_LINK
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  /** References {@code chat.id} (the Series), like {@link GroupChatParticipant#getSeriesId()}. */
  @Column(name = "series_id", nullable = false, updatable = false)
  private Long seriesId;

  @Column(name = "consultant_id", nullable = false, updatable = false, length = 36)
  private String consultantId;

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

  public boolean isPending() {
    return status == Status.PENDING;
  }
}
