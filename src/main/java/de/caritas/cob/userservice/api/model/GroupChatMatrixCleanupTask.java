package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Independent cleanup intent; scalar references deliberately have no database foreign keys. */
@Entity
@Table(name = "group_chat_matrix_cleanup_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatMatrixCleanupTask {
  public enum Action {
    PURGE_ROOM,
    REMOVE_MEMBER
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Action action;

  @Column(name = "owner_id", nullable = false, length = 36, updatable = false)
  private String ownerId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "series_id", nullable = false, updatable = false)
  private Long seriesId;

  @Column(name = "consultant_id", length = 36, updatable = false)
  private String consultantId;

  @Column(name = "room_id", nullable = false, length = 255, updatable = false)
  private String roomId;

  @Column(name = "member_id", length = 255, updatable = false)
  private String memberId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "last_attempt_at")
  private LocalDateTime lastAttemptAt;

  @Builder.Default
  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;
}
