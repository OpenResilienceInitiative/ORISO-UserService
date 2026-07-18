package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A consultant who opened or posted in a Team-Besprechung. Drives the hybrid notification rule:
 * after the first post only participants (plus mentioned colleagues) are notified.
 */
@Entity
@Table(name = "team_discussion_participant")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@ToString
public class TeamDiscussionParticipant {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", updatable = false, nullable = false)
  private Long id;

  @Column(name = "team_discussion_id", nullable = false)
  private Long teamDiscussionId;

  @Column(name = "consultant_id", nullable = false, length = 36)
  private String consultantId;

  @Column(name = "join_date", nullable = false)
  private LocalDateTime joinDate;
}
