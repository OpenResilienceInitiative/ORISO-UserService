package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The exact initial department room affected by one winning acceptance. */
@Entity
@Table(name = "agency_enquiry_room_decision")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AgencyEnquiryRoomDecision {

  public enum Outcome {
    PENDING,
    REUSED,
    REPLACED,
    REOPENED
  }

  public static final String EVENT_SEQUENCE = "sequence_enquiry_room_event";

  @Id
  @SequenceGenerator(name = EVENT_SEQUENCE, sequenceName = EVENT_SEQUENCE, allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = EVENT_SEQUENCE)
  private Long id;

  @Column(name = "session_id", nullable = false, updatable = false)
  private Long sessionId;

  @Column(name = "agency_id", nullable = false, updatable = false)
  private Long agencyId;

  @Column(name = "tenant_id", nullable = false, updatable = false)
  private Long tenantId;

  @Column(name = "room_id", nullable = false, updatable = false, length = 255)
  private String roomId;

  @Column(name = "ownership_revision", nullable = false, updatable = false)
  private long ownershipRevision;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 10)
  private Outcome outcome;

  @Column(name = "resolved_order")
  private Long resolvedOrder;

  @Enumerated(EnumType.STRING)
  @Column(name = "opening_status", length = 32)
  private SessionStatus openingStatus;

  // Null means that external room work may already have started, or an older writer made the row.
  @Column(name = "prepared_until")
  private LocalDateTime preparedUntil;

  public AgencyEnquiryRoomDecision(
      Session accepted, SessionStatus openingStatus, LocalDateTime preparedUntil) {
    sessionId = accepted.getId();
    agencyId = accepted.getAgencyId();
    tenantId = accepted.getTenantId();
    roomId = accepted.getMatrixRoomId();
    ownershipRevision = accepted.getOwnershipRevision();
    outcome = Outcome.PENDING;
    this.openingStatus = openingStatus;
    this.preparedUntil = preparedUntil;
  }

  public void clearPreparation() {
    preparedUntil = null;
  }

  public void resolve(Outcome outcome, long eventOrder) {
    this.outcome = outcome;
    this.resolvedOrder = eventOrder;
    clearPreparation();
  }

  public boolean matches(Session session) {
    return sessionId.equals(session.getId())
        && tenantId.equals(session.getTenantId())
        && agencyId.equals(session.getAgencyId())
        && ownershipRevision == session.getOwnershipRevision()
        && roomId.equals(session.getMatrixRoomId());
  }

  public boolean matches(AgencyEnquiryRevocationTask task, Session current) {
    return sessionId.equals(task.getSessionId())
        && tenantId.equals(current.getTenantId())
        && agencyId.equals(task.getAgencyId())
        && roomId.equals(task.getRoomId())
        && Objects.equals(current.getAgencyId(), agencyId);
  }
}
