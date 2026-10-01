package de.caritas.cob.userservice.api.tenant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
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

/** Public HTTP requests use the real permission checks, counselling facades and H2 repositories. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("testing")
@TestPropertySource(
    properties = {
      "feature.topics.enabled=false",
      "feature.demographics.enabled=false",
      "logging.level.root=WARN",
      "logging.level.org.springframework.web=WARN",
      "logging.level.org.springframework.web.servlet.mvc.method.annotation=WARN"
    })
@Import({TenantFixtures.class, IndividualCounsellingDpaGateIT.IncomingIdentityFixture.class})
class IndividualCounsellingDpaGateIT {
  @org.springframework.boot.test.context.TestConfiguration
  static class IncomingIdentityFixture {
    // AppConfig's broad scan also discovers TenantServiceTest.CacheTestConfig's stub.
    // Select the real external adapter for these public API journeys.
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    de.caritas.cob.userservice.api.admin.service.tenant.TenantService realTenantReader(
        de.caritas.cob.userservice.api.config.apiclient.TenantServiceApiControllerFactory factory,
        org.springframework.cache.CacheManager cacheManager) {
      return new de.caritas.cob.userservice.api.admin.service.tenant.TenantService(
          factory, cacheManager);
    }

    // The stock factory's injected servlet request loses the caller in this synthetic-JWT
    // context. Keep production JWT mapping and permissions; supply the dispatched request.
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    @org.springframework.context.annotation.Scope(
        value = "request",
        proxyMode = org.springframework.context.annotation.ScopedProxyMode.TARGET_CLASS)
    de.caritas.cob.userservice.api.helper.AuthenticatedUser mappedCaller() {
      var request =
          ((org.springframework.web.context.request.ServletRequestAttributes)
                  org.springframework.web.context.request.RequestContextHolder
                      .currentRequestAttributes())
              .getRequest();
      var config = new de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig();
      config.setPrincipalAttribute("preferred_username");
      return config.authenticatedUser(
          request, new de.caritas.cob.userservice.api.helper.UsernameTranscoder());
    }
  }

  private static final long SERVING_TENANT = 41L;
  private static final long AGENCY = 410L;

  @org.springframework.boot.test.web.server.LocalServerPort private int port;
  @Autowired private de.caritas.cob.userservice.api.port.out.SessionRepository sessions;
  private de.caritas.cob.userservice.api.model.User asker;
  @Autowired private TenantFixtures fixtures;

  @Autowired
  @Qualifier("restTemplate")
  private RestTemplate transport;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private KeycloakService identityAuthentication;
  @MockitoBean private de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix;
  @Autowired private de.caritas.cob.userservice.api.port.out.UserRepository users;
  @Autowired private de.caritas.cob.userservice.api.port.out.ConsultantRepository consultants;
  private MockRestServiceServer downstream;
  private String anonymousIdentityId;
  private boolean refuseIdentityProvisioning;
  private boolean requireTechnicalOwnerHeader;
  private long agencyTenant = SERVING_TENANT;
  private int ownerStatus = 200;
  private final java.util.concurrent.atomic.AtomicInteger ownerReads =
      new java.util.concurrent.atomic.AtomicInteger();
  private String otherTenantGate = "{\"dpaPublished\":true,\"dpaSigned\":false}";
  private String gate =
      """
      {"dpaPublished":true,"dpaSigned":false,"dpaStatus":"OUTDATED",
       "currentDpaVersion":"v2","signingDeadlineAt":"2026-01-01T00:00:00Z",
       "renewalGraceActive":false,"newCounsellingAllowed":false}
      """;

  @BeforeEach
  void externalServices() throws Exception {
    org.mockito.Mockito.when(
            identityAuthentication.login(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            new de.caritas.cob.userservice.api.port.out.IdentityLogin(
                "synthetic-service-token", 60, 60, "synthetic-refresh"));
    when(matrix.loginAsUserAccessToken(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("synthetic-matrix-token");
    when(matrix.getRoomEvent(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("$encrypted-enquiry"),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            java.util.Optional.of(
                Map.of(
                    "event_id",
                    "$encrypted-enquiry",
                    "type",
                    "m.room.encrypted",
                    "sender",
                    "@synthetic-asker:synthetic.oriso.test")));
    when(matrix.loginUser(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn("synthetic-matrix-token");
    when(matrix.joinRoom(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    anonymousIdentityId = java.util.UUID.randomUUID().toString();
    when(identityAuthentication.isUsernameAvailable(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(identityAuthentication.createUser(
            org.mockito.ArgumentMatchers.any(
                de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.class)))
        .thenAnswer(
            call -> {
              if (refuseIdentityProvisioning)
                throw new IllegalStateException("The external fixture refuses provisioning writes");
              return new de.caritas.cob.userservice.api.port.out.identity.CreatedIdentity(
                  anonymousIdentityId);
            });
    when(identityAuthentication.updateDummyEmail(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(
                de.caritas.cob.userservice.api.port.out.IdentityDummyEmailUpdate.class)))
        .thenReturn("synthetic-anonymous@synthetic.oriso.test");
    var matrixIdentity =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateUserResponseDTO();
    matrixIdentity.setUserId("@synthetic-anonymous:synthetic.oriso.test");
    when(matrix.createUser(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(matrixIdentity));
    downstream = MockRestServiceServer.bindTo(transport).build();
    downstream
        .expect(ExpectedCount.manyTimes(), anything())
        .andRespond(
            request -> {
              var path = request.getURI().getPath();

              if (path.endsWith("/consultingtypes/1/extended")) {
                return withSuccess(
                        "{\"id\":1,\"registration\":{},\"groupChat\":{\"isGroupChat\":false}}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.endsWith("/agencies/410") || path.endsWith("/agencies/consultingtype/1")) {
                return withSuccess(
                        "[{\"id\":410,\"tenantId\":"
                            + agencyTenant
                            + ",\"consultingType\":1,\"teamAgency\":false,\"topicIds\":[]}]",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.endsWith("/internal/agencies/410/matrix-service-account")) {
                return withSuccess(
                        "{\"matrixUserId\":\"@synthetic-agency:synthetic.oriso.test\",\"matrixPassword\":\"synthetic-fixture\"}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/tenant/public/single") || path.equals("/tenant/public/id/41")) {
                return withSuccess(
                        "{\"id\":41,\"settings\":{\"tenantAdminControls\":{\"chatRecoverySettings\":{\"asker\":\"RECOVERY_KEY\",\"consultant\":\"RECOVERY_KEY\",\"revision\":0}}}}",
                        MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/tenantadmin/42/dpa/gate")) {
                ownerReads.incrementAndGet();
                return withSuccess(otherTenantGate, MediaType.APPLICATION_JSON)
                    .createResponse(request);
              }
              if (path.equals("/tenantadmin/41/dpa/gate")) {
                ownerReads.incrementAndGet();
                if (ownerStatus != 200) {
                  return org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.valueOf(ownerStatus))
                      .body("synthetic-owner-private-detail")
                      .createResponse(request);
                }
                if (requireTechnicalOwnerHeader
                    && !"0".equals(request.getHeaders().getFirst("tenantId"))) {
                  return org.springframework.test.web.client.response.MockRestResponseCreators
                      .withStatus(org.springframework.http.HttpStatus.FORBIDDEN)
                      .createResponse(request);
                }
                return withSuccess(gate, MediaType.APPLICATION_JSON).createResponse(request);
              }
              throw new AssertionError("Unexpected external operation: " + path);
            });
  }

  @AfterEach
  void clearContexts() {
    TenantContext.clear();
    if (asker != null) {
      java.util.stream.StreamSupport.stream(sessions.findAll().spliterator(), false)
          .filter(session -> session.getUser().getUserId().equals(asker.getUserId()))
          .forEach(sessions::delete);
    }
    if (anonymousIdentityId != null) {
      users
          .findById(anonymousIdentityId)
          .ifPresent(
              user -> {
                java.util.stream.StreamSupport.stream(sessions.findAll().spliterator(), false)
                    .filter(session -> session.getUser().getUserId().equals(anonymousIdentityId))
                    .forEach(sessions::delete);
                users.delete(user);
              });
    }
    fixtures.removeAll();
    if (downstream != null) downstream.reset();
  }

  @Test
  void renewalGraceExpiryAndCurrentSignatureAreReadFreshForAnExistingAsker() throws Exception {
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var expired = gate;
    var body = "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}";
    gate =
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"OUTDATED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":true,\"newCounsellingAllowed\":true}";
    var grace =
        request("/users/askers/session/new", asker.getUserId(), asker.getUsername(), "user", body);
    org.junit.jupiter.api.Assertions.assertEquals(201, grace.statusCode(), grace.body());
    gate = expired;
    var denied =
        request("/users/askers/session/new", asker.getUserId(), asker.getUsername(), "user", body);
    org.junit.jupiter.api.Assertions.assertEquals(403, denied.statusCode(), denied.body());
    gate =
        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":null,\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}";
    var signed =
        request("/users/askers/session/new", asker.getUserId(), asker.getUsername(), "user", body);
    org.junit.jupiter.api.Assertions.assertEquals(201, signed.statusCode(), signed.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {401, 403, 404, 500, 503})
  void ownerHttpFailuresAreSanitizedDependencyFailures(int status) throws Exception {
    ownerStatus = status;
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"reason\":\"DPA_POLICY_UNAVAILABLE\"}", result.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{\"dpaPublished\":true,\"dpaSigned\":false}",
        "{\"dpaPublished\":false,\"dpaSigned\":false}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"UNSIGNED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":false}",
        "{\"dpaPublished\":false,\"dpaSigned\":false,\"dpaStatus\":\"MISSING\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":false}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"INCONSISTENT\",\"currentDpaVersion\":\"v2\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":false}"
      })
  void unsignedMissingAndInconsistentOwnersDoNotAuthorizeNewWork(String response) throws Exception {
    gate = response;
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "not-json",
        "{}",
        "{\"dpaPublished\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":\"true\"}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"OUTDATED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"UNSIGNED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":true,\"newCounsellingAllowed\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"bad-deadline\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00Z\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00+02:00\",\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}"
      })
  void malformedOwnerContractsAreDependencyFailures(String response) throws Exception {
    gate = response;
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"reason\":\"DPA_POLICY_UNAVAILABLE\"}", result.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"INITIAL", "NEW"})
  void expiredRenewalBlocksAnAppointmentStartingUnbegunCounselling(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    var session = storedSession(status);
    var result =
        request(
            "/appointments/sessions/" + session.getId() + "/enquiry/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"counselorEmail\":\"synthetic-consultant@synthetic.oriso.test\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"INITIAL", "NEW"})
  void expiredRenewalBlocksAssigningAStoredUnbegunSession(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    var session = storedSession(status);
    var currentConsultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    if (status == de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL) {
      session.setConsultant(currentConsultant);
      sessions.save(session);
    }
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    when(matrix.setUserPowerLevel(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(matrix.getRoomMembers(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            java.util.Optional.of(
                List.of(
                    currentConsultant.getMatrixUserId(),
                    consultant.getMatrixUserId(),
                    asker.getMatrixUserId())));
    var result =
        request(
            "PUT",
            "/users/sessions/" + session.getId() + "/consultant/" + consultant.getId(),
            currentConsultant.getId(),
            currentConsultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void begunCounsellingCanBeReassignedAfterExpiry() throws Exception {
    var session =
        storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS);
    var currentConsultant = session.getConsultant();
    var nextConsultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    when(matrix.setUserPowerLevel(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    when(matrix.getRoomMembers(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(
            java.util.Optional.of(
                List.of(
                    currentConsultant.getMatrixUserId(),
                    nextConsultant.getMatrixUserId(),
                    asker.getMatrixUserId())));
    var result =
        request(
            "PUT",
            "/users/sessions/" + session.getId() + "/consultant/" + nextConsultant.getId(),
            currentConsultant.getId(),
            currentConsultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(200, result.statusCode(), result.body());
  }

  @Test
  void begunCounsellingStillAllowsHistoryAndMessagesAfterExpiry() throws Exception {
    var session =
        storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS);
    when(matrix.getRoomMessages(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of(Map.of("event_id", "$history", "body", "Earlier counselling message")));
    when(matrix.sendMessage(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Map.of("event_id", "$continued"));
    var history =
        request(
            "GET",
            "/matrix/sessions/" + session.getId() + "/messages",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(200, history.statusCode(), history.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        history.body().contains("$history"), history.body());
    var message =
        request(
            "/matrix/sessions/" + session.getId() + "/messages",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"message\":\"Continuing counselling\"}");
    org.junit.jupiter.api.Assertions.assertEquals(200, message.statusCode(), message.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        message.body().contains("\"success\":true"), message.body());
  }

  @Test
  void anUnboundAnonymousQueueUsesTheAcceptingConsultantsConcreteOrganisation() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var session = anonymousSession(null);
    var consultant = fixtures.consultant(42, AGENCY);
    var result =
        request(
            "PUT",
            "/conversations/askers/anonymous/" + session.getId() + "/accept",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void explicitlyNullDecisionFieldsCannotMasqueradeAsALegacySignedResponse() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true,\"newCounsellingAllowed\":null}";
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_POLICY_UNAVAILABLE"), result.body());
  }

  @Test
  void aChangedServingOrganisationCannotReuseTheFormerOrganisationsSignature() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var body = "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}";
    var first =
        request("/users/askers/session/new", asker.getUserId(), asker.getUsername(), "user", body);
    org.junit.jupiter.api.Assertions.assertEquals(201, first.statusCode(), first.body());
    agencyTenant = 42;
    var second =
        request("/users/askers/session/new", asker.getUserId(), asker.getUsername(), "user", body);
    org.junit.jupiter.api.Assertions.assertEquals(403, second.statusCode(), second.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        second.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), second.body());
  }

  @Test
  void anonymousAcceptanceWithAnUnknownServingTenantFailsBeforeRoomProvisioning() throws Exception {
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    var session = anonymousSession(null);
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    consultant.setTenantId(null);
    consultants.save(consultant);
    when(matrix.createRoomAsMatrixUser(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString()))
        .thenThrow(new IllegalStateException("The external fixture refuses room provisioning"));
    var result =
        request(
            "PUT",
            "/conversations/askers/anonymous/" + session.getId() + "/accept",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_POLICY_UNAVAILABLE"), result.body());
  }

  @Test
  void ownerReadUsesTheTechnicalScopeAfterResolvingTheServingOrganisation() throws Exception {
    requireTechnicalOwnerHeader = true;
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(201, result.statusCode(), result.body());
  }

  @Test
  void incompleteOwnerDecisionIsADependencyFailureRatherThanALegalDenial() throws Exception {
    gate = "{\"newCounsellingAllowed\":false}";
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"agencyId\":410,\"consultingType\":\"1\"}");
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_POLICY_UNAVAILABLE"), result.body());
  }

  @Test
  void expiredRenewalBlocksInitialRegisteredCounsellingBeforeIdentityWrites() throws Exception {
    refuseIdentityProvisioning = true;
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/users/askers/new"))
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"username\":\"syntheticuser\",\"password\":\"SyntheticFixture1!\",\"postcode\":\"12345\",\"age\":\"25\",\"agencyId\":410,\"termsAccepted\":\"true\",\"consultingType\":\"1\"}"))
            .build();
    var result =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void expiredRenewalBlocksNewAnonymousEnquiryBeforeAccountProvisioning() throws Exception {
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create(
                    "http://localhost:" + port + "/conversations/askers/anonymous/new"))
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"consultingType\":1}"))
            .build();
    var result =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
    org.mockito.Mockito.verify(identityAuthentication, org.mockito.Mockito.never())
        .createUser(
            org.mockito.ArgumentMatchers.any(
                de.caritas.cob.userservice.api.adapters.web.dto.UserDTO.class));
    org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
        .createUser(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"INITIAL", "NEW"})
  void expiredRenewalBlocksAcceptingAnAnonymousEnquiryAtItsServingAgency(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    var session = anonymousSession(AGENCY);
    session.setStatus(status);
    sessions.save(session);
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    var result =
        request(
            "PUT",
            "/conversations/askers/anonymous/" + session.getId() + "/accept",
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void expiredRenewalBlocksAcceptingAStoredInitialRegisteredEnquiry() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    var result =
        request(
            "PUT",
            "/users/sessions/new/" + session.getId(),
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void expiredRenewalBlocksAcceptingAnUnbegunRegisteredEnquiry() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW);
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    var result =
        request(
            "PUT",
            "/users/sessions/new/" + session.getId(),
            consultant.getId(),
            consultant.getUsername(),
            "consultant",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void permittedFirstEnquiryPreflightIsBodylessUncachedAndFinalizationRechecksExpiry()
      throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    var expired = gate;
    gate = "{\"dpaPublished\":true,\"dpaSigned\":true}";
    for (int attempt = 0; attempt < 2; attempt++) {
      var result = enquiryPermission(session);
      org.junit.jupiter.api.Assertions.assertEquals(204, result.statusCode(), result.body());
      org.junit.jupiter.api.Assertions.assertEquals("", result.body());
      org.junit.jupiter.api.Assertions.assertEquals(
          "no-store", result.headers().firstValue("Cache-Control").orElse(""));
    }
    org.junit.jupiter.api.Assertions.assertEquals(2, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
    gate = expired;
    var denied = enquiryPermission(session);
    org.junit.jupiter.api.Assertions.assertEquals(403, denied.statusCode(), denied.body());
    var finalization =
        request(
            "/users/sessions/" + session.getId() + "/enquiry/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"message\":\"\",\"matrixEventId\":\"$encrypted-enquiry\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, finalization.statusCode());
    org.junit.jupiter.api.Assertions.assertTrue(
        finalization.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), finalization.body());
    org.junit.jupiter.api.Assertions.assertEquals(4, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  private java.net.http.HttpResponse<String> enquiryPermission(
      de.caritas.cob.userservice.api.model.Session session) throws Exception {
    return request(
        "GET",
        "/users/sessions/" + session.getId() + "/enquiry/permission",
        asker.getUserId(),
        asker.getUsername(),
        "user",
        "");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"INITIAL", "NEW"})
  void expiredRenewalBlocksFirstEnquiryPreflightForAnUnbegunSession(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    var session = storedSession(status);
    var finalization =
        request(
            "/users/sessions/" + session.getId() + "/enquiry/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"message\":\"\",\"matrixEventId\":\"$encrypted-enquiry\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, finalization.statusCode());
    org.junit.jupiter.api.Assertions.assertTrue(
        finalization.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), finalization.body());
    var result =
        request(
            "GET",
            "/users/sessions/" + session.getId() + "/enquiry/permission",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "DPA_NEW_COUNSELLING_NOT_ALLOWED", result.headers().firstValue("X-Reason").orElse(""));
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("permittedFirstEnquiryGates")
  void currentGraceAndLegacyOwnersPermitUnbegunFirstEnquiryPreflight(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status, String response)
      throws Exception {
    gate = response;
    requireTechnicalOwnerHeader = true;
    var result = enquiryPermission(storedSession(status));
    org.junit.jupiter.api.Assertions.assertEquals(204, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals("", result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "no-store", result.headers().firstValue("Cache-Control").orElse(""));
    org.junit.jupiter.api.Assertions.assertEquals(1, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments>
      permittedFirstEnquiryGates() {
    return java.util.stream.Stream.of(
            de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL,
            de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW)
        .flatMap(
            status ->
                java.util.stream.Stream.of(
                        "{\"dpaPublished\":true,\"dpaSigned\":true}",
                        "{\"dpaPublished\":true,\"dpaSigned\":true,\"dpaStatus\":\"VALID\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":null,\"renewalGraceActive\":false,\"newCounsellingAllowed\":true}",
                        "{\"dpaPublished\":true,\"dpaSigned\":false,\"dpaStatus\":\"OUTDATED\",\"currentDpaVersion\":\"v2\",\"signingDeadlineAt\":\"2999-01-01T00:00:00Z\",\"renewalGraceActive\":true,\"newCounsellingAllowed\":true}")
                    .map(gate -> org.junit.jupiter.params.provider.Arguments.of(status, gate)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"INITIAL", "NEW"})
  void ownerOutagePreventsFirstEnquiryPreflightWithASanitizedDependencyFailure(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    ownerStatus = 503;
    var result = enquiryPermission(storedSession(status));
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "DPA_POLICY_UNAVAILABLE", result.headers().firstValue("X-Reason").orElse(""));
    org.junit.jupiter.api.Assertions.assertFalse(result.body().contains("synthetic-owner-private"));
    org.junit.jupiter.api.Assertions.assertEquals(1, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void malformedOwnerPreventsFirstEnquiryPreflightAsADependencyFailure() throws Exception {
    gate = "{\"newCounsellingAllowed\":false}";
    var result =
        enquiryPermission(
            storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL));
    org.junit.jupiter.api.Assertions.assertEquals(502, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "DPA_POLICY_UNAVAILABLE", result.headers().firstValue("X-Reason").orElse(""));
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void anotherAskerCannotPreflightTheOwnersSessionOrReadItsDpa() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    var other = fixtures.adviceSeeker(SERVING_TENANT);
    var result =
        request(
            "GET",
            "/users/sessions/" + session.getId() + "/enquiry/permission",
            other.getUserId(),
            other.getUsername(),
            "user",
            "");
    org.junit.jupiter.api.Assertions.assertEquals(400, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"consultant", "anonymous"})
  void nonAskerAuthoritiesCannotPreflightOrReadTheOwnerDpa(String role) throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    var result =
        request(
            "GET",
            "/users/sessions/" + session.getId() + "/enquiry/permission",
            asker.getUserId(),
            asker.getUsername(),
            role,
            "");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void anOwnedAnonymousSessionCannotUseTheRegisteredFirstEnquiryPreflight() throws Exception {
    var result = enquiryPermission(anonymousSession(AGENCY));
    org.junit.jupiter.api.Assertions.assertEquals(400, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void anAlreadyWrittenEnquiryCannotBePreflightedAgainOrReadOwnerDpa() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW);
    session.setEnquiryMessageDate(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
    sessions.save(session);
    var result = enquiryPermission(session);
    org.junit.jupiter.api.Assertions.assertEquals(409, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = de.caritas.cob.userservice.api.model.Session.SessionStatus.class,
      names = {"DONE", "IN_ARCHIVE"})
  void terminalSessionsCannotPreflightAnEnquiryOrReadOwnerDpa(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) throws Exception {
    var result = enquiryPermission(storedSession(status));
    org.junit.jupiter.api.Assertions.assertEquals(409, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void begunDirectCounsellingCanPreflightItsFirstEnquiryAfterExpiryWithoutOwnerRead()
      throws Exception {
    var result =
        enquiryPermission(
            storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS));
    org.junit.jupiter.api.Assertions.assertEquals(204, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertEquals("", result.body());
    org.junit.jupiter.api.Assertions.assertEquals(
        "no-store", result.headers().firstValue("Cache-Control").orElse(""));
    org.junit.jupiter.api.Assertions.assertEquals(0, ownerReads.get());
    org.mockito.Mockito.verifyNoInteractions(matrix);
  }

  @Test
  void expiredRenewalBlocksFirstEnquiryForAStoredInitialSession() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.INITIAL);
    var result =
        request(
            "/users/sessions/" + session.getId() + "/enquiry/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"message\":\"\",\"matrixEventId\":\"$encrypted-enquiry\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  @Test
  void expiredRenewalBlocksFirstEnquiryForAStoredUnbegunSession() throws Exception {
    var session = storedSession(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW);
    var result =
        request(
            "/users/sessions/" + session.getId() + "/enquiry/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"message\":\"\",\"matrixEventId\":\"$encrypted-enquiry\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  private de.caritas.cob.userservice.api.model.Session anonymousSession(Long agency) {
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    asker.setMatrixUserId("@synthetic-asker:synthetic.oriso.test");
    users.save(asker);
    var session = new de.caritas.cob.userservice.api.model.Session();
    session.setUser(asker);
    session.setTenantId(SERVING_TENANT);
    session.setAgencyId(agency);
    session.setConsultingTypeId(1);
    session.setStatus(de.caritas.cob.userservice.api.model.Session.SessionStatus.NEW);
    session.setRegistrationType(
        de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS);
    session.setPostcode("12345");
    session.setLanguageCode(com.neovisionaries.i18n.LanguageCode.de);
    session.setTeamSession(false);
    session.setSessionTopics(new java.util.ArrayList<>());
    session.setIsConsultantDirectlySet(false);
    session.setMatrixRoomId("!synthetic-session:synthetic.oriso.test");
    return sessions.save(session);
  }

  private de.caritas.cob.userservice.api.model.Session storedSession(
      de.caritas.cob.userservice.api.model.Session.SessionStatus status) {
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    asker.setMatrixUserId("@synthetic-asker:synthetic.oriso.test");
    users.save(asker);
    var session = fixtures.session(asker, AGENCY, null);
    session.setStatus(status);
    if (status == de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS) {
      session.setConsultant(fixtures.consultant(SERVING_TENANT, AGENCY));
    }
    session.setMatrixRoomId("!synthetic-session:synthetic.oriso.test");
    return sessions.save(session);
  }

  @Test
  void expiredRenewalBlocksDirectCounsellingBeforeItCommences() throws Exception {
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var consultant = fixtures.consultant(SERVING_TENANT, AGENCY);
    var result =
        request(
            "/users/askers/session/new",
            asker.getUserId(),
            asker.getUsername(),
            "user",
            "{\"postcode\":\"12345\",\"age\":\"25\",\"agencyId\":410,\"consultingType\":\"1\",\"consultantId\":\""
                + consultant.getId()
                + "\"}");
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }

  private java.net.http.HttpResponse<String> request(
      String path, String id, String username, String role, String body) throws Exception {
    return request("POST", path, id, username, role, body);
  }

  private java.net.http.HttpResponse<String> request(
      String method, String path, String id, String username, String role, String body)
      throws Exception {
    var token =
        Jwt.withTokenValue("synthetic-token")
            .header("alg", "none")
            .subject(id)
            .claim("username", username)
            .claim("tenantId", SERVING_TENANT)
            .claim("realm_access", Map.of("roles", List.of(role)))
            .build();
    when(jwtDecoder.decode("synthetic-token")).thenReturn(token);
    var request =
        java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer synthetic-token")
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body))
            .build();
    return java.net.http.HttpClient.newHttpClient()
        .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void expiredRenewalBlocksAnExistingAskerCreatingAnotherIndividualSession() throws Exception {
    asker = fixtures.adviceSeeker(SERVING_TENANT);
    var token =
        Jwt.withTokenValue("synthetic-token")
            .header("alg", "none")
            .subject(asker.getUserId())
            .claim("username", asker.getUsername())
            .claim("tenantId", SERVING_TENANT)
            .claim("realm_access", Map.of("roles", List.of("user")))
            .build();
    when(jwtDecoder.decode("synthetic-token")).thenReturn(token);
    var request =
        java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + port + "/users/askers/session/new"))
            .header("Authorization", "Bearer synthetic-token")
            .header("Cookie", "CSRF-TOKEN=test")
            .header("X-CSRF-Token", "test")
            .header("Content-Type", "application/json")
            .POST(
                java.net.http.HttpRequest.BodyPublishers.ofString(
                    "{\"postcode\":\"12345\",\"age\":\"25\",\"agencyId\":410,\"consultingType\":\"1\"}"))
            .build();
    var result =
        java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    org.junit.jupiter.api.Assertions.assertEquals(403, result.statusCode(), result.body());
    org.junit.jupiter.api.Assertions.assertTrue(
        result.body().contains("DPA_NEW_COUNSELLING_NOT_ALLOWED"), result.body());
  }
}
