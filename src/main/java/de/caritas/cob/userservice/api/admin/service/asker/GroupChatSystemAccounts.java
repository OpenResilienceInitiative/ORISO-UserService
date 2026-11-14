package de.caritas.cob.userservice.api.admin.service.asker;

/**
 * The technical {@code group-chat-system} and {@code group-chat-system-<tenantId>} users (see
 * {@code CreateChatFacade}) own every group session of their Träger, group circles and internal
 * group chats alike. They are no person: the advice-seeker list leaves them out and an admin may
 * not mark them, because the nightly job would delete every group chat they own (ORISO-Admin#1521).
 * They go when their Träger is deleted (#1525).
 */
public final class GroupChatSystemAccounts {

  static final String PREFIX = "group-chat-system";

  private GroupChatSystemAccounts() {}

  public static boolean isTechnical(String userId) {
    return userId != null && userId.startsWith(PREFIX);
  }
}
