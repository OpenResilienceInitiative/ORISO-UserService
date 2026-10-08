package de.caritas.cob.userservice.api.adapters.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Actual client HTTP serialization and independent protocol-state readback. */
class MatrixRoomClientVerifiedClosureTest {
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(http);
  private final MatrixConfig config = mock(MatrixConfig.class);
  private final MatrixRoomClient rooms =
      new MatrixRoomClient(config, http, mock(LiveChatDiagnosticMetrics.class));
  private static final String BASE = "https://matrix.synthetic.test";
  private static final String ROOM = "!case:synthetic.test";
  private static final String OPERATOR = "@operator:synthetic.test";
  private static final String STATE =
      BASE + "/_matrix/client/v3/rooms/%21case%3Asynthetic.test/state";
  private static final String POWER =
      BASE + "/_matrix/client/r0/rooms/%21case%3Asynthetic.test/state/m.room.power_levels";

  @Test
  void verifiedClosureBlocksExplicitTimelineAndCallOverridesWithoutChangingPeople() {
    when(config.getApiUrl(anyString())).thenAnswer(c -> BASE + c.getArgument(0, String.class));
    server
        .expect(requestTo(BASE + "/_matrix/client/v3/account/whoami"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andRespond(
            withSuccess("{\"user_id\":\"@operator:synthetic.test\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(STATE))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(state(false), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(POWER))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.users['@operator:synthetic.test']").value(100))
        .andExpect(jsonPath("$.users['@human:synthetic.test']").value(60))
        .andExpect(jsonPath("$.events_default").value(61))
        .andExpect(jsonPath("$.events['m.room.message']").value(61))
        .andExpect(jsonPath("$.events['m.room.encrypted']").value(61))
        .andExpect(jsonPath("$.events['m.reaction']").value(61))
        .andExpect(jsonPath("$.events['m.room.redaction']").value(61))
        .andExpect(jsonPath("$.events['org.matrix.msc3401.call.member']").value(61))
        .andExpect(jsonPath("$.events['org.oriso.call.invite']").value(61))
        .andExpect(jsonPath("$.events['m.room.name']").value(50))
        .andExpect(jsonPath("$.state_default").value(50))
        .andRespond(withSuccess("{\"event_id\":\"$closed\"}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(STATE))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(state(true), MediaType.APPLICATION_JSON));

    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isTrue();
    server.verify();
  }

  @Test
  void equalPowerHumanLeavesClosurePendingWithoutDemotionOrWrite() {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(false)
                    .replace("\"@human:synthetic.test\":60", "\"@human:synthetic.test\":100"),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @Test
  void wrongAuthenticatedOperatorCannotReadOrRewriteRoomState() {
    identity("@other:synthetic.test");
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @Test
  void unverifiedExplicitReactionOverrideCannotProduceConfirmation() {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(withSuccess(state(false), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(POWER))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(true).replace("\"m.reaction\":61", "\"m.reaction\":0"),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @Test
  void versionTwelveAdditionalHumanCreatorCannotBeClosedThroughNumericPowerMap() {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(false)
                    .replace(
                        "\"room_version\":\"11\"",
                        "\"room_version\":\"12\",\"additional_creators\":[\"@human:synthetic.test\"]"),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @Test
  void unsupportedRoomVersionHasNoPermissionRewrite() {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(false).replace("\"room_version\":\"11\"", "\"room_version\":\"future\""),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @Test
  void versionTwelveTechnicalCreatorCanCloseWithoutInventingFiniteCreatorPower() {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(withSuccess(versionTwelve(false), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(POWER))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonPath("$.users['@operator:synthetic.test']").doesNotExist())
        .andExpect(jsonPath("$.users['@human:synthetic.test']").value(60))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(STATE))
        .andRespond(withSuccess(versionTwelve(true), MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isTrue();
    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"01", "+11"})
  void unknownVersionIdentifiersCannotBeInterpretedAsKnownVersions(String version) {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(false)
                    .replace("\"room_version\":\"11\"", "\"room_version\":\"" + version + "\""),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"m.room.encrypted", "m.call.member", "m.room.redaction"})
  void independentReadbackOfEveryContentPermissionIsRequired(String type) {
    identity(OPERATOR);
    server
        .expect(requestTo(STATE))
        .andRespond(withSuccess(state(false), MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(POWER))
        .andExpect(method(HttpMethod.PUT))
        .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    server
        .expect(requestTo(STATE))
        .andRespond(
            withSuccess(
                state(true).replace("\"" + type + "\":61", "\"" + type + "\":0"),
                MediaType.APPLICATION_JSON));
    assertThat(rooms.closeRoomForMessagesVerified(ROOM, OPERATOR, "synthetic-token")).isFalse();
    server.verify();
  }

  private String versionTwelve(boolean closed) {
    return state(closed)
        .replace("\"room_version\":\"11\"", "\"room_version\":\"12\"")
        .replace("\"@operator:synthetic.test\":100,", "");
  }

  private void identity(String userId) {
    when(config.getApiUrl(anyString())).thenAnswer(c -> BASE + c.getArgument(0, String.class));
    server
        .expect(requestTo(BASE + "/_matrix/client/v3/account/whoami"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"user_id\":\"" + userId + "\"}", MediaType.APPLICATION_JSON));
  }

  private String state(boolean closed) {
    int required = closed ? 61 : 0;
    return """
      [{"type":"m.room.create","state_key":"","sender":"@operator:synthetic.test","content":{"room_version":"11"}},
       {"type":"m.room.member","state_key":"@operator:synthetic.test","content":{"membership":"join"}},
       {"type":"m.room.member","state_key":"@human:synthetic.test","content":{"membership":"join"}},
       {"type":"m.room.power_levels","state_key":"","content":{
         "users":{"@operator:synthetic.test":100,"@human:synthetic.test":60},"users_default":0,"state_default":50,
         "events_default":%d,"events":{"m.call.member":%d,"m.room.name":50,"m.room.message":%d,"m.room.encrypted":%d,"m.reaction":%d,"m.room.redaction":%d,
         "org.matrix.msc3401.call.member":%d,"org.oriso.call.invite":%d}}}]
      """
        .formatted(required, required, required, required, required, required, required, required);
  }
}
