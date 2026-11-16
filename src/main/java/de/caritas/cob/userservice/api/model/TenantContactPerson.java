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
 * The Träger default contact person and deputy for every centre without its own, plus the general
 * contact e-mail of the Träger (ORISO-Admin#1534).
 */
@Entity
@Table(name = "tenant_contact_person")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@EntityListeners(AuditingEntityListener.class)
public class TenantContactPerson {

  @Id
  @Column(name = "tenant_id", updatable = false, nullable = false)
  private Long tenantId;

  @Size(max = 36)
  @Column(name = "contact_admin_id", length = 36)
  private String contactAdminId;

  @Size(max = 36)
  @Column(name = "deputy_admin_id", length = 36)
  private String deputyAdminId;

  @Size(max = 255)
  @Column(name = "general_contact_email")
  private String generalContactEmail;

  @CreatedDate
  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @LastModifiedDate
  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;
}
