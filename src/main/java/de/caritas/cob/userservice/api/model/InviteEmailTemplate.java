package de.caritas.cob.userservice.api.model;

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
    name = "invite_email_template",
    indexes = {@Index(name = "idx_invite_email_template_kind", columnList = "kind,active")})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class InviteEmailTemplate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 32)
  private InviteEmailTemplateKind kind;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "language", length = 16)
  private String language;

  @Column(name = "subject", nullable = false)
  private String subject;

  @Lob
  @Column(name = "body", nullable = false, columnDefinition = "LONGTEXT")
  private String body;

  @Column(name = "active", nullable = false)
  @Builder.Default
  private Boolean active = true;

  @Column(name = "created_by_user_id", length = 36)
  private String createdByUserId;

  @Column(name = "create_date", nullable = false, columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;
}
