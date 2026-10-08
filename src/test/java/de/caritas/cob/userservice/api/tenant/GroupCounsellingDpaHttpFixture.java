package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ChatAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Shared real HTTP/security/repositories; only incoming identity and outgoing HTTP fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testing")
@TestPropertySource(
    properties = {
      "matrix.adminUsername=synthetic-matrix-admin",
      "matrix.adminPassword=synthetic-fixture",
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "feature.topics.enabled=false",
      "feature.demographics.enabled=false",
      "logging.level.root=WARN",
      "logging.level.org.springframework.web=WARN",
      "logging.level.org.springframework.web.servlet.mvc.method.annotation=WARN"
    })
@Import({TenantFixtures.class, IndividualCounsellingDpaGateIT.IncomingIdentityFixture.class})
abstract class GroupCounsellingDpaHttpFixture {
  protected static final long OWNER = 41L;
  protected static final long AGENCY = 410L;
  protected static final String EXPIRED =
      """
      {"dpaPublished":true,"dpaSigned":false,"dpaStatus":"OUTDATED",
       "currentDpaVersion":"v2","signingDeadlineAt":"2026-01-01T00:00:00Z",
       "renewalGraceActive":false,"newCounsellingAllowed":false}
      """;
  @LocalServerPort protected int port;
  @Autowired protected TenantFixtures fixtures;
  @Autowired protected ChatRepository chats;
  @Autowired protected ChatAgencyRepository agencies;
  @Autowired protected GroupChatParticipantRepository participants;
  @Autowired protected JdbcTemplate database;

  @Autowired
  @Qualifier("restTemplate")
  protected RestTemplate transport;

  @MockitoBean protected JwtDecoder jwtDecoder;
  protected Consultant consultant;
  protected MockRestServiceServer downstream;
  protected String gate = EXPIRED;
  protected int ownerStatus = 200;
  protected long agencyTenant = OWNER;
  protected final AtomicInteger recipientOwnerReads = new AtomicInteger();
  protected boolean systemUserExisted;
  protected final AtomicInteger ownerReads = new AtomicInteger();
  protected final AtomicInteger matrixWrites = new AtomicInteger();

