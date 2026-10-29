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
 * Placeholder left in the counsellor's conversation list after an advice seeker's session was
 * deleted (ORISO-Frontend#1159).
 *
 * <p>Deliberately carries no personal data: no asker id, name, room, topic or message content —
 * only which counsellor and counselling centre the case belonged to and when it disappeared, so the
 * counsellor can tell an intentional deletion from a technical problem. Rows age out after the
 * retention period ({@code deleted.asker.session.retention.days}).
 */
@Entity
@Table(name = "deleted_asker_session")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeletedAskerSession {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "consultant_id", nullable = false, updatable = false, length = 36)
  private String consultantId;

  /** The id the deleted session had; only used to keep one placeholder per case. */
  @Column(name = "former_session_id", nullable = false, updatable = false)
  private Long formerSessionId;

  @Column(name = "agency_id", updatable = false)
  private Long agencyId;

  @Column(name = "tenant_id", updatable = false)
  private Long tenantId;

  @Column(name = "deleted_at", nullable = false, updatable = false)
  private LocalDateTime deletedAt;
}
