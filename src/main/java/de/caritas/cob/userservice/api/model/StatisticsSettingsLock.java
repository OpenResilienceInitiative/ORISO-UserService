package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The one row a save of the statistics settings locks, so two saves of the same revision cannot
 * both pass the revision check (UserService#1562). It holds no data. The protocol table cannot be
 * locked instead: it is append-only and a save may add no line at all.
 */
@Entity
@Table(name = "statistics_settings_lock")
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class StatisticsSettingsLock {

  public static final long ID = 1L;

  @Id
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;
}
