package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import java.util.Objects;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.search.annotations.Field;
import org.hibernate.search.annotations.FieldBridge;
import org.hibernate.search.bridge.builtin.LongBridge;

/** Represents the relation between consultant and agency */
@Entity
@Table(name = "consultant_agency")
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Getter
@Setter
@FilterDef(
    name = "tenantFilter",
    parameters = {@ParamDef(name = "tenantId", type = "long")})
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class ConsultantAgency implements TenantAware {

  @Id
  @SequenceGenerator(
      name = "id_seq",
      allocationSize = 1,
      sequenceName = "sequence_consultant_agency")
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "id_seq")
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "consultant_id", nullable = false)
  private Consultant consultant;

  @Column(name = "agency_id")
  @Field
  @FieldBridge(impl = LongBridge.class)
  private Long agencyId;

  @Column(name = "create_date")
  private LocalDateTime createDate;

  @Column(name = "update_date")
  private LocalDateTime updateDate;

  @Column(name = "delete_date")
  private LocalDateTime deleteDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "status", length = 11)
  @Enumerated(EnumType.STRING)
  @Field
  private ConsultantAgencyStatus status = ConsultantAgencyStatus.IN_PROGRESS;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ConsultantAgency)) {
      return false;
    }
    ConsultantAgency that = (ConsultantAgency) o;
    return id.equals(that.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  public interface ConsultantAgencyBase {

    Long getId();

    Long getAgencyId();

    String getConsultantId();

    LocalDateTime getDeleteDate();
  }
}
