package de.caritas.cob.userservice.api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
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

  /**
   * Set when the person must leave the room (invitation expired, switched off or deleted): the time
   * of the next removal attempt. Stays set with {@code removedAt} empty until Matrix confirmed the
   * removal; a failed attempt moves it on, so the sweep retries with a delay.
   */
  @Column(name = "revoke_due_at")
  private LocalDateTime revokeDueAt;

  /** Matrix confirmed the person left the room. */
  @Column(name = "removed_at")
  private LocalDateTime removedAt;

  /**
   * The join attempt that currently owns this row (a random token), set from before the Matrix join
   * until it is confirmed or abandoned; see {@code DirectInvitationMemberships}.
   */
  @Column(name = "join_attempt", length = 36)
  private String joinAttempt;

  /**
   * ADR-022 Gate 2 pointer of this one person (ORISO-Frontend#2252, decision B): the legal-text
   * version they agreed to themselves. Each person of a shared appointment conversation passes the
   * gate on their own; the owner keeps {@code session.consented_legal_version_id}. A pointer, not a
   * log — overwritten on re-consent, no timestamp beside it.
   */
  @Column(name = "consented_legal_version_id")
  private Long consentedLegalVersionId;

  /** Optimistic lock: the sweep never overwrites a change a join made meanwhile. */
  @Version
  @Column(name = "version", nullable = false)
  @Builder.Default
  private Long version = 0L;
}
