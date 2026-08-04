package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One temporary support session room (ADR-018 §2): a fresh encrypted 1:1 Matrix room between a
 * Global Support Admin and a Berater*in, hard-limited to four hours and never reused — every
 * further help cycle starts completely from scratch.
 */
@Entity
@Table(name = "support_room")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupportRoom {

  public enum SupportRoomStatus {
    ACTIVE,
    CLOSED
  }

  @Id
  @Column(name = "id", length = 36, nullable = false)
  private String id;

  @Column(name = "handshake_id", length = 36, nullable = false)
  private String handshakeId;

  @Column(name = "matrix_room_id", length = 255, nullable = false)
  private String matrixRoomId;

  @Column(name = "support_admin_id", length = 36, nullable = false)
  private String supportAdminId;

  @Column(name = "support_admin_matrix_id", length = 255)
  private String supportAdminMatrixId;

  @Column(name = "consultant_id", length = 36, nullable = false)
  private String consultantId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 16, nullable = false)
  private SupportRoomStatus status;

  @Column(name = "close_reason", length = 16)
  private String closeReason;

  @Column(name = "create_date", nullable = false)
  private LocalDateTime createDate;

  @Column(name = "expiry_date", nullable = false)
  private LocalDateTime expiryDate;

  @Column(name = "closed_date")
  private LocalDateTime closedDate;

  @Column(name = "tenant_id")
  private Long tenantId;
}
