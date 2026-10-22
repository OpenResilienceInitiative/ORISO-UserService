package de.caritas.cob.userservice.api.service.identity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.KeycloakHumanAdminMaintenance;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.*;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Actual H2 domain plus HTTP boundary. The mocked broker conveys no native JWT authority credit.
 */
class HumanAdminAccountMaintenanceTest {
  private static final String ACCOUNT = "60000000-0000-4000-8000-000000000001";
  private static final String HUMAN = "60000000-0000-4000-8000-000000000002";
  private static final String OPERATION = "60000000-0000-4000-8000-000000000003";
  private static final String SUBJECT = "60000000-0000-4000-8000-000000000004";
  private static final String ISSUER = "https://public-auth.example.test/realms/test";
  private static final String URL =
      "https://internal-auth.example.test/realms/test/oriso-account-maintenance/v1/accounts/"
          + ACCOUNT;
  private static final byte[] KEY =
      "synthetic-admin-maintenance-key-32bytes".getBytes(StandardCharsets.UTF_8);
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"), ZoneOffset.UTC);
  private final AccountLifecycleCodec codec = new AccountLifecycleCodec(KEY);
  private final ObjectMapper json = new ObjectMapper();
  private final IdentityClientConfig mode = mock(IdentityClientConfig.class);
  private final AdminScope scope = mock(AdminScope.class);
  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AuthenticatedUser human = new AuthenticatedUser();
  private final TaskActorConfiguration actors = mock(TaskActorConfiguration.class);
  private final TaskActorTokenBroker broker = mock(TaskActorTokenBroker.class);
  private final RestTemplate http = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(http).build();
  private final TaskActorCredentials actor =
      new TaskActorCredentials(
          TaskActor.ACCOUNT_ADMIN, "backend-account-admin", "synthetic-only", SUBJECT);
  private JdbcTemplate jdbc;
  private DataSourceTransactionManager transactions;
  private HumanAdminAccountMaintenance service;

