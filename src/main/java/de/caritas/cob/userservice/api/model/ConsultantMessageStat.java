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
 * One row per message sent by a consultant, used only to compute the "messages sent" statistic on
 * the consultant's own profile.
 *
 * <p>The consultant's identity is never stored in plain text here: {@code consultantHmac} is
 * HMAC-SHA256(secret, consultantId), computed by {@link
 * de.caritas.cob.userservice.api.service.statistics.ConsultantIdentityHasher}. The secret is held
 * only by this service; nobody with direct database access (backup, read replica, another service)
 * can map a row back to a specific consultant without it. {@code agencyId}/{@code tenantId} stay in
 * plain text so agency/tenant-level aggregation remains possible without needing the secret. No
 * message content is stored (KDG compliance).
 */
@Entity
@Builder
@Table(name = "consultant_message_stat")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ConsultantMessageStat {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "consultant_hmac", nullable = false, length = 64)
  private String consultantHmac;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "agency_id")
  private Long agencyId;

  @Column(name = "source_session_id")
  private Long sourceSessionId;

  @Column(name = "sent_date", nullable = false)
  private LocalDateTime sentDate;
}
