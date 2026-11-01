package de.caritas.cob.userservice.api.service.matrixgroup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.Chat;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Reads trusted applied-membership history; never grants ordinary access and never caches a miss.
 */
@Service
public class MatrixGroupParticipationHistory {
  private final GroupMatrixPolicySettings settings;
  private final RestTemplate transport;
  private final ObjectMapper json;
  private final ChatMatrixRoomHistory rooms;

  public MatrixGroupParticipationHistory(
      GroupMatrixPolicySettings settings,
      @Qualifier("matrixGroupHistoryRestTemplate") RestTemplate transport,
      ObjectMapper json,
      ChatMatrixRoomHistory rooms) {
    this.settings = settings;
    this.transport = transport;
    this.json = json;
    this.rooms = rooms;
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public record Participation(boolean commenced, boolean historyComplete) {}

  public Participation classify(Chat chat, String actor) {
    if (!settings.enabled()) return new Participation(false, true);
    if (!GroupMatrixPolicySettings.validActor(actor) || chat.getChatOwner() == null)
      throw unavailable();
    try {
      var context =
          rooms
              .context(chat.getMatrixRoomId())
              .orElseThrow(MatrixGroupParticipationHistory::unavailable);
      var committed = context.series();
      if (!context.applicable()
          || !Objects.equals(chat.getId(), committed.getId())
          || !Objects.equals(chat.getChatOwner().getId(), committed.getChatOwner().getId())
          || !Objects.equals(
              chat.getChatOwner().getTenantId(), committed.getChatOwner().getTenantId()))
        throw unavailable();
      for (var room : context.roomIds()) {
        if (commencedInRoom(room, actor)) return new Participation(true, context.historyComplete());
      }
      return new Participation(false, context.historyComplete());
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  private boolean commencedInRoom(String room, String actor) throws Exception {
    var payload =
        json.writeValueAsBytes(
            Map.of(
                "contractVersion",
                1,
                "schemaVersion",
                GroupMatrixPolicySettings.SCHEMA,
                "roomId",
                room,
                "matrixUserId",
                actor));
    return transport.execute(
        settings.historyUri(),
        HttpMethod.POST,
        request -> {
          request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
          request.getHeaders().set(GroupMatrixPolicySettings.TOKEN_HEADER, settings.token());
          request.getBody().write(payload);
        },
        response -> {
          if (response.getStatusCode().value() != 200
              || response.getHeaders().getContentType() == null
              || !MediaType.APPLICATION_JSON.isCompatibleWith(
                  response.getHeaders().getContentType())) throw unavailable();
          var bytes = response.getBody().readNBytes(4097);
          if (bytes.length > 4096) throw unavailable();
          var body =
              json.reader()
                  .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                  .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                  .readTree(bytes);
          if (body == null
              || !body.isObject()
              || body.size() != 5
              || !body.path("contractVersion").isIntegralNumber()
              || !body.path("contractVersion").canConvertToInt()
              || body.path("contractVersion").intValue() != 1
              || !GroupMatrixPolicySettings.SCHEMA.equals(body.path("schemaVersion").textValue())
              || !room.equals(body.path("roomId").textValue())
              || !actor.equals(body.path("matrixUserId").textValue())) throw unavailable();
          return switch (body.path("participation").textValue()) {
            case "COMMENCED" -> true;
            case "NOT_COMMENCED" -> false;
            default -> throw unavailable();
          };
        });
  }

  /** A durable complete applied-JOIN snapshot is required before recurring room purge. */
  public void snapshotRoom(String room) {
    roomOperation("SNAPSHOT_ROOM", room);
  }

  public void forgetRoom(String room) {
    roomOperation("FORGET_ROOM", room);
  }

  private void roomOperation(String operation, String room) {
    if (!settings.enabled()) return;
    if (!GroupMatrixPolicySettings.validRoom(room)) throw unavailable();
    try {
      var payload =
          json.writeValueAsBytes(
              Map.of("contractVersion", 2, "operation", operation, "roomId", room));
      transport.execute(
          settings.historyUri(),
          HttpMethod.POST,
          request -> {
            request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.getHeaders().set(GroupMatrixPolicySettings.TOKEN_HEADER, settings.token());
            request.getBody().write(payload);
          },
          response -> {
            if (response.getStatusCode().value() != 200
                || response.getHeaders().getContentType() == null
                || !MediaType.APPLICATION_JSON.isCompatibleWith(
                    response.getHeaders().getContentType())) throw unavailable();
            var bytes = response.getBody().readNBytes(4097);
            if (bytes.length > 4096) throw unavailable();
            var body =
                json.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(bytes);
            if (body == null
                || !body.isObject()
                || body.size() != 3
                || !body.path("contractVersion").isIntegralNumber()
                || !body.path("contractVersion").canConvertToInt()
                || body.path("contractVersion").intValue() != 2
                || !room.equals(body.path("roomId").textValue())
                || !body.path("complete").isBoolean()
                || !body.path("complete").booleanValue()) throw unavailable();
            return null;
          });
    } catch (Exception failure) {
      throw unavailable();
    }
  }

  public static CustomValidationHttpStatusException unavailable() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.DPA_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
  }
}
