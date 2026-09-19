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

  /**
   * Service hours of the live chat behind this invitation, as a JSON array in the schema.org
   * OpeningHoursSpecification shape: {@code [{"dayOfWeek":1,"opens":"09:00","closes":"12:00"}]},
   * ISO-8601 weekday numbers and 24-hour local times. Null means no configured hours, which is not
   * the same as closed around the clock.
   */
  @Column(name = "opening_hours", length = 2000)
  private String openingHours;

  /** IANA zone the times above are stated in. Without it they mean nothing. */
  @Column(name = "opening_hours_time_zone", length = 64)
  private String openingHoursTimeZone;

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
}
