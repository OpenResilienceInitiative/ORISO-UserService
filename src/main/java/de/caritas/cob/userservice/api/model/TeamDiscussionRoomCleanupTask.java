package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** Durable retry handle for a confirmed losing team-discussion Matrix room. */
@Entity
@Table(name = "team_discussion_room_cleanup_task")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamDiscussionRoomCleanupTask {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "matrix_room_id", nullable = false, unique = true, length = 255)
  private String matrixRoomId;

  @Column(name = "attempt_count", nullable = false)
  @Builder.Default
  private int attemptCount = 0;

  @Column(name = "last_attempt_at", columnDefinition = "datetime(6)")
  private LocalDateTime lastAttemptAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime createDate;
}
