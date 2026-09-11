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

  private static final long DEFAULT_EXPIRE_DURATION = 4 * 60 * 60 * 1000L;

  static Optional<MatrixRtcMembershipEvent> parse(Map<String, Object> event) {
    if (!"org.matrix.msc3401.call.member".equals(event.get("type"))) return Optional.empty();
    if (!(event.get("event_id") instanceof String eventId)
        || eventId.isBlank()
        || !(event.get("sender") instanceof String sender)
        || !sender.startsWith("@")
        || !sender.contains(":")
        || !(event.get("state_key") instanceof String stateKey)
        || stateKey.isBlank()
        || !(event.get("content") instanceof Map<?, ?> content)) return Optional.empty();
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
      return Optional.of(
          new MatrixRtcMembershipEvent(
              eventId,
              sender,
              stateKey,
              deviceId,
              timestamp,
              Math.addExact(created, duration),
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
