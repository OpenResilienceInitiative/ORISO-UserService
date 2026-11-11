package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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

/**
 * One line of the statistics settings protocol: which setting changed from which value to which,
 * who changed it and from when it applies. The latest line per setting is its current value; rows
 * are never updated or deleted, so the protocol shows which rule applied when.
 */
@Entity
@Table(name = "statistics_setting_change")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class StatisticsSettingChange {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "setting_key", nullable = false, length = 64)
  private String settingKey;

  /** Null for the initial value recorded when the setting was introduced. */
  @Column(name = "old_value", length = 64)
  private String oldValue;

  @Column(name = "new_value", nullable = false, length = 64)
  private String newValue;

  /** Null when the system recorded the value (introduction default). */
  @Column(name = "changed_by_id", length = 64)
  private String changedById;

  @Column(name = "changed_by_name", length = 255)
  private String changedByName;

  @Column(name = "effective_from", nullable = false, columnDefinition = "datetime")
  private LocalDateTime effectiveFrom;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof StatisticsSettingChange that)) {
      return false;
    }
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
