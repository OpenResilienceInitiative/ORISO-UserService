package de.caritas.cob.userservice.api.service.matrixgroup;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.facade.ChatConverter;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
  private final ChatRepository chats;

  public MatrixGroupParticipationHistory(
      GroupMatrixPolicySettings settings,
      @Qualifier("matrixGroupHistoryRestTemplate") RestTemplate transport,
      ObjectMapper json,
      ChatRepository chats) {
    this.settings = settings;
    this.transport = transport;
    this.json = json;
    this.chats = chats;
  }

  public boolean enabled() {
    return settings.enabled();
  }

  public boolean commenced(Chat chat, String actor) {
    if (!settings.enabled()) return false;
    var room = chat.getMatrixRoomId();
    if (chat.getId() == null
        || chat.getChatOwner() == null
        || chat.getChatOwner().getTenantId() == null
        || chat.getChatOwner().getTenantId() <= 0
        || !GroupMatrixPolicySettings.validRoom(room)
        || !GroupMatrixPolicySettings.validActor(actor)) throw unavailable();
    try {
      var mapped =
          TenantContext.supplyAcrossTenants(() -> chats.findByMatrixRoomIdIn(Set.of(room)));
      if (mapped.size() != 1) throw unavailable();
      var committed = mapped.getFirst();
      if (!chat.getId().equals(committed.getId())
          || committed.getCurrentOccurrenceIndex() != 0
          || ChatConverter.conversationTypeOf(committed) != ConversationType.SELF_HELP
          || committed.getChatOwner() == null
          || !Objects.equals(chat.getChatOwner().getId(), committed.getChatOwner().getId())
          || !Objects.equals(
              chat.getChatOwner().getTenantId(), committed.getChatOwner().getTenantId()))
        throw unavailable();
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
    } catch (Exception failure) {
      // Transport/JSON exceptions may contain the dedicated credential, room, actor or raw body.
      throw unavailable();
    }
  }

  public static CustomValidationHttpStatusException unavailable() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.DPA_POLICY_UNAVAILABLE, HttpStatus.BAD_GATEWAY);
  }
}
