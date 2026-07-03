package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailDeliveryStatus;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(
    name = "invite_email_delivery",
    indexes = {@Index(name = "idx_invite_email_delivery_invite", columnList = "account_invite_id")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InviteEmailDelivery {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "account_invite_id", nullable = false)
  private Long accountInviteId;

  @Column(name = "template_id")
  private Long templateId;

  @Enumerated(EnumType.STRING)
  @Column(name = "template_kind", nullable = false, length = 32)
  private InviteEmailTemplateKind templateKind;

  @Column(name = "subject_snapshot", nullable = false)
  private String subjectSnapshot;

  @Lob
  @Column(name = "body_snapshot", nullable = false)
  private String bodySnapshot;

  @Column(name = "recipient_snapshot", nullable = false)
  private String recipientSnapshot;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private InviteEmailDeliveryStatus status;

  @Column(name = "sent_at", columnDefinition = "datetime")
  private LocalDateTime sentAt;

  @Column(name = "failure_reason", length = 1024)
  private String failureReason;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;
}
