package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.domain.Persistable;

/**
 * Durable time-bounded claim for a scheduled task shared by all service replicas.
 *
 * <p>Implements {@link Persistable} because the id ({@code taskName}) is application-assigned:
 * without it, Spring Data treats a freshly built claim as "not new" and {@code saveAndFlush} runs
 * an {@code em.merge()}. In the replica race window (H2 takes no gap lock on the empty-row {@code
 * FOR UPDATE} lookup) a merge silently turns into an UPDATE of the row a competing replica just
 * committed — both replicas then believe they own the claim. Reporting new instances as new forces
 * a real INSERT, so the loser fails on the primary key and takes the intended claim-conflict path
 * in {@code ScheduledTaskClaimService}.
 */
@Entity
@Table(name = "scheduled_task_claim")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class ScheduledTaskClaim implements Persistable<String> {

  @Id
  @Column(name = "task_name", nullable = false, length = 128)
  private String taskName;

  @Column(name = "claimed_at", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime claimedAt;

  @Column(name = "claimed_until", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime claimedUntil;

  /** True until the entity has been persisted or loaded; makes Spring Data INSERT new claims. */
  @Transient @Builder.Default @ToString.Exclude private boolean isNew = true;

  @Override
  public String getId() {
    return taskName;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }

  @PostLoad
  @PostPersist
  void markNotNew() {
    this.isNew = false;
  }

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
