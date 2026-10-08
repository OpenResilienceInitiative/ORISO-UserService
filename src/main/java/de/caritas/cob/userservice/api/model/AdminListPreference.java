package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * An admin's last chosen sort for one user-list tab (#1263). Keyed by the Keycloak user id, because
 * platform, Träger and Beratungsstellen admins need not own an admin or consultant row.
 */
@Entity
@Table(
    name = "admin_list_preference",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_admin_list_preference_user_tab",
            columnNames = {"user_id", "list_tab"}))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class AdminListPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Column(name = "list_tab", nullable = false, length = 32)
  private String tab;

  @Column(name = "sort_field", nullable = false, length = 32)
  private String sortField;

  @Column(name = "sort_order", nullable = false, length = 4)
  private String sortOrder;

  @Column(name = "update_date", nullable = false)
  private LocalDateTime updateDate;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AdminListPreference that)) {
      return false;
    }
    return Objects.equals(id, that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
