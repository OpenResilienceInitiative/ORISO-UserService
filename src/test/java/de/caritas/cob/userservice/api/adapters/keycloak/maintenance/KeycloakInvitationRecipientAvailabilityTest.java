package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ReviewedMaintenanceProfiles;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailability.*;
import de.caritas.cob.userservice.api.port.out.InvitationRecipientAvailabilityUnavailableException;
import de.caritas.cob.userservice.api.testHelper.InvitationRecipientHttpFixture;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpRequest;

/** Actual HTTP adapter/proof wire with a controlled receiver; native authorization is separate. */
class KeycloakInvitationRecipientAvailabilityTest {
  private static final byte[] KEY = new byte[32];
  private static final String SUBJECT = "b4444444-4444-4444-8444-444444444444";
  private static final Human HUMAN =
      new Human(
          SUBJECT,
          "https://issuer.example/realms/oriso",
          "human-admin",
          "PROTECTED_ACCOUNT_OPERATIONS_V1",
          "Bearer synthetic-human-token");
  private static final String URL =
      "http://identity.example/realms/oriso/oriso-account-maintenance/v1/availability/invitation-email";

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void usesTheFixedSignedBooleanWireWithoutAnyIdentityProjection(boolean available) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer synthetic-admin-token"))
        .andExpect(header("X-ORISO-Human-Authorization", HUMAN.rawBearer()))
        .andExpect(
            request -> {
              var body =
                  fixture.codec.read(
                      ((MockClientHttpRequest) request).getBodyAsString(), JsonNode.class);
              assertEquals(
                  Set.of("email", "profile", "tenantId", "tenantMode", "origin"), fields(body));
              assertEquals("free@example.org", body.path("email").textValue());
              assertEquals("7", body.path("tenantId").textValue());
              assertEquals("EXISTING", body.path("tenantMode").textValue());
              assertEquals(Set.of("family", "id", "version", "kind"), fields(body.path("profile")));
              assertEquals("ORDINARY", body.path("profile").path("family").textValue());
              assertEquals("consultant", body.path("profile").path("id").textValue());
              assertEquals(1, body.path("profile").path("version").longValue());
              assertEquals("CONSULTANT", body.path("profile").path("kind").textValue());
              var origin = body.path("origin");
              assertEquals(
                  Set.of(
                      "kind",
                      "workflowId",
                      "humanSubject",
                      "humanIssuer",
                      "humanClient",
                      "humanAuthority"),
                  fields(origin));
              assertEquals("HUMAN_ADMIN", origin.path("kind").textValue());
              assertEquals(SUBJECT, origin.path("humanSubject").textValue());
              assertEquals(HUMAN.issuer(), origin.path("humanIssuer").textValue());
              assertEquals(HUMAN.client(), origin.path("humanClient").textValue());
              assertEquals(HUMAN.authority(), origin.path("humanAuthority").textValue());
              assertFalse(origin.path("workflowId").textValue().isBlank());
              verifyProof(
                  fixture.codec, request.getHeaders().getFirst("X-ORISO-Task-Context"), body);
            })
        .andRespond(
            withSuccess("{\"available\":" + available + "}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));

    assertEquals(
        available,
        assertDoesNotThrow(
            () ->
                fixture.adapter.available(
                    "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING))));
    fixture.server.verify();
    verify(fixture.broker).issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    verify(fixture.broker, never()).issueCheckedProvisioning(any());
  }

  private static Set<String> fields(JsonNode node) {
    var result = new HashSet<String>();
    node.fieldNames().forEachRemaining(result::add);
    return result;
  }

  @ParameterizedTest
  @EnumSource(Kind.class)
  void selectsOnlyTheExactReviewedProfileForEachSupportedInvitationKind(Kind kind) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(
        assertDoesNotThrow(
            () ->
                fixture.adapter.available(
                    "free@example.org",
                    new Target(kind, kind == Kind.PLATFORM_ADMIN ? 0L : 7L, TenantMode.EXISTING))));
    var expected =
        Map.of(
            Kind.CONSULTANT,
            "consultant",
            Kind.AGENCY_ADMIN,
            "agency-admin",
            Kind.CONSULTANT_AGENCY_ADMIN,
            "combined-basic",
            Kind.TENANT_ADMIN,
            "tenant-admin",
            Kind.PLATFORM_ADMIN,
            "platform-admin");
    var profile = fixture.lastBody.get().path("profile");
    assertEquals(expected.get(kind), profile.path("id").textValue());
    assertEquals(kind.name(), profile.path("kind").textValue());
    assertEquals(
        Set.of(Kind.TENANT_ADMIN, Kind.PLATFORM_ADMIN).contains(kind) ? "PROTECTED" : "ORDINARY",
        profile.path("family").textValue());
    fixture.server.verify();
  }

  @Test
  void aValidChatOnlyCombinedProfileRemainsARecognizedCreationKindForTheRead() {
    var fixture = InvitationRecipientHttpFixture.chatOnly(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(
        assertDoesNotThrow(
            () ->
                fixture.adapter.available(
                    "free@example.org",
                    new Target(Kind.CONSULTANT_AGENCY_ADMIN, 7L, TenantMode.EXISTING))));
    assertEquals("combined", fixture.lastBody.get().path("profile").path("id").textValue());
    fixture.server.verify();
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CONSULTANT", "CONSULTANT_AGENCY_ADMIN"})
  void explicitBasicAndChatUnionsKeepTheirClosedPreferenceWhenBothAreInstalled(Kind kind) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    String expected = kind == Kind.CONSULTANT ? "consultant" : "combined-basic";
    var adapter = fixture.newAdapter(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(adapter.available("free@example.org", new Target(kind, 7L, TenantMode.EXISTING)));
    assertEquals(expected, fixture.lastBody.get().path("profile").path("id").textValue());
    fixture.server.verify();
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CONSULTANT", "CONSULTANT_AGENCY_ADMIN"})
  void anInstalledBasicOnlyUnionRemainsSupported(Kind kind) {
    var fixture = InvitationRecipientHttpFixture.withoutGroupChat(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(
        fixture.adapter.available("free@example.org", new Target(kind, 7L, TenantMode.EXISTING)));
    fixture.server.verify();
  }

  @ParameterizedTest
  @EnumSource(
      value = Kind.class,
      names = {"CONSULTANT", "CONSULTANT_AGENCY_ADMIN"})
  void theClosedChatUnionIsUsedOnlyWhenItsBasicUnionIsAbsent(Kind kind) {
    var fixture = InvitationRecipientHttpFixture.chatOnly(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(
        fixture.adapter.available("free@example.org", new Target(kind, 7L, TenantMode.EXISTING)));
    assertEquals(
        kind == Kind.CONSULTANT ? "consultant-chat" : "combined",
        fixture.lastBody.get().path("profile").path("id").textValue());
    fixture.server.verify();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void duplicateAliasesWithinThePreferredUnionCannotFallBackOrChooseTheFirst(boolean chatOnly) {
    var fixture =
        chatOnly
            ? InvitationRecipientHttpFixture.chatOnly(() -> HUMAN)
            : new InvitationRecipientHttpFixture(() -> HUMAN);
    var catalogue = new HashMap<>(fixture.reviewed.profiles());
    var profiles = new ArrayList<>(catalogue.get("CONSULTANT"));
    profiles.add(
        new ReviewedMaintenanceProfiles.Profile(
            "ORDINARY", "duplicate-alias", 1, profiles.getFirst().realmRoles()));
    catalogue.put("CONSULTANT", profiles);
    when(fixture.reviewed.profiles()).thenReturn(catalogue);
    assertThrows(
        InvitationRecipientAvailabilityUnavailableException.class,
        () -> fixture.newAdapter(() -> HUMAN));
    verifyNoInteractions(fixture.actors, fixture.broker);
    fixture.server.verify();
  }

  @Test
  void anExtraRealmRoleDoesNotBecomeAnApprovedCreationKindOrReachNative() {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    var catalogue = new HashMap<>(fixture.reviewed.profiles());
    catalogue.put(
        "CONSULTANT",
        List.of(
            new ReviewedMaintenanceProfiles.Profile(
                "ORDINARY",
                "unexpected-role-profile",
                1,
                Set.of("consultant", "group-chat-consultant", "realm-admin"))));
    when(fixture.reviewed.profiles()).thenReturn(catalogue);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var adapter =
        fixture.newAdapter(
            () -> {
              calls.incrementAndGet();
              return HUMAN;
            });
    assertThrows(
        InvitationRecipientAvailabilityUnavailableException.class,
        () ->
            adapter.available(
                "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING)));
    assertEquals(0, calls.get());
    verifyNoInteractions(fixture.actors, fixture.broker);
    fixture.server.verify();
  }

  @ParameterizedTest
  @MethodSource("validTenantSelections")
  void retainsExplicitReservedAndUnassignedSelectionsWithoutCreatingAnAccount(Target target) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON)
                .header("Cache-Control", "no-store"));
    assertTrue(fixture.adapter.available("free@example.org", target));
    assertEquals(target.tenantMode().name(), fixture.lastBody.get().path("tenantMode").textValue());
    assertEquals(
        target.tenantId() == null ? null : target.tenantId().toString(),
        fixture.lastBody.get().path("tenantId").textValue());
    fixture.server.verify();
  }

  private static java.util.stream.Stream<Target> validTenantSelections() {
    return java.util.stream.Stream.of(
        new Target(Kind.TENANT_ADMIN, null, TenantMode.RESERVED_NEW),
        new Target(Kind.TENANT_ADMIN, 17L, TenantMode.RESERVED_NEW),
        new Target(Kind.CONSULTANT_AGENCY_ADMIN, 17L, TenantMode.RESERVED_NEW),
        new Target(Kind.CONSULTANT, null, TenantMode.RESERVED_NEW),
        new Target(Kind.CONSULTANT, null, TenantMode.UNASSIGNED));
  }

  @ParameterizedTest
  @MethodSource("invalidTenantSelections")
  void invalidSelectionCannotReachTheTokenBrokerOrNativeRead(Target target) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    assertThrows(
        InvitationRecipientAvailabilityUnavailableException.class,
        () -> fixture.adapter.available("free@example.org", target));
    verifyNoInteractions(fixture.actors, fixture.broker);
    assertNull(fixture.lastBody.get());
    fixture.server.verify();
  }

  private static java.util.stream.Stream<Target> invalidTenantSelections() {
    return java.util.stream.Stream.of(
        new Target(Kind.CONSULTANT, null, TenantMode.EXISTING),
        new Target(Kind.TENANT_ADMIN, 0L, TenantMode.EXISTING),
        new Target(Kind.PLATFORM_ADMIN, 7L, TenantMode.RESERVED_NEW),
        new Target(Kind.AGENCY_ADMIN, null, TenantMode.UNASSIGNED),
        new Target(Kind.CONSULTANT, 7L, TenantMode.UNASSIGNED),
        new Target(Kind.CONSULTANT, -1L, TenantMode.RESERVED_NEW));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"available\":\"true\"}",
        "{\"available\":true,\"id\":\"private\"}",
        "{\"available\":true,\"available\":false}",
        "not-json"
      })
  void indeterminateOrProjectingRepliesNeverBecomeFree(String reply) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withSuccess(reply, MediaType.APPLICATION_JSON).header("Cache-Control", "no-store"));
    var failure =
        assertThrows(
            InvitationRecipientAvailabilityUnavailableException.class,
            () ->
                fixture.adapter.available(
                    "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING)));
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private"));
    fixture.server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 409, 500, 503})
  void providerFailuresRemainRefusedOrUnavailableWithoutLeakingTheirBodies(int status) {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(
            withStatus(HttpStatusCode.valueOf(status)).body("private-recipient-password-token"));
    var failure =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.adapter.available(
                    "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING)));
    if (status == 401 || status == 403)
      assertInstanceOf(
          de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class, failure);
    else assertInstanceOf(InvitationRecipientAvailabilityUnavailableException.class, failure);
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains("private"));
    fixture.server.verify();
  }

  @Test
  void aMissingDesiredProfileDoesNotBreakSubsetStartupOrReachAnyBrokerOrNativeRead() {
    var fixture = assertDoesNotThrow(() -> new InvitationRecipientHttpFixture(() -> HUMAN, false));
    assertThrows(
        InvitationRecipientAvailabilityUnavailableException.class,
        () ->
            fixture.adapter.available(
                "free@example.org",
                new Target(Kind.CONSULTANT_AGENCY_ADMIN, 7L, TenantMode.EXISTING)));
    verifyNoInteractions(fixture.actors, fixture.broker);
    assertNull(fixture.lastBody.get());
    fixture.server.verify();
  }

  @Test
  void aCurrentHumanRefusalHappensBeforeObtainingAnyTechnicalToken() {
    var fixture =
        new InvitationRecipientHttpFixture(
            () -> {
              throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
                  "private-human-token");
            });
    var failure =
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
            () ->
                fixture.adapter.available(
                    "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING)));
    assertFalse(failure.getMessage().contains("private"));
    assertNull(failure.getCause());
    verifyNoInteractions(fixture.actors, fixture.broker);
    fixture.server.verify();
  }

  @Test
  void responseWithoutTheRequiredNoStoreContractCannotPromiseAvailability() {
    var fixture = new InvitationRecipientHttpFixture(() -> HUMAN);
    fixture
        .server
        .expect(requestTo(URL))
        .andRespond(withSuccess("{\"available\":true}", MediaType.APPLICATION_JSON));
    assertThrows(
        InvitationRecipientAvailabilityUnavailableException.class,
        () ->
            fixture.adapter.available(
                "free@example.org", new Target(Kind.CONSULTANT, 7L, TenantMode.EXISTING)));
    fixture.server.verify();
  }

  private static void verifyProof(AccountLifecycleCodec codec, String proof, JsonNode body) {
    try {
      var parts = proof.split("\\.");
      assertEquals(2, parts.length);
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      assertEquals(
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(mac.doFinal(("proof\n" + parts[0]).getBytes(StandardCharsets.UTF_8))),
          parts[1]);
      var claims =
          codec.read(
              new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
              JsonNode.class);
      assertEquals("userservice", claims.path("iss").textValue());
      assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
      assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
      assertEquals("account.invitation-email-availability", claims.path("operation").textValue());
      assertEquals("POST", claims.path("method").textValue());
      assertEquals("ACCOUNT_ADMIN", claims.path("actor").textValue());
      assertEquals("oriso", claims.path("realm").textValue());
      assertEquals(
          "realm/realm-id/availability/invitation-email", claims.path("target").textValue());
      assertEquals("account-admin", claims.path("taskClient").textValue());
      assertEquals("a4444444-4444-4444-8444-444444444444", claims.path("taskSubject").textValue());
      assertEquals(codec.digest("payload", body), claims.path("payloadDigest").textValue());
      assertEquals(60, claims.path("exp").longValue() - claims.path("iat").longValue());
    } catch (Exception invalid) {
      throw new AssertionError("Invalid bounded invitation proof", invalid);
    }
  }
}
