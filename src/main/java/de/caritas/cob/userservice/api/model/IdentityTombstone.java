package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Minimal identity record retained after hard deletion (Security-05). */
@Entity
@Table(name = "identity_tombstone")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class IdentityTombstone implements TenantAware {

  public enum SubjectType {
    USER,
    CONSULTANT
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "subject_id", nullable = false, length = 64)
  private String subjectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "subject_type", nullable = false, length = 16)
  private SubjectType subjectType;

  @Column(name = "display_label", nullable = false, length = 255)
  private String displayLabel;

  @Column(name = "hard_deleted_at", nullable = false, columnDefinition = "datetime")
  private LocalDateTime hardDeletedAt;

  @Column(name = "source_delete_date", columnDefinition = "datetime")
  private LocalDateTime sourceDeleteDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof IdentityTombstone)) {
      return false;
    }
    IdentityTombstone that = (IdentityTombstone) o;
    return id != null && id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }
}
