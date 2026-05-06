package de.caritas.cob.userservice.api.model;

import java.time.LocalDateTime;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "agency_invite_link")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class AgencyInviteLink {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "token", nullable = false, unique = true, length = 64)
  private String token;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "agency_id", nullable = false)
  private Long agencyId;

  @Column(name = "consulting_type_id")
  private Integer consultingTypeId;

  @Column(name = "created_by_user_id", nullable = false, length = 36)
  private String createdByUserId;

  @Column(name = "created_by_username")
  private String createdByUsername;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Column(name = "used_at")
  private LocalDateTime usedAt;

  @Column(name = "used_by_session_id")
  private Long usedBySessionId;

  @Column(name = "status", nullable = false, length = 20)
  private String status;
}