  @BeforeEach
  void actualSchemaAndValidatedHumanFixture() {
    var ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:human-admin-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER");
    jdbc = new JdbcTemplate(ds);
    transactions = new DataSourceTransactionManager(ds);
    jdbc.execute(
        "create table admin(admin_id varchar(36) primary key,tenant_id bigint,username varchar(255) not null,type varchar(6) not null,first_name varchar(255),last_name varchar(255),email varchar(255),managed_paused boolean not null,update_date timestamp)");
    jdbc.update(
        "insert into admin values(?,7,'original-admin','TENANT','Old','Name','old@example.test',true,?)",
        ACCOUNT,
        java.sql.Timestamp.from(CLOCK.instant()));
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
            new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20261022_general_admin_identity_367/migrate.sql"))
        .execute(ds);
    when(mode.isFiveActorMode()).thenReturn(true);
    human.setUserId(HUMAN);
    human.setAccessToken("synthetic-current-human");
    human.setTenantId(7L);
    human.setGrantedAuthorities(Set.of(Authority.AuthorityValue.TENANT_ADMIN));
    request.setMethod("PATCH");
    request.setRequestURI("/service/useradmin/tenantadmins/" + ACCOUNT);
    request.addHeader("Authorization", "Bearer synthetic-current-human");
    request.addHeader("Idempotency-Key", OPERATION);
    request.setUserPrincipal(authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    when(actors.require(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(actor);
    var checked = mock(TaskActorTokenBroker.VerifiedMaintenanceToken.class);
    when(checked.actor()).thenReturn(TaskActor.ACCOUNT_ADMIN);
    when(checked.purpose()).thenReturn(TaskPurpose.ACCOUNT_MAINTENANCE);
    when(checked.accessToken()).thenReturn("synthetic-checked-admin");
    when(checked.clientId()).thenReturn(actor.clientId());
    when(checked.subject()).thenReturn(SUBJECT);
    when(checked.issuer()).thenReturn(ISSUER);
    when(checked.validatedJwtIssuedAt()).thenReturn(Instant.now().minusSeconds(5).getEpochSecond());
    when(checked.validatedJwtExpiresAt())
        .thenReturn(Instant.now().plusSeconds(300).getEpochSecond());
    when(broker.issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE)).thenReturn(checked);
    service =
        service(
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"));
  }

  @Test
  void actualAdminSqlAndCurrentHumanReachOnlyTheFixedScopedNativeRead() {
    readReply();
    List<String> malformed =
        List.of(
            "extra",
            "missing",
            "null-enabled",
            "string-enabled",
            "numeric-tenant",
            "numeric-role",
            "duplicate");
    for (String variant : malformed) {
      server
          .expect(requestTo(URL + "/read"))
          .andRespond(
              r -> {
                var body = projection(false);
                switch (variant) {
                  case "extra" -> body.put("extra", true);
                  case "missing" -> body.remove("enabled");
                  case "null-enabled" -> body.putNull("enabled");
                  case "string-enabled" -> body.put("enabled", "true");
                  case "numeric-tenant" -> body.put("tenantId", 7);
                  case "numeric-role" -> body.putArray("effectiveRoles").add(1);
                  default -> {}
                }
                String raw = codec.canonical(body);
                if (variant.equals("duplicate"))
                  raw = raw.substring(0, raw.length() - 1) + ",\"enabled\":false}";
                var response = withSuccess(raw, MediaType.APPLICATION_JSON).createResponse(r);
                response.getHeaders().setCacheControl("no-store");
                return response;
              });
    }
    var result = assertDoesNotThrow(() -> service.read(ACCOUNT));
    assertEquals(ACCOUNT, result.id());
    assertEquals(7L, result.tenantId());
    assertTrue(result.enabled());
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
    for (String variant : malformed)
      assertThrows(RuntimeException.class, () -> service.read(ACCOUNT), variant);
    verify(scope, atLeastOnce()).assertMay(any());
    verify(broker, atLeastOnce()).issueCheckedMaintenance(TaskPurpose.ACCOUNT_MAINTENANCE);
    verify(broker, never()).issue(any());
    server.verify();
  }

  @Test
  void completeProfileReceiptPrecedesExactSqlUpdateAndPreservesPauseTenantAndUsername() {
    readReply();
    server
        .expect(requestTo(URL + "/profile"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(header("Authorization", "Bearer synthetic-checked-admin"))
        .andExpect(header("X-ORISO-Human-Authorization", "Bearer synthetic-current-human"))
        .andRespond(profileReceipt(null));
    readReply(true);
    assertDoesNotThrow(() -> service.updateAdminProfile(ACCOUNT, desired()));
    var row = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    assertEquals("New", row.get("first_name"));
    assertEquals("new@example.test", row.get("email"));
    assertEquals("original-admin", row.get("username"));
    assertEquals(7L, ((Number) row.get("tenant_id")).longValue());
    assertEquals(true, row.get("managed_paused"));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
    server.verify();
  }

  @Test
  void allFourPublicAdminProfileRoutesUseTheCheckedSqlWriterWithoutGenericFallback() {
    for (boolean agency : List.of(false, true)) {
      for (boolean put : List.of(false, true)) {
        server.reset();
        actualSchemaAndValidatedHumanFixture();
        var type = agency
            ? de.caritas.cob.userservice.api.model.Admin.AdminType.AGENCY
            : de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT;
        jdbc.update("update admin set type=? where admin_id=?", type.name(), ACCOUNT);
        human.setGrantedAuthorities(Set.of(agency
            ? Authority.AuthorityValue.USER_ADMIN : Authority.AuthorityValue.TENANT_ADMIN));
        request.setUserPrincipal(authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
        request.setMethod(put ? "PUT" : "PATCH");
        request.setRequestURI("/service/useradmin/" + (agency ? "agencyadmins/" : "tenantadmins/") + ACCOUNT);
        assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
        human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
        var scopeRows = mock(AdminRepository.class);
        when(scopeRows.findById(ACCOUNT)).thenAnswer(ignored -> Optional.of(localAdmin(type)));
        var actualScope = new AdminScope(human, scopeRows, mock(AdminAgencyRepository.class),
            mock(ConsultantRepository.class), mock(ConsultantAgencyRepository.class),
            mock(de.caritas.cob.userservice.api.service.agency.AgencyService.class),
            mock(UserRepository.class), mock(SessionRepository.class), mock(UserAgencyRepository.class));
        org.springframework.test.util.ReflectionTestUtils.setField(actualScope, "multitenancyEnabled", true);
        assertEquals(new AdminScope.Tenant(7L), actualScope.current());
        assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
        jdbc.update("update admin set tenant_id=8 where admin_id=?", ACCOUNT);
        assertThrows(de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
            () -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
        jdbc.update("update admin set tenant_id=7 where admin_id=?", ACCOUNT);
        assertEquals(7L, localAdmin(type).getTenantId());
        service = new HumanAdminAccountMaintenance(mode, human, actualScope, jdbc, transactions,
            request, new KeycloakHumanAdminMaintenance(http, actors, broker, codec,
                "https://internal-auth.example.test", "test", "realm-1"), CLOCK, ISSUER);
        var generic = mock(IdentityProfileUpdater.class);
        var repository = mock(AdminRepository.class);
        var retrieve = mock(de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService.class);
        when(retrieve.findAdmin(ACCOUNT, type)).thenAnswer(ignored -> localAdmin(type));
        var api = new de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService(
            generic,
            mock(de.caritas.cob.userservice.api.admin.service.consultant.validation.UserAccountInputValidator.class),
            repository, retrieve);
        api.setScopedAdminMaintenance(mode, service);
        readReply();
        server.expect(requestTo(URL + "/profile")).andRespond(profileReceipt(null));
        readReply(true);
        de.caritas.cob.userservice.api.model.Admin result;
        if (put && agency) {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO();
          dto.setFirstname("New");dto.setLastname("Name");dto.setEmail("new@example.test");
          result = api.updateAgencyAdmin(ACCOUNT, dto);
        } else if (put) {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO();
          dto.setTenantId(7);dto.setFirstname("New");dto.setLastname("Name");dto.setEmail("new@example.test");
          result = api.updateTenantAdmin(ACCOUNT, dto);
        } else {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO();
          dto.setFirstname("New");dto.setLastname("Name");dto.setEmail("new@example.test");
          result = agency ? api.patchAgencyAdmin(ACCOUNT, dto) : api.patchTenantAdmin(ACCOUNT, dto);
        }
        assertEquals("New", jdbc.queryForObject("select first_name from admin where admin_id=?", String.class, ACCOUNT));
        assertNotNull(result);
        assertEquals("New", result.getFirstName());
        assertTrue(result.isManagedPaused());
        assertEquals(7L, result.getTenantId());
        assertEquals("original-admin", result.getUsername());
        assertEquals("APPLIED", jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
        verifyNoInteractions(generic);
        verify(repository, never()).save(any());
        server.verify();
      }
    }
  }

  @Test
  void unknownNativeReplyRetainsOriginalCommandAndSameKeyRollsForwardWithoutCasRefresh() {
    readReply();
    AtomicReference<String> first = new AtomicReference<>();
    server
        .expect(requestTo(URL + "/profile"))
        .andExpect(r -> first.set(((MockClientHttpRequest) r).getBodyAsString()))
        .andRespond(withException(new IOException("synthetic lost response")));
    server
        .expect(requestTo(URL + "/profile"))
        .andExpect(r -> assertEquals(first.get(), ((MockClientHttpRequest) r).getBodyAsString()))
        .andRespond(profileReceipt(null));
    readReply(true);
    assertThrows(RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
    assertEquals("Old", jdbc.queryForObject("select first_name from admin", String.class));
    assertEquals(
        List.of("FROZEN"),
        jdbc.queryForList("select status from general_admin_identity_intent", String.class));
    assertDoesNotThrow(() -> service.updateAdminProfile(ACCOUNT, desired()));
    assertEquals(
        1,
        jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
    server.verify();
  }

  @Test
  void changedRequestOnSameKeyRefusesBeforeAnotherNativeCallOrSqlWrite() {
    readReply();
    server
        .expect(requestTo(URL + "/profile"))
        .andRespond(withException(new IOException("synthetic lost response")));
    assertThrows(RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
    assertThrows(
        RuntimeException.class,
        () ->
            service.updateAdminProfile(
                ACCOUNT,
                new IdentityProfileUpdate(
                    "original-admin", "other@example.test", 7L, "Other", "Name")));
    assertEquals("Old", jdbc.queryForObject("select first_name from admin", String.class));
    assertEquals(
        List.of("FROZEN"),
        jdbc.queryForList("select status from general_admin_identity_intent", String.class));
    server.verify();
  }

  @Test
  void actualAdminScopeRefusalAndMismatchedImmutableProfileNeverReachNative() {
    doThrow(new IllegalStateException("synthetic out of scope")).when(scope).assertMay(any());
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    reset(scope);
    assertAll(
        () ->
            assertThrows(
                RuntimeException.class,
                () ->
                    service.updateAdminProfile(
                        ACCOUNT,
                        new IdentityProfileUpdate(
                            "foreign", "new@example.test", 7L, "New", "Name"))),
        () ->
            assertThrows(
                RuntimeException.class,
                () ->
                    service.updateAdminProfile(
                        ACCOUNT,
                        new IdentityProfileUpdate(
                            "original-admin", "new@example.test", 8L, "New", "Name"))),
        () ->
            assertThrows(
                RuntimeException.class,
                () -> service.read("60000000-0000-4000-8000-000000000099")));
    verifyNoInteractions(broker);
    server.verify();
  }

  @Test
  void missingAuthorityWrongIssuerPrincipalOrBearerAndAmbientSqlRefuseBeforeNative() {
    human.setGrantedAuthorities(Set.of());
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    human.setGrantedAuthorities(Set.of(Authority.AuthorityValue.TENANT_ADMIN));
    request.setUserPrincipal(new JwtAuthenticationToken(
        authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L).getToken()));
    assertFalse(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    request.setUserPrincipal(
        authentication(
            "synthetic-current-human",
            "https://foreign.example.test/realms/test",
            HUMAN,
            "admin",
            7L));
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    request.setUserPrincipal(authentication("different", ISSUER, HUMAN, "admin", 7L));
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    request.setUserPrincipal(authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            tx -> assertThrows(RuntimeException.class, () -> service.read(ACCOUNT)));
    verifyNoInteractions(broker);
    server.verify();
  }

  @Test
  void nativeReceiptCannotOverwriteForeignSqlDrift() {
    readReply();
    server
        .expect(requestTo(URL + "/profile"))
        .andRespond(
            profileReceipt(
                () ->
                    jdbc.update(
                        "update admin set first_name='Foreign' where admin_id=?", ACCOUNT)));
    assertThrows(RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
    assertEquals("Foreign", jdbc.queryForObject("select first_name from admin", String.class));
    assertNotEquals(
        "APPLIED",
        jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
    server.verify();
  }

  @Test
  void privateCheckedHandleClosesAfterReturnAndCannotCrossThreads() throws Exception {
    AtomicReference<HumanAdminAccountMaintenance.CheckedAccount> escaped = new AtomicReference<>();
    var transport = mock(HumanAdminMaintenanceTransport.class);
    when(transport.read(any()))
        .thenAnswer(
            call -> {
              var checked = call.getArgument(0, HumanAdminAccountMaintenance.CheckedAccount.class);
              checked.requireCurrent();
              assertEquals(ACCOUNT, checked.accountId());
              assertEquals(HUMAN, checked.humanSubject());
              assertEquals(7L, checked.tenantId());
              escaped.set(checked);
              AtomicReference<Throwable> refused = new AtomicReference<>();
              var thread =
                  new Thread(
                      () -> {
                        try {
                          checked.requireCurrent();
                        } catch (Throwable failure) {
                          refused.set(failure);
                        }
                      });
              thread.start();
              thread.join();
              assertNotNull(refused.get());
              return new HumanAdminMaintenanceTransport.Projection(
                  ACCOUNT,
                  "original-admin",
                  "old@example.test",
                  "Old",
                  "Name",
                  true,
                  true,
                  7L,
                  "de",
                  List.of("tenant-admin"),
                  false,
                  "a".repeat(64));
            });
    assertDoesNotThrow(() -> service(transport).read(ACCOUNT));
    assertThrows(RuntimeException.class, () -> escaped.get().requireCurrent());
    assertTrue(
        Arrays.stream(HumanAdminAccountMaintenance.CheckedAccount.class.getDeclaredConstructors())
            .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
    assertFalse(escaped.get().toString().contains("synthetic-current-human"));
  }

  private de.caritas.cob.userservice.api.model.Admin localAdmin(
      de.caritas.cob.userservice.api.model.Admin.AdminType type) {
    var row = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    return de.caritas.cob.userservice.api.model.Admin.builder()
        .id(ACCOUNT).tenantId(((Number) row.get("tenant_id")).longValue())
        .username((String) row.get("username")).type(type)
        .firstName((String) row.get("first_name"))
        .lastName((String) row.get("last_name"))
        .email((String) row.get("email"))
        .managedPaused((Boolean) row.get("managed_paused")).build();
  }

  private HumanAdminAccountMaintenance service(HumanAdminMaintenanceTransport transport) {
    return new HumanAdminAccountMaintenance(
        mode, human, scope, jdbc, transactions, request, transport, CLOCK, ISSUER);
  }

  private IdentityProfileUpdate desired() {
    return new IdentityProfileUpdate("original-admin", "new@example.test", 7L, "New", "Name");
  }

  private JwtAuthenticationToken authentication(
      String token, String issuer, String subject, String client, Long tenant) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue(token)
            .header("alg", "RS256")
            .issuer(issuer)
            .subject(subject)
            .issuedAt(CLOCK.instant().minusSeconds(30))
            .expiresAt(CLOCK.instant().plusSeconds(300))
            .claim("azp", client)
            .claim("tenantId", tenant.toString())
            .build(),
        human.getGrantedAuthorities().stream()
            .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
            .toList());
  }

  private void readReply() {
    readReply(false);
  }

  private void readReply(boolean applied) {
    server
        .expect(requestTo(URL + "/read"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer synthetic-checked-admin"))
        .andExpect(header("X-ORISO-Human-Authorization", "Bearer synthetic-current-human"))
        .andExpect(
            r -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              JsonNode body = json.readTree(((MockClientHttpRequest) r).getBodyAsString());
              verifyProof(r.getHeaders().getFirst("X-ORISO-Task-Context"), "read", body);
              assertEquals(Set.of("target"), fieldNames(body));
              assertEquals(ACCOUNT, body.path("target").path("accountId").textValue());
              assertEquals("7", body.path("target").path("tenantId").textValue());
              assertEquals(
                  "HUMAN_ADMIN", body.path("target").path("origin").path("kind").textValue());
              assertEquals(
                  "PROTECTED_ACCOUNT_OPERATIONS_V1",
                  body.path("target").path("origin").path("humanAuthority").textValue());
            })
        .andRespond(
            r -> {
              var projection = projection(applied);
              var response =
                  withSuccess(codec.canonical(projection), MediaType.APPLICATION_JSON)
                      .createResponse(r);
              response.getHeaders().setCacheControl("no-store");
              return response;
            });
  }

  private com.fasterxml.jackson.databind.node.ObjectNode projection(boolean applied) {
    var body = codec.read("{}", com.fasterxml.jackson.databind.node.ObjectNode.class);
    body.put("id", ACCOUNT);
    body.put("username", "original-admin");
    body.put("email", applied ? "new@example.test" : "old@example.test");
    body.put("firstName", applied ? "New" : "Old");
    body.put("lastName", "Name");
    body.put("enabled", true);
    body.put("emailVerified", true);
    body.put("tenantId", "7");
    body.put("preferredLanguage", "de");
    body.putArray("effectiveRoles").add("tenant-admin");
    body.put("passwordChangeRequired", false);
    body.put("state", "a".repeat(64));
    return body;
  }

  private ResponseCreator profileReceipt(Runnable beforeReply) {
    return request -> {
      try {
        assertFalse(
            org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive());
        JsonNode body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        verifyProof(request.getHeaders().getFirst("X-ORISO-Task-Context"), "profile", body);
        assertEquals(OPERATION, body.path("operationId").textValue());
        assertEquals("a".repeat(64), body.path("expectedState").textValue());
        assertEquals(Set.of("firstName", "lastName", "email"), fieldNames(body.path("change")));
        assertEquals("new@example.test", body.path("change").path("email").textValue());
        var outcome = new TreeMap<String, Object>();
        outcome.put("realmId", "realm-1");
        outcome.put("accountId", ACCOUNT);
        outcome.put("operationId", OPERATION);
        outcome.put("command", "profile");
        outcome.put("ownerClient", actor.clientId());
        outcome.put("ownerSubject", SUBJECT);
        outcome.put("intentDigest", digest("maintenance-intent", body));
        outcome.put("previousState", "a".repeat(64));
        outcome.put("tenantId", "7");
        outcome.put(
            "originDigest", digest("maintenance-origin", body.path("target").path("origin")));
        outcome.put("result", "UPDATED");
        String encoded =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
        String receipt = encoded + "." + mac("maintenance-operation-receipt\n" + encoded);
        if (beforeReply != null) beforeReply.run();
        var response =
            withSuccess(
                    codec.canonical(
                        Map.of(
                            "operationId",
                            OPERATION,
                            "accountId",
                            ACCOUNT,
                            "result",
                            "UPDATED",
                            "receipt",
                            receipt)),
                    MediaType.APPLICATION_JSON)
                .createResponse(request);
        response.getHeaders().setCacheControl("no-store");
        return response;
      } catch (Exception failure) {
        throw new IOException(failure);
      }
    };
  }

  private void verifyProof(String proof, String command, JsonNode body) throws IOException {
    try {
      String[] parts = proof.split("\\.", -1);
      assertEquals(2, parts.length);
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(KEY, "HmacSHA256"));
      assertArrayEquals(
          hmac.doFinal(("proof\n" + parts[0]).getBytes(StandardCharsets.UTF_8)),
          Base64.getUrlDecoder().decode(parts[1]));
      JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
      assertEquals(
          Set.of(
              "iss",
              "aud",
              "purpose",
              "operation",
              "method",
              "actor",
              "realm",
              "target",
              "taskClient",
              "taskSubject",
              "payloadDigest",
              "jti",
              "nonce",
              "iat",
              "exp"),
          fieldNames(claims));
      assertEquals("userservice", claims.path("iss").textValue());
      assertEquals("oriso-account-maintenance", claims.path("aud").textValue());
      assertEquals("ACCOUNT_MAINTENANCE", claims.path("purpose").textValue());
      assertEquals("account." + command, claims.path("operation").textValue());
      assertEquals("POST", claims.path("method").textValue());
      assertEquals("ACCOUNT_ADMIN", claims.path("actor").textValue());
      assertEquals("test", claims.path("realm").textValue());
      assertEquals("realm/realm-1/account/" + ACCOUNT, claims.path("target").textValue());
      assertEquals(actor.clientId(), claims.path("taskClient").textValue());
      assertEquals(SUBJECT, claims.path("taskSubject").textValue());
      assertEquals(digest("payload", body), claims.path("payloadDigest").textValue());
      assertEquals(
          UUID.fromString(claims.path("jti").textValue()).toString(),
          claims.path("jti").textValue());
      assertTrue(claims.path("nonce").textValue().matches("[0-9a-f]{32}"));
      assertTrue(claims.path("iat").isIntegralNumber());
      assertEquals(60, claims.path("exp").longValue() - claims.path("iat").longValue());
    } catch (Exception invalid) {
      throw new IOException("Synthetic proof verification failed", invalid);
    }
  }

  private String digest(String domain, Object value) throws Exception {
    return mac(domain + "\n" + codec.canonical(value));
  }

  private String mac(String text) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
  }

  private Set<String> fieldNames(JsonNode node) {
    Set<String> fields = new HashSet<>();
    node.fieldNames().forEachRemaining(fields::add);
    return fields;
  }
}
