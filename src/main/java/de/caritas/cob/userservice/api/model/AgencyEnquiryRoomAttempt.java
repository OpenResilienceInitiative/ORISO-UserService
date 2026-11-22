package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

  public boolean matchesBinding(Session confirmed) {
    return sessionId.equals(confirmed.getId())
        && agencyId.equals(confirmed.getAgencyId())
        && Objects.equals(tenantId, confirmed.getTenantId())
        && ownershipRevision == confirmed.getOwnershipRevision()
        && roomId.equals(confirmed.getMatrixRoomId());
  }
}
