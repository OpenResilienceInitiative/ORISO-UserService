package de.caritas.cob.userservice.api.service.matrix;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Metadata from the current MatrixRTC room-scoped membership format. The dedicated room binding,
 * never content.call_id or the state key, determines the application call and conversation.
 */
record MatrixRtcMembershipEvent(
    String eventId,
    String sender,
    String stateKey,
    String deviceId,
    long eventTimestamp,
    long expiresAt,
    boolean departed,
    String replacesEventId) {

  static final String TYPE = "org.matrix.msc3401.call.member";

  private static final long DEFAULT_EXPIRE_DURATION = 4 * 60 * 60 * 1000L;

  /**
   * FE#2209 review: created_ts and expires are sender-controlled. A membership counts as live for
   * at most this long after its server timestamp. Element Call (matrix-js-sdk MembershipManager)
   * sends a four-hour expiry and refreshes the event before it runs out, keeping created_ts and
   * growing expires; each refresh carries a new server timestamp, so long calls stay live.
   */
  static final long MAX_MEMBERSHIP_VALIDITY_MILLIS = DEFAULT_EXPIRE_DURATION + 60 * 60 * 1000L;

  /**
   * FE#2209 round 3: a MatrixRTC state key names its owner, {@code _@user:server_DEVICE...} (or the
   * legacy {@code @user:server_DEVICE} and {@code @user:server}). A key naming anybody but the
   * sender is somebody else's device and is ignored, so nobody can claim it first. A server name
   * never contains "_", so the separator after the sender is unambiguous.
   */
  static boolean ownedBy(String stateKey, String sender) {
    return stateKey.equals(sender)
        || stateKey.startsWith(sender + "_")
        || stateKey.startsWith("_" + sender + "_");
  }

  static Optional<MatrixRtcMembershipEvent> parse(Map<String, Object> event) {
    if (!TYPE.equals(event.get("type"))) return Optional.empty();
    if (!(event.get("event_id") instanceof String eventId)
        || eventId.isBlank()
        || !(event.get("sender") instanceof String sender)
        || !sender.startsWith("@")
        || !sender.contains(":")
        || !(event.get("state_key") instanceof String stateKey)
        || stateKey.isBlank()
        || !(event.get("content") instanceof Map<?, ?> content)
        || !ownedBy(stateKey, sender)) return Optional.empty();
    Long timestamp = nonNegativeInteger(event.get("origin_server_ts"));
    if (timestamp == null) return Optional.empty();
    String replaces =
        event.get("unsigned") instanceof Map<?, ?> unsigned
                && unsigned.get("replaces_state") instanceof String replaced
                && !replaced.isBlank()
            ? replaced
            : null;
    if (content.isEmpty()) {
      return Optional.of(
          new MatrixRtcMembershipEvent(
              eventId, sender, stateKey, null, timestamp, timestamp, true, replaces));
    }
    if (!"m.call".equals(content.get("application"))
        || !"m.room".equals(content.get("scope"))
        || !"".equals(content.get("call_id"))
        || !(content.get("device_id") instanceof String deviceId)
        || deviceId.isBlank()
        || !(content.get("focus_active") instanceof Map<?, ?> focus)
        || !(focus.get("type") instanceof String focusType)
        || focusType.isBlank()
        || !(content.get("foci_preferred") instanceof List<?>)) return Optional.empty();
    Long created =
        content.containsKey("created_ts")
            ? nonNegativeInteger(content.get("created_ts"))
            : timestamp;
    Long duration =
        content.containsKey("expires")
            ? nonNegativeInteger(content.get("expires"))
            : Long.valueOf(DEFAULT_EXPIRE_DURATION);
    if (created == null || duration == null) return Optional.empty();
    try {
      long claimed = Math.addExact(Math.min(created, timestamp), duration);
      long cap =
          timestamp > Long.MAX_VALUE - MAX_MEMBERSHIP_VALIDITY_MILLIS
              ? Long.MAX_VALUE
              : timestamp + MAX_MEMBERSHIP_VALIDITY_MILLIS;
      return Optional.of(
          new MatrixRtcMembershipEvent(
              eventId,
              sender,
              stateKey,
              deviceId,
              timestamp,
              Math.min(claimed, cap),
              false,
              replaces));
    } catch (ArithmeticException overflow) {
      return Optional.empty();
    }
  }

  private static Long nonNegativeInteger(Object value) {
    if (!(value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long)) return null;
    long number = ((Number) value).longValue();
    return number >= 0 ? number : null;
  }
}
