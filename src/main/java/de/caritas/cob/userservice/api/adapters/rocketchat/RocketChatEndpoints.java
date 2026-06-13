package de.caritas.cob.userservice.api.adapters.rocketchat;

/** Shared Rocket.Chat API endpoint URL constants. */
public final class RocketChatEndpoints {

  private RocketChatEndpoints() {}

  // User endpoints
  public static final String USER_CREATE = "/users.create";
  public static final String USER_UPDATE = "/users.update";
  public static final String USER_DELETE = "/users.delete";
  public static final String USER_LIST = "/users.list";
  public static final String USER_LOGIN = "/login";
  public static final String USER_LOGOUT = "/logout";
  public static final String USER_INFO = "/users.info?userId=";
  public static final String USER_PRESENCE_GET = "/users.getPresence?userId=";
  public static final String USER_PRESENCE_SET = "/method.call/UserPresence";
  public static final String USER_PRESENCE_LIST = "/users.presence";

  // Group endpoints
  public static final String GROUP_CREATE = "/groups.create";
  public static final String GROUP_DELETE = "/groups.delete";
  public static final String GROUP_INVITE = "/groups.invite";
  public static final String GROUP_KICK = "/groups.kick";
  public static final String GROUP_MEMBERS = "/groups.members";
  public static final String GROUP_READ_ONLY = "/groups.setReadOnly";
  public static final String GROUP_KEY_UPDATE = "/e2e.updateGroupKey";
  public static final String GROUP_LIST = "/groups.listAll";

  // Room endpoints
  public static final String ROOM_LEAVE = "/rooms.leave";
  public static final String ROOM_CLEAN_HISTORY = "/rooms.cleanHistory";
  public static final String ROOM_GET = "/rooms.get";
  public static final String ROOM_INFO = "/rooms.info?roomId=";
  public static final String ROOM_SAVE_SETTINGS = "/rooms.saveRoomSettings";

  // Subscription endpoints
  public static final String SUBSCRIPTION_GET = "/subscriptions.get";

  // Misc endpoints
  public static final String USER_MUTE = "/method.call/muteUserInRoom";
  public static final String USER_UNMUTE = "/method.call/unmuteUserInRoom";
}
