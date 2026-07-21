package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Global per-user Do-Not-Disturb (decided 2026-07-18). While {@code dndUntil} is in the future,
 * announcements (toast/sound/push) and notification emails are suppressed; the persisted activity
 * feed still fills. Auto-reverts when the timestamp passes — no cleanup job.
 */
@Entity
@Table(name = "user_do_not_disturb")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class UserDoNotDisturb {

  @Id
  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Column(name = "dnd_until")
  private LocalDateTime dndUntil;

  @Column(name = "update_date", nullable = false)
  private LocalDateTime updateDate;
}
