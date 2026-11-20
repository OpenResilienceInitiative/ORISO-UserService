package de.caritas.cob.userservice.api.service.matrix;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;

/** Distinct source identities: a group Chat is never represented as a fabricated Session. */
@Getter
public final class MatrixCallConversation {
  private final String matrixRoomId;
  private final Long tenantId;
  private final Long sessionId;
  private final Long chatId;

  public MatrixCallConversation(String room, Long tenant, Long session, Long chat) {
    if (room == null || room.isBlank() || tenant == null || (session == null) == (chat == null)) {
      throw new IllegalArgumentException("A call requires exactly one trusted conversation source");
    }
    matrixRoomId = room;
    tenantId = tenant;
    sessionId = session;
    chatId = chat;
  }

  public Map<String, Object> notificationParams() {
    var params = new LinkedHashMap<String, Object>();
    params.put("roomRef", matrixRoomId);
    params.put(
        sessionId != null ? "sessionId" : "seriesId", sessionId != null ? sessionId : chatId);
    return params;
  }

  public String actionPath(boolean consultant) {
    String base = consultant ? "/sessions/consultant/sessionView/" : "/sessions/user/view/";
    if (sessionId != null) return base + matrixRoomId + "/" + sessionId;
    String room =
        java.net.URLEncoder.encode(matrixRoomId, StandardCharsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!");
    return base + room + "/" + chatId;
  }
}
