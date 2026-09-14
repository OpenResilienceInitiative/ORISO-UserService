package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairAction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Durable retry record for a Matrix side effect that could not be compensated immediately. */
@Entity
@Table(
    name = "case_handover_matrix_repair_task",
    uniqueConstraints =
        @UniqueConstraint(
            name = "idx_case_handover_matrix_repair_action_room_member",
            columnNames = {"action", "room_id", "member_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseHandoverMatrixRepairTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 16)
  private CaseHandoverMatrixRepairAction action;

  @Column(name = "room_id", nullable = false, length = 255)
  private String roomId;

  @Column(name = "member_id", nullable = false, length = 255)
  private String memberId;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "requester_consultant_id", nullable = false, length = 36)
  private String requesterConsultantId;

  /** Matrix user that can remove the member. JOIN actions authenticate as the member itself. */
  @Column(name = "operator_id", length = 255)
  private String operatorId;

  @Column(name = "attempt_count", nullable = false)
  @Builder.Default
  private int attemptCount = 0;

  @Column(name = "last_attempt_at", columnDefinition = "datetime(6)")
  private LocalDateTime lastAttemptAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime createDate;
}
