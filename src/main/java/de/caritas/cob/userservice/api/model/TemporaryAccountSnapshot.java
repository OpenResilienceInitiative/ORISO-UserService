package de.caritas.cob.userservice.api.model;

import de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A temporary account created through a counsellor direct link (ADR-029 §3–§5). The platform
 * lifetimes and the retention mode are copied here when the account is created, so later setting
 * changes never shorten or extend an existing account. Null on every other user.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemporaryAccountSnapshot {

  /** CHAT, AUDIO or VIDEO: the medium of the direct link the account was created for. */
  @Column(name = "temporary_medium", length = 16)
  @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
  private de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType medium;

  /** The only direct link this account may start a conversation through. No FK: links go. */
  @Column(name = "temporary_invite_link_id")
  private Long inviteLinkId;

  @Column(name = "temporary_call_delete_after_minutes")
  private Integer callDeleteAfterMinutes;

  @Column(name = "temporary_call_max_lifetime_hours")
  private Integer callMaxLifetimeHours;

  @Column(name = "temporary_chat_inactivity_days")
  private Integer chatInactivityDays;

  @Enumerated(EnumType.STRING)
  @Column(name = "temporary_message_retention", length = 32)
  private MessageRetentionMode messageRetention;

  /** The guest's own answer under GUEST_DECIDES; null while undecided. */
  @Column(name = "temporary_keep_messages")
  private Boolean keepMessages;

  /** Set once Keycloak and Matrix identities are gone; the row stays for "Gast (gelöscht)". */
  @Column(name = "temporary_deleted_at")
  private LocalDateTime deletedAt;

  /** Set when the cleanup removed the Keycloak user; later runs finish without a new check. */
  @Column(name = "temporary_deletion_started_at")
  private LocalDateTime deletionStartedAt;

  /** The Synapse redaction task still to be confirmed; polled, never submitted twice. */
  @Column(name = "temporary_redact_id", length = 255)
  private String redactId;

  public boolean isCall() {
    return medium
            == de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.AUDIO
        || medium
            == de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.VIDEO;
  }

  /** Whether this account's messages stay after deletion. Undecided counts as deleted. */
  public boolean keepsMessages() {
    if (messageRetention == null) {
      return false;
    }
    return switch (messageRetention) {
      case ALWAYS_PERSISTENT -> true;
      case ALWAYS_TEMPORARY -> false;
      case GUEST_DECIDES -> Boolean.TRUE.equals(keepMessages);
    };
  }
}
