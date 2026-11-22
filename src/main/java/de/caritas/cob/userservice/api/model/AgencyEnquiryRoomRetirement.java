package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** Records a holding room displaced during first acceptance of the same enquiry. */
@Entity
@Table(name = "agency_enquiry_room_retirement")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgencyEnquiryRoomRetirement {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "room_id", nullable = false, updatable = false, length = 255)
  private String roomId;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "agency_id", nullable = false, updatable = false)
  private Long agencyId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "replacement_room_id", nullable = false, updatable = false, length = 255)
  private String replacementRoomId;

  public AgencyEnquiryRoomRetirement(
      String roomId, Long sessionId, Long agencyId, Long tenantId, String replacementRoomId) {
    this.roomId = roomId;
    this.sessionId = sessionId;
    this.agencyId = agencyId;
    this.tenantId = tenantId;
    this.replacementRoomId = replacementRoomId;
  }
}
