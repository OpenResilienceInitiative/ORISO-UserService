package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The contact person and optional deputy a counselling centre names for its counsellors
 * (ORISO-Admin#1534). The admin ids are plain references: whether they still name an admin of this
 * centre or its Träger is checked when read.
 */
@Entity
@Table(name = "agency_contact_person")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class AgencyContactPerson {

  @Id
  @Column(name = "agency_id", updatable = false, nullable = false)
  private Long agencyId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Size(max = 36)
  @Column(name = "contact_admin_id", length = 36)
  private String contactAdminId;

  @Size(max = 36)
  @Column(name = "deputy_admin_id", length = 36)
  private String deputyAdminId;

  @CreatedDate
  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @LastModifiedDate
  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;
}
