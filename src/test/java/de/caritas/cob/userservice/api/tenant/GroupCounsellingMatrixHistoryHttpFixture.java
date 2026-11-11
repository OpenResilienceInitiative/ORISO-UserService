package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.TestPropertySource;

/** Shared real HTTP fixture; only identity and external owner/Synapse HTTP are substituted. */
@TestPropertySource(
    properties = {
      "matrix.group.policy.enabled=true",
      "matrix.group.policy.token=synthetic-dedicated-membership-policy-credential",
      "matrix.group.participation-history.url=http://synapse.synthetic/oriso-internal/group-participation-history"
    })
abstract class GroupCounsellingMatrixHistoryHttpFixture extends GroupCounsellingDpaHttpFixture {
  @Autowired UserChatRepository userChats;

  @Autowired UserRepository users;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("matrixGroupHistoryRestTemplate")
  protected org.springframework.web.client.RestTemplate historyTransport;

  protected org.springframework.test.web.client.MockRestServiceServer historyDownstream;

  @BeforeEach
  void authenticatedHistoryHttpFixture() {
    historyDownstream =
        org.springframework.test.web.client.MockRestServiceServer.bindTo(historyTransport).build();
    historyDownstream
        .expect(
            org.springframework.test.web.client.ExpectedCount.between(0, Integer.MAX_VALUE),
            org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
        .andRespond(this::additionalExternalResponse);
  }

  protected final ObjectMapper json = new ObjectMapper();
  protected final AtomicInteger historyReads = new AtomicInteger();
  protected final AtomicInteger memberReads = new AtomicInteger();
  protected int historyUnavailableAtRead;
  protected String historyBodyOverride;
  protected boolean historyNon200;
  protected boolean matrixInviteDenied;
  protected boolean matrixMemberBanned;
  protected boolean snapshotUnavailable;
  protected int snapshotCount;
  protected boolean forgetUnavailable;
  protected final java.util.List<String> forgottenRooms = new java.util.ArrayList<>();
  protected int purgeCount;
  protected int nativeJoinWrites;
  protected final java.util.List<String> copiedActors = new java.util.ArrayList<>();
  protected MediaType historyContentType = MediaType.APPLICATION_JSON;
  protected final java.util.Set<String> notCommencedActors = new java.util.HashSet<>();
  protected final AtomicInteger historyBytesRead = new AtomicInteger();
  protected String participation = "COMMENCED";
  protected final java.util.Map<String, String> roomParticipation = new java.util.HashMap<>();
  protected String members = "{\"members\":[]}";

  @AfterEach
  void verifyHistoryHttpFixture() {
    historyDownstream.verify();
  }

  protected HttpResponse<String> nativeRequest(
      String method, String path, String token, String body) throws Exception {
    var builder =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json")
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("X-Oriso-Group-Policy-Token", token);
    return java.net.http.HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected HttpResponse<String> nativePolicy(String token, String body) throws Exception {
    var builder =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/internal/matrix/group-join-policy"))
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body));
    if (token != null) builder.header("X-Oriso-Group-Policy-Token", token);
    return java.net.http.HttpClient.newHttpClient()
        .send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  protected Chat activeChat() {
    var chat = storedChat(ConversationType.SELF_HELP);
    chat.setActive(true);
    return chats.save(chat);
  }

  protected User assignedUser(Chat chat) {
    var user = fixtures.adviceSeeker(42L);
    user.setMatrixUserId("@synthetic-former:synthetic.oriso.test");
    Tenants.in(42L, () -> users.save(user));
    userChats.save(UserChat.builder().user(user).chat(chat).build());
    return user;
  }

  protected HttpResponse<String> join(Chat chat, User user) throws Exception {
    return request(
        "PUT",
        "/users/chat/" + chat.getId() + "/join",
        user.getUserId(),
        user.getUsername(),
        "user",
        user.getTenantId(),
        "");
  }

  @Override
  protected ClientHttpResponse additionalExternalResponse(ClientHttpRequest request)
      throws IOException {
    String path = request.getURI().getPath();
    if (path.equals("/oriso-internal/group-participation-history")) {
      historyReads.incrementAndGet();
      assertEquals(
          "synthetic-dedicated-membership-policy-credential",
          request.getHeaders().getFirst("X-Oriso-Group-Policy-Token"));
      if (historyNon200) {
        var raw =
            withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                .body("synthetic-private-history-detail".repeat(500))
                .createResponse(request);
        return new ClientHttpResponse() {
          public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return raw.getStatusCode();
          }

          public String getStatusText() throws IOException {
            return raw.getStatusText();
          }

          public org.springframework.http.HttpHeaders getHeaders() {
            return raw.getHeaders();
          }

          public java.io.InputStream getBody() throws IOException {
            return new java.io.FilterInputStream(raw.getBody()) {
              public int read(byte[] bytes, int offset, int length) throws IOException {
                int count = super.read(bytes, offset, length);
                if (count > 0) historyBytesRead.addAndGet(count);
                return count;
              }
            };
          }

          public void close() {
            raw.close();
          }
        };
      }
      if (historyBodyOverride != null)
        return withSuccess(historyBodyOverride, historyContentType).createResponse(request);
      assertNull(request.getHeaders().getFirst("Authorization"));
      var asked =
          json.readTree(
              ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                  .getBodyAsString());
      if (asked.path("contractVersion").intValue() == 2) {
        var operation = asked.path("operation").textValue();
        assertTrue(java.util.Set.of("SNAPSHOT_ROOM", "FORGET_ROOM").contains(operation));
        if (operation.equals("FORGET_ROOM")) {
          forgottenRooms.add(asked.path("roomId").textValue());
          if (forgetUnavailable)
            return withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
                .createResponse(request);
        } else snapshotCount++;
        if (snapshotUnavailable)
          return withStatus(org.springframework.http.HttpStatus.BAD_GATEWAY)
              .createResponse(request);
        return withSuccess(
                json.writeValueAsString(
                    Map.of(
                        "contractVersion",
                        2,
                        "roomId",
                        asked.path("roomId").textValue(),
                        "complete",
                        true)),
                MediaType.APPLICATION_JSON)
            .createResponse(request);
      }
      assertEquals(1, asked.path("contractVersion").intValue());
      assertEquals("synapse-1.158.0-applied-membership-v1", asked.path("schemaVersion").asText());
      return withSuccess(
              json.writeValueAsString(
                  Map.of(
                      "contractVersion",
                      1,
                      "schemaVersion",
                      "synapse-1.158.0-applied-membership-v1",
                      "roomId",
                      asked.get("roomId").asText(),
                      "matrixUserId",
                      asked.get("matrixUserId").asText(),
                      "participation",
                      historyReads.get() == historyUnavailableAtRead
                          ? "UNAVAILABLE"
                          : notCommencedActors.contains(asked.get("matrixUserId").asText())
                              ? "NOT_COMMENCED"
                              : roomParticipation.getOrDefault(
                                  asked.get("roomId").asText(), participation))),
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    if ((path.startsWith("/_synapse/admin/v1/rooms/")
            || path.startsWith("/_synapse/admin/v2/rooms/"))
        && request.getMethod() == org.springframework.http.HttpMethod.DELETE) {
      purgeCount++;
      return withSuccess("{}", MediaType.APPLICATION_JSON).createResponse(request);
    }
    if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/members")) {
      memberReads.incrementAndGet();
      return withSuccess(members, MediaType.APPLICATION_JSON).createResponse(request);
    }
    if (path.startsWith("/_synapse/admin/v1/rooms/") && path.endsWith("/state")) {
      var membership = matrixMemberBanned ? "ban" : "leave";
      return withSuccess(
              "{\"state\":[{\"type\":\"m.room.member\",\"state_key\":"
                  + "\"@synthetic-former:synthetic.oriso.test\",\"content\":{\"membership\":\""
                  + membership
                  + "\"}}]}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    if (path.endsWith("/invite")
        || path.endsWith("/join")
        || path.contains("/join/")
        || path.endsWith("/leave")) {
      matrixWrites.incrementAndGet();
      if (path.endsWith("/join") || path.contains("/join/")) nativeJoinWrites++;
      if (path.endsWith("/invite")) {
        var invited =
            json.readTree(
                ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                    .getBodyAsString());
        copiedActors.add(invited.path("user_id").textValue());
      }
      if (matrixInviteDenied && path.endsWith("/invite"))
        return withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
            .body("{\"errcode\":\"M_FORBIDDEN\",\"error\":\"User is banned\"}")
            .createResponse(request);
      return withSuccess(
              "{\"room_id\":\"!synthetic-stored:synthetic.oriso.test\"}",
              MediaType.APPLICATION_JSON)
          .createResponse(request);
    }
    return super.additionalExternalResponse(request);
  }
}
