package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One recipient of a confirmed planned notice, and the record of its mail. No address. */
@Entity
@Table(
    name = "service_notice_recipient",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_snr_campaign_recipient",
          columnNames = {"campaign_id", "recipient_id"}),
      @UniqueConstraint(name = "uq_snr_correlation", columnNames = "correlation_id")
    })
@Getter
@Setter
@NoArgsConstructor
public class ServiceNoticeRecipient {

  public enum MailStatus {
    /** A mail is due; the sender will check the switch and address again before sending. */
    PENDING,
    /** Claimed by the sender; committed before the mail is handed to a transport. */
    SENDING,
    SENT,
    /** Not sent: at send time the person no longer wanted or could receive it. */
    SUPPRESSED,
    /** The transport failed after handoff; resending could duplicate the mail. */
    UNCERTAIN,
    /** Given up after the retry limit; the mail never left. */
    FAILED,
    NOT_SENT_PREFERENCE_OFF,
    NOT_SENT_NO_ADDRESS,
    NOT_SENT_NO_SENDER_TENANT;

    /** False for the rows that were recorded as in-app only when the notice was confirmed. */
    public boolean mailQueuedAtConfirmation() {
      return !name().startsWith("NOT_SENT_");
    }
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "campaign_id", nullable = false)
  private Long campaignId;

  @Column(name = "recipient_id", nullable = false, length = 36)
  private String recipientId;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "mail_status", nullable = false, length = 32)
  private MailStatus mailStatus;

  @Column(name = "correlation_id", nullable = false, length = 36)
  private String correlationId;

  @Column(name = "failure_count", nullable = false)
  private int failureCount;

  @Column(name = "next_attempt_at_utc", nullable = false)
  private LocalDateTime nextAttemptAtUtc;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "claimed_at")
  private LocalDateTime claimedAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;
}
