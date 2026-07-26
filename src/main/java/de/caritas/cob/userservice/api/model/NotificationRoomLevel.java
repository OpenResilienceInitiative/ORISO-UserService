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
import lombok.ToString;

/**
 * Server-side mirror of the per-conversation notification level (decided 2026-07-18: All / Mentions
 * only / Muted / Snoozed-with-auto-revert). The authoritative user-facing copy lives in Matrix
 * account data (FE #420); the producers here cannot read that, so the frontend mirrors the level
 * via {@code PATCH /users/event-notifications/conversation-level} and the fan-out honours it. A
 * snooze is expressed as {@code snoozedUntil} in the future — after that moment the stored level
 * applies again (auto-revert without any cleanup job).
 */
@Entity
@Table(name = "notification_room_level")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class NotificationRoomLevel {

  public enum Level {
    ALL,
    MENTIONS,
    MUTED
  }

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "user_id", nullable = false, length = 64)
  private String userId;

  @Column(name = "room_id", nullable = false, length = 255)
  private String roomId;

  @Enumerated(EnumType.STRING)
  @Column(name = "notification_level", nullable = false, length = 20)
  @Builder.Default
  private Level level = Level.ALL;

  @Column(name = "snoozed_until")
  private LocalDateTime snoozedUntil;

  @Column(name = "update_date", nullable = false)
  private LocalDateTime updateDate;
}
