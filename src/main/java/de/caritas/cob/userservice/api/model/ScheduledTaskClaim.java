package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/** Durable time-bounded claim for a scheduled task shared by all service replicas. */
@Entity
@Table(name = "scheduled_task_claim")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class ScheduledTaskClaim {

  @Id
  @Column(name = "task_name", nullable = false, length = 128)
  private String taskName;

  @Column(name = "claimed_at", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime claimedAt;

  @Column(name = "claimed_until", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime claimedUntil;

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ScheduledTaskClaim)) {
      return false;
    }
    ScheduledTaskClaim that = (ScheduledTaskClaim) other;
    return taskName != null && taskName.equals(that.taskName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskName);
  }
}
