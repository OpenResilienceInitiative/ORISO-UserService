package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Durable, metadata-only projection of one call inside a Matrix conversation room. */
@Entity
@Table(
    name = "call_lifecycle_projection",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_call_lifecycle_room_call",
            columnNames = {"matrix_room_id", "call_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallLifecycleProjection {

  public enum Status {
    INVITED,
    STARTED,
    ENDED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "matrix_room_id", nullable = false, length = 255)
  private String matrixRoomId;

  @Column(name = "call_room_id", length = 255)
  private String callRoomId;

  @Column(name = "call_id", nullable = false, length = 191)
  private String callId;

  @Column(name = "call_type", nullable = false, length = 16)
  private String callType;

  @Column(name = "status", nullable = false, length = 16)
  private String status;

  @Column(name = "actor_user_id", length = 64)
  private String actorUserId;

  @Column(name = "source_session_id")
  private Long sourceSessionId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "invited_at", columnDefinition = "datetime(3)")
  private LocalDateTime invitedAt;

  @Column(name = "started_at", columnDefinition = "datetime(3)")
  private LocalDateTime startedAt;

  @Column(name = "ended_at", columnDefinition = "datetime(3)")
  private LocalDateTime endedAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(3)")
  private LocalDateTime createDate;

  @Column(name = "update_date", nullable = false, columnDefinition = "datetime(3)")
  private LocalDateTime updateDate;
}
