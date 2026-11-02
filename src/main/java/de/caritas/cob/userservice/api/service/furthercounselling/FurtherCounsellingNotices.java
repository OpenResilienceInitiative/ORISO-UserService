package de.caritas.cob.userservice.api.service.furthercounselling;

import static de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.FURTHER_COUNSELLING_RECOMMENDED_TYPE;
import static de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.SYSTEM_NOTIFICATION_PREFIX;
import static de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.TOPIC_DUPLICATE_ENDED_TYPE;
import static de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService.TOPIC_DUPLICATE_STARTED_TYPE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FE#1876 room notices. Each body is the prefix plus JSON. The payloads carry ids only: no
 * postcode, no asker data, and no name or id of the new counselling centre.
 */
public final class FurtherCounsellingNotices {

  private static final ObjectMapper JSON = new ObjectMapper();

  private FurtherCounsellingNotices() {}

  public static String recommended(
      long topicId, String note, String recommendedBy, Instant createdAt) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", FURTHER_COUNSELLING_RECOMMENDED_TYPE);
    payload.put("topicId", topicId);
    payload.put("note", note);
    payload.put("recommendedBy", recommendedBy);
    payload.put("createdAt", createdAt.toString());
    return encode(payload);
  }

  /** Topic only: the old room learns nothing about the new conversation. */
  public static String duplicateStarted(long topicId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", TOPIC_DUPLICATE_STARTED_TYPE);
    payload.put("topicId", topicId);
    return encode(payload);
  }

  public static String duplicateEnded(long topicId) {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("type", TOPIC_DUPLICATE_ENDED_TYPE);
    payload.put("topicId", topicId);
    return encode(payload);
  }

  private static String encode(Map<String, Object> payload) {
    try {
      return SYSTEM_NOTIFICATION_PREFIX + JSON.writeValueAsString(payload);
    } catch (JsonProcessingException impossible) {
      throw new IllegalStateException("Cannot encode further-counselling notice", impossible);
    }
  }
}
