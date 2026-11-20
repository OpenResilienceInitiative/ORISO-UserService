package de.caritas.cob.userservice.api.adapters.web.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.service.matrixgroup.GroupMatrixPolicySettings;
import de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupJoinPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MatrixGroupJoinPolicyController {
  private final GroupMatrixPolicySettings settings;
  private final MatrixGroupJoinPolicyService policy;
  private final ObjectMapper json;

  @PostMapping("/internal/matrix/group-join-policy")
  public ResponseEntity<Map<String, Object>> resolve(
      @RequestHeader(value = GroupMatrixPolicySettings.TOKEN_HEADER, required = false) String token,
      HttpServletRequest request) {
    if (!settings.authenticates(token)) return ResponseEntity.status(401).build();
    JsonNode body;
    try {
      if (request.getContentType() == null
          || !MediaType.APPLICATION_JSON.isCompatibleWith(
              MediaType.parseMediaType(request.getContentType())))
        return ResponseEntity.badRequest().build();
      var bytes = request.getInputStream().readNBytes(4097);
      if (bytes.length > 4096) return ResponseEntity.badRequest().build();
      body =
          json.reader()
              .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
              .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .readTree(bytes);
      if (body == null
          || !body.isObject()
          || !body.path("contractVersion").isIntegralNumber()
          || !body.path("contractVersion").canConvertToInt()
          || !GroupMatrixPolicySettings.validRoom(body.path("roomId").textValue())
          || !GroupMatrixPolicySettings.validActor(body.path("matrixUserId").textValue()))
        return ResponseEntity.badRequest().build();
    } catch (Exception malformed) {
      return ResponseEntity.badRequest().build();
    }
    int version = body.path("contractVersion").intValue();
    boolean contextRequest =
        version == 2 && body.size() == 4 && "CONTEXT".equals(body.path("operation").textValue());
    if (!contextRequest && (version != 1 || body.size() != 3))
      return ResponseEntity.badRequest().build();
    var room = body.get("roomId").textValue();
    var actor = body.get("matrixUserId").textValue();
    if (contextRequest) {
      var context = policy.context(room);
      Map<String, Object> response =
          Map.of(
              "contractVersion",
              2,
              "roomId",
              room,
              "matrixUserId",
              actor,
              "applicable",
              context.map(c -> c.applicable()).orElse(false),
              "roomIds",
              context.map(c -> c.roomIds()).orElse(java.util.List.of()),
              "historyComplete",
              context.map(c -> c.historyComplete()).orElse(true));
      try {
        if (json.writeValueAsBytes(response).length > 65536) throw new IllegalArgumentException();
      } catch (Exception invalid) {
        throw de.caritas.cob.userservice.api.service.matrixgroup.MatrixGroupParticipationHistory
            .unavailable();
      }
      return ResponseEntity.ok(response);
    }
    var applicable = policy.applicableAndAllowed(room);
    return ResponseEntity.ok(
        Map.of(
            "contractVersion", 1, "roomId", room, "matrixUserId", actor, "applicable", applicable));
  }
}
