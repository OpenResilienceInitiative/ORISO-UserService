package de.caritas.cob.userservice.api.service.agencyinvitelink;

import java.util.Set;

/** Specifies the communication channel that will be opened when the link is redeemed. */
public enum InviteLinkChatType {
  /** Real-time Matrix live-chat session (admin-created topic links). */
  LIVE_CHAT,

  /** Counsellor direct link: 1:1 chat, enters directly (ADR-029 §2). */
  CHAT,

  /** Counsellor direct link: 1:1 audio call, knock first. */
  AUDIO,

  /** Counsellor direct link: 1:1 video call, knock first. */
  VIDEO;

  public static final Set<String> DIRECT_MEDIA = Set.of(CHAT.name(), AUDIO.name(), VIDEO.name());

  public static boolean isDirectMedium(String chatType) {
    return chatType != null && DIRECT_MEDIA.contains(chatType);
  }

  public InviteLinkAdmission admission() {
    return this == AUDIO || this == VIDEO ? InviteLinkAdmission.KNOCK : InviteLinkAdmission.DIRECT;
  }
}
