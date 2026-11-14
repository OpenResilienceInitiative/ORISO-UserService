package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationAccessMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Outside the tenant filter on purpose: a link is redeemed on a public route that has no tenant
 * yet, so the admin reads name the caller's tenant in their queries instead.
 */
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

  /**
   * The topic this invite link is bound to. Always required for links created by the new flow.
   * Column is nullable in the schema only so legacy rows produced before changeset 0052 can keep
   * existing without being backfilled — the application layer rejects creates with a null topic.
   */
  @Column(name = "topic_id")
  private Long topicId;

  /** {@code TENANT} / {@code COUNSELLOR} / {@code EXTERNAL_INBOUND}. */
  @Column(name = "link_kind", nullable = false, length = 32)
  private String linkKind;

  /**
   * {@code LIVE_CHAT} for admin links; {@code CHAT} / {@code AUDIO} / {@code VIDEO} for direct
   * links.
   */
  @Column(name = "chat_type", nullable = false, length = 32)
  private String chatType;

  /** {@code FULL} for now; reserved for future anonymity modes. */
  @Column(name = "anonymity", nullable = false, length = 16)
  private String anonymity;

  @Column(name = "notes", length = 500)
  private String notes;

  /** Required only when {@link #linkKind} = {@code COUNSELLOR}. */
  @Column(name = "consultant_id", length = 36)
  private String consultantId;

  /**
   * @deprecated Agency is no longer carried on invite links. Kept nullable for legacy rows; will be
   *     dropped in a phase-2 cleanup.
   */
  @Deprecated
  @Column(name = "agency_id")
  private Long agencyId;

  /**
   * @deprecated Agency-derived consulting type is no longer stored on the link. Kept nullable for
   *     legacy rows; will be dropped in a phase-2 cleanup.
   */
  @Deprecated
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

  /** Counsellor-chosen label of a direct link, e.g. "Flyer Schule" (#1475). */
  @Column(name = "name", length = 100)
  private String name;

  /**
   * Optional date and time a counsellor noted for a direct link, in UTC (ORISO-Frontend#2142).
   * Informational only: the link works before and after it.
   */
  @Column(name = "scheduled_at")
  private LocalDateTime scheduledAt;

  /** Direct links: people without an account get a temporary one (ADR-029 §3). */
  @Builder.Default
  @Column(name = "temporary_account", nullable = false)
  private boolean temporaryAccount = false;

  @Column(name = "update_date")
  private LocalDateTime updateDate;

  /**
   * Call share links (#1487): guests wait for the counsellor ("Anklopfen") instead of joining
   * straight away. Read only for {@code link_kind = CALL}.
   */
  @Builder.Default
  @Column(name = "knock", nullable = false)
  private boolean knock = true;

  /** Call share links (#1487): the one running call ({@code matrix_call_binding.id}). */
  @Column(name = "call_binding_id", unique = true)
  private Long callBindingId;

  /**
   * Direct invitation with an appointment (ORISO-Frontend#2252): the {@code session_appointment}
   * everybody who opens the link meets at. Its session is the shared appointment conversation once
   * the first person opened the link (or the 1:1 the invitation was created from).
   */
  @JdbcTypeCode(SqlTypes.CHAR)
  @Column(name = "appointment_id", columnDefinition = "char(36)")
  private UUID appointmentId;

  /** With an appointment only: minutes the link stays valid after its end (0..2880). */
  @Column(name = "validity_after_end_minutes")
  private Integer validityAfterEndMinutes;

  /**
   * Who may enter (ORISO-Frontend#2252). Empty for links from before: read from {@link
   * #temporaryAccount} then, see {@link #effectiveAccessMode()}.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "access_mode", length = 16)
  private DirectInvitationAccessMode accessMode;

  /** The invitation was created from an existing 1:1 and adds people to it (#2252). */
  @Builder.Default
  @Column(name = "joins_existing_session", nullable = false)
  private boolean joinsExistingSession = false;

  /**
   * The token before the last "Neuen Link senden" (#2252): it still finds the invitation, which
   * then answers "expired", so the person can ask for a new link again.
   */
  @Column(name = "previous_token", length = 64)
  private String previousToken;

  public DirectInvitationAccessMode effectiveAccessMode() {
    if (accessMode != null) {
      return accessMode;
    }
    return temporaryAccount
        ? DirectInvitationAccessMode.BOTH
        : DirectInvitationAccessMode.ACCOUNTS_ONLY;
  }
}
