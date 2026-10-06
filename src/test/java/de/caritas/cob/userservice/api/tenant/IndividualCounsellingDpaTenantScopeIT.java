package de.caritas.cob.userservice.api.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Public creation and readback with the real tenant filter prove the technical read scope ends. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testing")
@TestPropertySource(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true",
      "feature.topics.enabled=false",
      "feature.demographics.enabled=false",
      "logging.level.root=WARN"
    })
@Import({TenantFixtures.class, IndividualCounsellingDpaGateIT.IncomingIdentityFixture.class})
class IndividualCounsellingDpaTenantScopeIT {
  @LocalServerPort private int port;
  @Autowired private TenantFixtures fixtures;
  @Autowired private SessionRepository sessions;
  @Autowired private de.caritas.cob.userservice.api.port.out.UserRepository users;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository consultantTopics;

  @Autowired
  @Qualifier("restTemplate")
  private RestTemplate transport;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private KeycloakService identity;
  @MockitoBean private MatrixSynapseService matrix;
  private MockRestServiceServer downstream;
  private Long createdSessionId;
  private Long consultantTopicId;

  @AfterEach
  void cleanUp() {
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    if (consultantTopicId != null) consultantTopics.deleteById(consultantTopicId);
    if (createdSessionId != null) sessions.deleteById(createdSessionId);
    fixtures.removeAll();
    TenantContext.clear();
    if (downstream != null) downstream.reset();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"41,200", "42,404"})
  void registeredTopicAssignmentAcceptsOnlyAnEnquiryInTheCallersTenant(
      int sessionTenant, int expectedStatus) throws Exception {
    when(identity.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("synthetic-service-token", 60, 60, "synthetic-refresh"));
    when(matrix.loginUser(anyString(), anyString())).thenReturn("synthetic-matrix-token");
    when(matrix.loginAsUserAccessToken(anyString())).thenReturn("synthetic-matrix-token");
    when(matrix.joinRoom(anyString(), anyString())).thenReturn(true);
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.manyTimes(), anything())
        .andRespond(
            request -> {
              var path = request.getURI().getPath();
              String body;
              if (path.endsWith("/agencies/420")) {
                body =
                    "[{\"id\":420,\"tenantId\":"
                        + sessionTenant
                        + ",\"consultingType\":1,\"teamAgency\":false,\"topicIds\":[7]}]";
              } else if (path.endsWith("/internal/agencies/420/matrix-service-account")) {
                body =
                    "{\"matrixUserId\":\"@synthetic-agency:synthetic.oriso.test\",\"matrixPassword\":\"synthetic-fixture\"}";
              } else if (path.equals("/tenant/public/id/41")) {
                body = "{\"id\":41,\"subdomain\":\"synthetic41\"}";
              } else if (path.equals("/tenantadmin/42/dpa/gate")) {
                body = "{\"dpaPublished\":true,\"dpaSigned\":true}";
              } else if (path.equals("/tenantadmin/41/dpa/gate")) {
                body = "{\"dpaPublished\":true,\"dpaSigned\":" + (sessionTenant == 41) + "}";
              } else {
                throw new AssertionError("Unexpected external operation: " + path);
              }
              return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
            });
    var routedAsker = fixtures.adviceSeeker(sessionTenant);
    routedAsker.setMatrixUserId("@synthetic-asker:synthetic.oriso.test");
    Tenants.in((long) sessionTenant, () -> users.save(routedAsker));
    var routedSession = fixtures.session(routedAsker, 420, null);
    routedSession.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW);
    routedSession.setMainTopicId(7L);
    routedSession.setPostcode("00000");
    routedSession.setMatrixRoomId("!synthetic-session:synthetic.oriso.test");
    Tenants.in((long) sessionTenant, () -> sessions.save(routedSession));
    createdSessionId = routedSession.getId();
    var consultant = fixtures.consultant(41, 410L);
    consultantTopicId =
        consultantTopics
            .save(
                de.caritas.cob.userservice.api.model.ConsultantTopic.builder()
                    .consultant(consultant)
                    .topicId(7L)
                    .build())
            .getId();
    when(jwtDecoder.decode("synthetic-token"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject(consultant.getId())
                .claim("username", consultant.getUsername())
                .claim("tenantId", 41L)
                .claim("realm_access", Map.of("roles", List.of("consultant")))
                .build());
    var result =
        send(
            "PUT",
            "/users/sessions/" + routedSession.getId() + "/consultant/" + consultant.getId(),
            "");
    assertEquals(expectedStatus, result.statusCode(), result.body());
  }

  @Test
  void firstEnquiryPreflightCannotLoadAnOwnedSessionFromAnotherTenantOrReadItsDpa()
      throws Exception {
    var ownerReads = new java.util.concurrent.atomic.AtomicInteger();
    when(identity.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("synthetic-service-token", 60, 60, "synthetic-refresh"));
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.manyTimes(), anything())
        .andRespond(
            request -> {
              var path = request.getURI().getPath();
              String body;
              if (path.equals("/tenant/public/single") || path.equals("/tenant/public/id/41")) {
                body = "{\"id\":41,\"subdomain\":\"synthetic41\"}";
              } else if (path.endsWith("/agencies/420")) {
                body = "[{\"id\":420,\"tenantId\":42,\"consultingType\":1}]";
              } else if (path.equals("/tenantadmin/42/dpa/gate")) {
                ownerReads.incrementAndGet();
                body = "{\"dpaPublished\":true,\"dpaSigned\":true}";
              } else {
                throw new AssertionError("Unexpected external operation: " + path);
              }
              return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
            });
    var caller = fixtures.adviceSeeker(41);
    // The asker id matches, so only the real tenant boundary can hide this foreign row.
    var foreignSession = new de.caritas.cob.userservice.api.model.Session();
    foreignSession.setUser(caller);
    foreignSession.setTenantId(42L);
    foreignSession.setAgencyId(420L);
    foreignSession.setConsultingTypeId(1);
    foreignSession.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    foreignSession.setRegistrationType(
        de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED);
    foreignSession.setPostcode("12345");
    foreignSession.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    foreignSession.setTeamSession(false);
    foreignSession.setIsConsultantDirectlySet(false);
    foreignSession.setMatrixRoomId("!synthetic-foreign-initial:synthetic.oriso.test");
    createdSessionId = Tenants.in(42L, () -> sessions.save(foreignSession)).getId();
    when(jwtDecoder.decode("synthetic-token"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject(caller.getUserId())
                .claim("username", caller.getUsername())
                .claim("tenantId", 41L)
                .claim("realm_access", Map.of("roles", List.of("user")))
                .build());
    var result = send("GET", "/users/sessions/" + createdSessionId + "/enquiry/permission", "");
    assertEquals(400, result.statusCode(), result.body());
    assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void createdSessionRemainsAccessibleInItsServingTenantAfterTheTechnicalOwnerRead()
      throws Exception {
    when(identity.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("synthetic-service-token", 60, 60, "synthetic-refresh"));
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.manyTimes(), anything())
        .andRespond(
            request -> {
              var path = request.getURI().getPath();
              String body;
              if (path.endsWith("/agencies/410")) {
                body =
                    "[{\"id\":410,\"tenantId\":41,\"consultingType\":1,\"teamAgency\":false,\"topicIds\":[],\"name\":\"Synthetic centre\"}]";
              } else if (path.endsWith("/consultingtypes/1/extended")) {
                body = "{\"id\":1,\"registration\":{},\"groupChat\":{\"isGroupChat\":false}}";
              } else if (path.equals("/tenant/public/id/41")) {
                body = "{\"id\":41,\"subdomain\":\"synthetic41\"}";
              } else if (path.equals("/tenantadmin/41/dpa/gate")) {
                assertEquals("0", request.getHeaders().getFirst("tenantId"));
                body = "{\"dpaPublished\":true,\"dpaSigned\":true}";
              } else {
                throw new AssertionError("Unexpected external operation: " + path);
              }
              return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
            });
    var asker = fixtures.adviceSeeker(41);
    when(jwtDecoder.decode("synthetic-token"))
        .thenReturn(
            Jwt.withTokenValue("synthetic-token")
                .header("alg", "none")
                .subject(asker.getUserId())
                .claim("username", asker.getUsername())
                .claim("tenantId", 41L)
                .claim("realm_access", Map.of("roles", List.of("user")))
                .build());
    var created =
        send(
            "POST",
            "/users/askers/session/new",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    assertEquals(201, created.statusCode(), created.body());
    createdSessionId =
        tools.jackson.databind.json.JsonMapper.builder()
            .build()
            .readTree(created.body())
            .get("sessionId")
            .asLong();
    var readback = send("GET", "/users/sessions/room/" + createdSessionId, "");
    assertEquals(200, readback.statusCode(), readback.body());
    assertTrue(readback.body().contains(createdSessionId.toString()), readback.body());
  }

  private HttpResponse<String> send(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer synthetic-token")
            .header("tenantId", "41")
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }
}
