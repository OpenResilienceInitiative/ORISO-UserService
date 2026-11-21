package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The exact Matrix departure and local roster state to check after an interrupted edit. */
@Entity
@Table(name = "group_roster_removal_intent")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class GroupRosterRemovalIntent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "series_id", nullable = false, updatable = false)
  private Long seriesId;

  @Column(name = "owner_id", nullable = false, length = 36, updatable = false)
  private String ownerId;

  @Column(name = "tenant_id", updatable = false)
  private Long tenantId;

  @Column(name = "series_room_id", length = 255, updatable = false)
  private String seriesRoomId;

  @Column(name = "occurrence_index", nullable = false, updatable = false)
  private int occurrenceIndex;

  @Column(name = "participant_id", nullable = false, updatable = false)
  private Long participantId;

  @Column(name = "participant_version", nullable = false, updatable = false)
  private long participantVersion;

  @Column(name = "consultant_id", nullable = false, length = 36, updatable = false)
  private String consultantId;

  @Column(name = "member_id", nullable = false, length = 255, updatable = false)
  private String memberId;

  @Column(name = "room_id", nullable = false, length = 255, updatable = false)
  private String roomId;

  /** The exact side-room supervision, or null for a departure from the group room itself. */
  @Column(name = "supervision_id", updatable = false)
  private Long supervisionId;

  /** The exact supervision without a side room that a group-room departure can end locally. */
  @Column(name = "local_supervision_id", updatable = false)
  private Long localSupervisionId;

  /** The departure request has finished or absence was verified after an uncertain response. */
  @Setter
  @Column(name = "dispatch_settled", nullable = false)
  private boolean dispatchSettled;

  @Setter
  @Column(name = "retry_at", nullable = false, columnDefinition = "datetime(6)")
  private LocalDateTime retryAt;
}
