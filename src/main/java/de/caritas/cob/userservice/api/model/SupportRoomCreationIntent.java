package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The exact Matrix creation attempt reserved for one support handshake. */
@Entity
@Table(name = "support_room_creation_intent")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SupportRoomCreationIntent {

  @Id
  @Column(name = "handshake_id", length = 36, nullable = false)
  private String handshakeId;

  @Column(name = "id", length = 36, nullable = false, updatable = false)
  private String id;

  @Column(name = "nonce", length = 36, nullable = false, updatable = false)
  private String nonce;

  @Column(name = "support_admin_id", length = 36, nullable = false, updatable = false)
  private String supportAdminId;

  @Column(name = "support_admin_matrix_id", length = 255, nullable = false, updatable = false)
  private String supportAdminMatrixId;

  @Column(name = "consultant_id", length = 36, nullable = false, updatable = false)
  private String consultantId;

  @Column(name = "consultant_matrix_id", length = 255, nullable = false, updatable = false)
  private String consultantMatrixId;

  @Column(name = "tenant_id", updatable = false)
  private Long tenantId;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private LocalDateTime expiresAt;

  @Setter
  @Column(name = "retry_at", nullable = false)
  private LocalDateTime retryAt;

  /** The local part supplied when creating the room. */
  public String getRoomAlias() {
    return "support-" + id;
  }
}
