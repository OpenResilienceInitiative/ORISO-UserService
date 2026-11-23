package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Evidence of an enquiry room that has been created and is awaiting a confirmed binding. */
@Entity
@Table(name = "agency_enquiry_room_attempt")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgencyEnquiryRoomAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "agency_id", nullable = false, updatable = false)
  private Long agencyId;

  @Column(name = "tenant_id", updatable = false)
  private Long tenantId;

  @Column(name = "ownership_revision", nullable = false, updatable = false)
  private long ownershipRevision;

  @Column(name = "room_id", nullable = false, updatable = false, length = 255)
  private String roomId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "requester_invitation_started", nullable = false)
  private boolean requesterInvitationStarted;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "requester_join_plan", columnDefinition = "json")
  private RequesterJoinPlan requesterJoinPlan;

  @Column(name = "requester_join_ready_at")
  private LocalDateTime requesterJoinReadyAt;

  @Column(name = "requester_join_claim", length = 36)
  private String requesterJoinClaim;

  public AgencyEnquiryRoomAttempt(Session session, String roomId, LocalDateTime createdAt) {
    sessionId = session.getId();
    agencyId = session.getAgencyId();
    tenantId = session.getTenantId();
    ownershipRevision = session.getOwnershipRevision();
    this.roomId = roomId;
    this.createdAt = createdAt;
  }

  public void markRequesterInvitationStarted() {
    requesterInvitationStarted = true;
  }

  public boolean matchesPreparation(Session current) {
    return sessionId.equals(current.getId())
        && agencyId.equals(current.getAgencyId())
        && Objects.equals(tenantId, current.getTenantId())
        && ownershipRevision == current.getOwnershipRevision();
  }

  public boolean matchesBinding(Session confirmed) {
    return matchesPreparation(confirmed) && roomId.equals(confirmed.getMatrixRoomId());
  }

  public void recordRequesterJoin(RequesterJoinPlan plan, String claim, LocalDateTime leaseUntil) {
    requesterJoinPlan = plan;
    claimRequesterJoin(claim, leaseUntil);
  }

  public void claimRequesterJoin(String claim, LocalDateTime leaseUntil) {
    requesterJoinClaim = claim;
    requesterJoinReadyAt = leaseUntil;
  }

  public void deferRequesterJoin(LocalDateTime resumeAt) {
    requesterJoinClaim = null;
    requesterJoinReadyAt = resumeAt;
  }

  public void finishRequesterJoin() {
    requesterJoinPlan = null;
    requesterJoinClaim = null;
    requesterJoinReadyAt = null;
  }

  public record RequesterJoinPlan(
      String requesterId,
      String requesterMatrixUserId,
      String agencyMatrixUserId,
      boolean directlyAssigned,
      List<InitialContact> contacts,
      List<String> deferredConsultantIds) {
    public RequesterJoinPlan {
      contacts = List.copyOf(contacts);
      deferredConsultantIds = List.copyOf(deferredConsultantIds);
    }
  }

  public record InitialContact(String consultantId, String matrixUserId) {}
}
