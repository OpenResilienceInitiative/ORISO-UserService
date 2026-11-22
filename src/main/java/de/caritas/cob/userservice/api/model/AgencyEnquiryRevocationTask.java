package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A Matrix membership to reconcile after a counsellor leaves a centre. */
@Entity
@Table(name = "agency_enquiry_revocation_task")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgencyEnquiryRevocationTask {

  // Older replicas also exclude these tasks through their ordinary retryAt query.
  public static final LocalDateTime WAITING_FOR_ROOM_DECISION =
      LocalDateTime.of(9999, 12, 31, 0, 0);

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "consultant_agency_id", nullable = false, updatable = false)
  private Long consultantAgencyId;

  @Column(name = "consultant_id", nullable = false, updatable = false, length = 36)
  private String consultantId;

  @Column(name = "agency_id", nullable = false, updatable = false)
  private Long agencyId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "room_id", nullable = false, updatable = false, length = 255)
  private String roomId;

  @Column(name = "member_id", nullable = false, updatable = false, length = 255)
  private String memberId;

  @Column(name = "room_decision_id", updatable = false)
  private Long roomDecisionId;

  @Column(name = "retry_at", nullable = false)
  private LocalDateTime retryAt;

  @Builder
  AgencyEnquiryRevocationTask(
      Long consultantAgencyId,
      String consultantId,
      Long agencyId,
      Long sessionId,
      String roomId,
      String memberId,
      Long roomDecisionId,
      LocalDateTime retryAt) {
    this.consultantAgencyId = consultantAgencyId;
    this.consultantId = consultantId;
    this.agencyId = agencyId;
    this.sessionId = sessionId;
    this.roomId = roomId;
    this.memberId = memberId;
    this.roomDecisionId = roomDecisionId;
    this.retryAt = retryAt;
  }

  public void retryAt(LocalDateTime retryAt) {
    this.retryAt = retryAt;
  }
}
