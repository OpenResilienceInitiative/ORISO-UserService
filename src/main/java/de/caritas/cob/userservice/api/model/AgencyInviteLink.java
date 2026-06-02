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

  /** {@code LIVE_CHAT} for now; reserved for future chat types. */
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
   * @deprecated Agency is no longer carried on invite links. Kept nullable for legacy rows; will
   *     be dropped in a phase-2 cleanup.
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
}
