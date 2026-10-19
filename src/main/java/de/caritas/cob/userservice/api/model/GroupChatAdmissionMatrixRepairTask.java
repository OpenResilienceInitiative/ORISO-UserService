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

/** A committed Matrix cleanup intent that survives a failed admission transaction. */
@Entity
@Table(
    name = "group_chat_admission_matrix_repair_task",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_gcamrt_request_room",
            columnNames = {"request_id", "room_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatAdmissionMatrixRepairTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "request_id", nullable = false, updatable = false)
  private Long requestId;

  @Column(name = "series_id", nullable = false, updatable = false)
  private Long seriesId;

  @Column(name = "consultant_id", nullable = false, updatable = false, length = 36)
  private String consultantId;

  @Column(name = "room_id", nullable = false, updatable = false, length = 255)
  private String roomId;

  @Column(name = "member_id", nullable = false, updatable = false, length = 255)
  private String memberId;

  @Builder.Default
  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  @Column(name = "last_attempt_at")
  private LocalDateTime lastAttemptAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;
}