  @BeforeEach
  void externalHttpFixtures() {
    consultant = fixtures.consultant(OWNER, AGENCY);
    systemUserExisted =
        database.queryForObject(
                "SELECT COUNT(*) FROM user WHERE user_id = 'group-chat-system-41'", Integer.class)
            > 0;
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.between(0, Integer.MAX_VALUE), anything())
        .andRespond(
            request -> {
              String path = request.getURI().getPath();
              if (path.endsWith("/token")) {
                return withSuccess(
                        "{\"access_token\":\"synthetic-service-token\",\"expires_in\":60,\"refresh_expires_in\":60,\"refresh_token\":\"synthetic-refresh\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.endsWith("/logout")) return withNoContent().createResponse(request);
              if (path.equals("/tenant/public/single")
                  || path.equals("/tenant/public/id/41")
                  || path.equals("/tenant/public/id/42")) {
                return withSuccess(
                        "{\"id\":"
                            + (path.endsWith("/42") ? 42 : 41)
                            + ",\"settings\":{\"featureGroupChatV2Enabled\":true}}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.endsWith("/agencies/410")) {
                return withSuccess(
                        "[{\"id\":410,\"tenantId\":"
                            + agencyTenant
                            + ",\"consultingType\":1,\"teamAgency\":false,\"topicIds\":[],\"settings\":{\"featureGroupChatV2Enabled\":true,\"featureSelfHelpGroupsEnabled\":true,\"featureInternalGroupChatEnabled\":true}}]",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/tenantadmin/41/dpa/gate")) {
                ownerReads.incrementAndGet();
                assertEquals("0", request.getHeaders().getFirst("tenantId"));
                assertEquals(
                    "Bearer synthetic-service-token",
                    request.getHeaders().getFirst("Authorization"));
                return ownerStatus == 200
                    ? withSuccess(gate, MediaType.APPLICATION_JSON).createResponse(request)
                    : withStatus(HttpStatus.valueOf(ownerStatus))
                        .body("synthetic-owner-private-detail")
                        .createResponse(request);
              }
              if (path.equals("/tenantadmin/42/dpa/gate")) {
                recipientOwnerReads.incrementAndGet();
                return withSuccess(
                        "{\"dpaPublished\":true,\"dpaSigned\":true}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/_matrix/client/r0/login")
                  || (path.startsWith("/_synapse/admin/v1/users/") && path.endsWith("/login"))) {
                return withSuccess(
                        "{\"access_token\":\"synthetic-matrix-token\"}", MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/_matrix/client/r0/createRoom")) {
                matrixWrites.incrementAndGet();
                return withSuccess(
                        "{\"room_id\":\"!synthetic-created:synthetic.oriso.test\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              return additionalExternalResponse(request);
            });
  }

  @AfterEach
  void removeOwnedRows() {
    Tenants.acrossAll(
        () -> {
          for (var chat : chats.findByChatOwner(consultant)) {
            database.update(
                "DELETE FROM event_notification WHERE deduplication_key LIKE ?",
                "group-chat:%:" + chat.getId() + ":%");
            database.update("DELETE FROM user_chat WHERE chat_id = ?", chat.getId());
            database.update("DELETE FROM chat_agency WHERE chat_id = ?", chat.getId());
            database.update("DELETE FROM group_chat_participant WHERE series_id = ?", chat.getId());
            chats.deleteById(chat.getId());
          }
          database.update("DELETE FROM session WHERE consultant_id = ?", consultant.getId());
          if (!systemUserExisted)
            database.update("DELETE FROM user WHERE user_id = ?", "group-chat-system-41");
        });
    fixtures.removeAll();
    TenantContext.clear();
    downstream.verify();
  }

  protected HttpResponse<String> create(boolean external, String path) throws Exception {
    return request(
        "POST",
        path,
        consultant.getId(),
        consultant.getUsername(),
        "consultant",
        OWNER,
        "{\"topic\":\"Synthetic AVV group\",\"agencyId\":410,\"startDate\":\"2999-01-01\",\"startTime\":\"12:00\",\"duration\":60,\"repetitive\":false,"
            + (external ? "\"repeatCount\":1," : "")
            + "\"timezone\":\"UTC\",\"modality\":\"TEXT\",\"consultantIds\":[]}");
  }

  protected Chat storedChat(ConversationType type) {
    var start = java.time.LocalDateTime.of(2999, 1, 1, 12, 0);
    var chat =
        chats.save(
            Chat.builder()
                .topic("Synthetic AVV group")
                .createDate(java.time.LocalDateTime.now())
                .updateDate(java.time.LocalDateTime.now())
                .consultingTypeId(1)
                .chatOwner(consultant)
                .initialStartDate(start)
                .startDate(start)
                .duration(60)
                .conversationType(type)
                .matrixRoomId("!synthetic-stored:synthetic.oriso.test")
                .active(false)
                .build());
    agencies.save(new ChatAgency(chat, AGENCY));
    participants.save(
        GroupChatParticipant.builder()
            .seriesId(chat.getId())
            .chatId(chat.getId())
            .consultantId(consultant.getId())
            .role(GroupChatParticipant.ParticipantRole.OWNER)
            .build());
    return chat;
  }

  protected HttpResponse<String> start(Chat chat, Consultant caller) throws Exception {
    return request(
        "PUT",
        "/users/chat/" + chat.getId() + "/start",
        caller.getId(),
        caller.getUsername(),
        "consultant",
        caller.getTenantId(),
        "");
  }

  protected HttpResponse<String> request(
      String method, String path, String id, String username, String role, long tenant, String body)
      throws Exception {
    var token =
        Jwt.withTokenValue("synthetic-token")
            .header("alg", "none")
            .subject(id)
            .claim("username", username)
            .claim("tenantId", tenant)
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    when(jwtDecoder.decode("synthetic-token")).thenReturn(token);
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer synthetic-token")
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  /** Extension point for a route's outgoing Matrix/HTTP responses, never an internal mock. */
  protected org.springframework.http.client.ClientHttpResponse additionalExternalResponse(
      org.springframework.http.client.ClientHttpRequest request) throws java.io.IOException {
    throw new AssertionError("Unexpected external HTTP path: " + request.getURI().getPath());
  }
}
