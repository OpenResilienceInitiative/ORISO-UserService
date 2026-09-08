package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Exactly-once ledger for DPA_SIGNED_NOTICE mails (ORISO-UserService#1005): one notice per tenant
 * and signed DPA version. The unique constraint is the concurrency guarantee — of two parallel
 * signature hints exactly one inserts the row and sends the mail; the loser sees the constraint
 * violation and stays silent.
 */
@Entity
@Table(
    name = "dpa_signed_notice",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_dpa_signed_notice_tenant_version",
            columnNames = {"tenant_id", "dpa_version"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
// recipientEmail is personal data and entity toString reaches logs (same rule as AccountInvite)
@ToString(exclude = {"recipientEmail"})
public class DpaSignedNotice {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  /** The signed DPA version as reported by TenantService (activation timestamp string). */
  @Column(name = "dpa_version", nullable = false, length = 64)
  private String dpaVersion;

  @Column(name = "recipient_email", nullable = false, length = 255)
  private String recipientEmail;

  @Column(name = "signed_at", columnDefinition = "datetime")
  private LocalDateTime signedAt;

  @Column(name = "sent_at", columnDefinition = "datetime")
  private LocalDateTime sentAt;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;
}
