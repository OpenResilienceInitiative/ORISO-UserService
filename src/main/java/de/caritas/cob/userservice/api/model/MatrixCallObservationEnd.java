package de.caritas.cob.userservice.api.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * One person's end or cancellation of an observed call (#1628), stored per (source room, call,
 * actor). Several people may end the same call, also before its registration has landed; each end
 * is kept on its own, so the creator's own cancellation is never lost behind somebody else's. The
 * creator's end refuses a late registration of that call. Observation only, like {@link
 * MatrixCallObservation}: it never grants access.
 */
@Entity
@Table(name = "matrix_call_observation_end")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatrixCallObservationEnd {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "source_room_id", nullable = false, length = 255)
  private String sourceRoomId;

  @Column(name = "call_id", nullable = false, length = 191)
  private String callId;

  @Column(name = "actor_matrix_id", nullable = false, length = 255)
  private String actorMatrixId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  /** Internal group source; null for a session call. */
  @Column(name = "chat_id")
  private Long chatId;

  /** Session source (#2237); null for an internal-group call. */
  @Column(name = "session_id")
  private Long sessionId;

  @Column(name = "ended_at", nullable = false)
  private long endedAt;
}
