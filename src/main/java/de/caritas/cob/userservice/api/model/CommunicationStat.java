package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * One row per counted communication event for the admin statistics dashboard: an advice-seeker
 * message, an audio call or a video call. Voice messages are not counted: session rooms are
 * end-to-end encrypted, so the server cannot tell a voice message from a text message.
 *
 * <p>KDG: the row holds no message content and no identity of an advice seeker or counsellor, only
 * where (tenant, agency, session) and when something happened. {@code callKey} is a SHA-256 of the
 * Matrix call id and exists only so that one call is counted once.
 */
@Entity
@Builder
@Table(name = "communication_stat")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class CommunicationStat {

  public enum Kind {
    ADVICE_SEEKER_MESSAGE,
    AUDIO_CALL,
    VIDEO_CALL
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "agency_id")
  private Long agencyId;

  @Column(name = "session_id")
  private Long sessionId;

  @Enumerated(EnumType.STRING)
  @Column(name = "kind", nullable = false, length = 40)
  private Kind kind;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;

  @Column(name = "call_key", length = 64, unique = true)
  private String callKey;
}
