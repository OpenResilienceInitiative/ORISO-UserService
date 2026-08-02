package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Audit trail of the live-handshake lifecycle (ADR-018 §3). Entries are retained for 12 months and
 * deleted automatically.
 */
@Entity
@Table(name = "handshake_audit_event")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandshakeAuditEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Long id;

  @Column(name = "handshake_id", length = 36, nullable = false)
  private String handshakeId;

  @Column(name = "purpose", length = 40, nullable = false)
  private String purpose;

  @Column(name = "event", length = 40, nullable = false)
  private String event;

  @Column(name = "actor_id", length = 36)
  private String actorId;

  @Column(name = "counterpart_id", length = 36)
  private String counterpartId;

  @Column(name = "tenant_id")
  private Long tenantId;

  /** Target agency of the support access — the scope an Agency Admin is allowed to see. */
  @Column(name = "agency_id")
  private Long agencyId;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;
}
