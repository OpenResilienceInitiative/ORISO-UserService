package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A person who entered a conversation through a direct invitation with an appointment
 * (ORISO-Frontend#2252): everybody in a shared appointment conversation, and the invited people in
 * a 1:1 the invitation was created from. Guests (temporary accounts) are taken out of the room
 * again when the invitation expires ({@code removedAt}).
 */
@Entity
@Table(
    name = "direct_invitation_member",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uq_direct_invitation_member_session_user",
            columnNames = {"session_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DirectInvitationMember {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id", nullable = false)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "user_id", nullable = false, length = 36)
  private String userId;

  @Column(name = "invite_link_id", nullable = false)
  private Long inviteLinkId;

  /** Entered with a temporary account. */
  @Column(name = "guest", nullable = false)
  private boolean guest;

  @Column(name = "joined_at", nullable = false)
  private LocalDateTime joinedAt;

  @Column(name = "removed_at")
  private LocalDateTime removedAt;
}
