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
        "create table admin(admin_id varchar(36) primary key,tenant_id bigint,username varchar(255) not null,type varchar(6) not null,first_name varchar(255),last_name varchar(255),email varchar(255),managed_paused boolean not null,update_date timestamp,id_old bigint,create_date timestamp)");
    jdbc.execute(
        "create table consultant(consultant_id varchar(36) primary key,tenant_id bigint,username varchar(255),first_name varchar(255),last_name varchar(255),email varchar(255),delete_date timestamp,update_date timestamp,managed_paused boolean not null,is_absent boolean not null default false,two_factor_required boolean,status varchar(11),deletion_lifecycle_state varchar(32),create_date timestamp)");
    jdbc.execute(
        "create table user(user_id varchar(36) primary key,tenant_id bigint,delete_date timestamp,email varchar(255))");
    jdbc.execute("create table language(consultant_id varchar(36),language_code varchar(2))");
    jdbc.execute(
        "create table consultant_topic(consultant_id varchar(36),topic_id bigint,agency_id bigint)");
    jdbc.execute("create table consultant_agency(consultant_id varchar(36),agency_id bigint)");
    jdbc.execute("create table admin_agency(admin_id varchar(36),agency_id bigint)");
    jdbc.update(
        "insert into admin(admin_id,tenant_id,username,type,first_name,last_name,email,managed_paused,update_date) values(?,7,'original-admin','TENANT','Old','Name','old@example.test',true,?)",
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
    request.removeHeader("Idempotency-Key");
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
        var type =
            agency
                ? de.caritas.cob.userservice.api.model.Admin.AdminType.AGENCY
                : de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT;
        jdbc.update("update admin set type=? where admin_id=?", type.name(), ACCOUNT);
        human.setGrantedAuthorities(
            Set.of(
                agency
                    ? Authority.AuthorityValue.USER_ADMIN
                    : Authority.AuthorityValue.TENANT_ADMIN));
        request.setUserPrincipal(
            authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
        request.setMethod(put ? "PUT" : "PATCH");
        request.setRequestURI(
            "/service/useradmin/" + (agency ? "agencyadmins/" : "tenantadmins/") + ACCOUNT);
        assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
        human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
        var actualScope = actualScope(type);
        assertEquals(new AdminScope.Tenant(7L), actualScope.current());
        assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
        jdbc.update("update admin set tenant_id=8 where admin_id=?", ACCOUNT);
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
            () -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
        jdbc.update("update admin set tenant_id=7 where admin_id=?", ACCOUNT);
        assertEquals(7L, localAdmin(type).getTenantId());
        service =
            new HumanAdminAccountMaintenance(
                mode,
                human,
                actualScope,
                jdbc,
                transactions,
                request,
                new KeycloakHumanAdminMaintenance(
                    http,
                    actors,
                    broker,
                    codec,
                    "https://internal-auth.example.test",
                    "test",
                    "realm-1"),
                CLOCK,
                ISSUER);
        var generic = mock(IdentityProfileUpdater.class);
        var repository = mock(AdminRepository.class);
        var retrieve =
            mock(
                de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService
                    .class);
        when(retrieve.findAdmin(ACCOUNT, type)).thenAnswer(ignored -> localAdmin(type));
        var api =
            new de.caritas.cob.userservice.api.admin.service.admin.update.UpdateAdminService(
                generic,
                mock(
                    de.caritas.cob.userservice.api.admin.service.consultant.validation
                        .UserAccountInputValidator.class),
                repository,
                retrieve);
        api.setScopedAdminMaintenance(mode, service);
        readReply();
        server.expect(requestTo(URL + "/profile")).andRespond(profileReceipt(null));
        readReply(true);
        de.caritas.cob.userservice.api.model.Admin result;
        if (put && agency) {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.UpdateAgencyAdminDTO();
          dto.setFirstname("New");
          dto.setLastname("Name");
          dto.setEmail("new@example.test");
          result = api.updateAgencyAdmin(ACCOUNT, dto);
        } else if (put) {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantAdminDTO();
          dto.setTenantId(7);
          dto.setFirstname("New");
          dto.setLastname("Name");
          dto.setEmail("new@example.test");
          result = api.updateTenantAdmin(ACCOUNT, dto);
        } else {
          var dto = new de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO();
          dto.setFirstname("New");
          dto.setLastname("Name");
          dto.setEmail("new@example.test");
          result = agency ? api.patchAgencyAdmin(ACCOUNT, dto) : api.patchTenantAdmin(ACCOUNT, dto);
        }
        assertEquals(
            "New",
            jdbc.queryForObject(
                "select first_name from admin where admin_id=?", String.class, ACCOUNT));
        assertNotNull(result);
        assertEquals("New", result.getFirstName());
        assertTrue(result.isManagedPaused());
        assertEquals(7L, result.getTenantId());
        assertEquals("original-admin", result.getUsername());
        assertEquals(
            "APPLIED",
            jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
        verifyNoInteractions(generic);
        verify(repository, never()).save(any());
        server.verify();
      }
    }
  }

  @Test
  void sameAccountAdminConsultantRequiresOneCurrentTenantAndOneProfilePostcondition() {
    human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    jdbc.update(
        "insert into consultant(consultant_id,tenant_id,username,first_name,last_name,email,delete_date,update_date,managed_paused) values(?,7,'original-admin','Old','Name','old@example.test',null,?,true)",
        ACCOUNT,
        java.sql.Timestamp.from(CLOCK.instant()));
    var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
    jdbc.update("update consultant set tenant_id=8 where consultant_id=?", ACCOUNT);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
    jdbc.update("update consultant set tenant_id=7 where consultant_id=?", ACCOUNT);
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    readReply();
    server.expect(requestTo(URL + "/profile")).andRespond(profileReceipt(null));
    readReply(true);
    assertDoesNotThrow(() -> service.updateAdminProfile(ACCOUNT, desired()));
    assertEquals("New", jdbc.queryForObject("select first_name from admin", String.class));
    assertEquals("New", jdbc.queryForObject("select first_name from consultant", String.class));
    assertEquals(
        "new@example.test", jdbc.queryForObject("select email from consultant", String.class));
    assertEquals(true, jdbc.queryForObject("select managed_paused from consultant", Boolean.class));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
    server.verify();
    jdbc.update("update consultant set tenant_id=8 where consultant_id=?", ACCOUNT);
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    jdbc.update(
        "update consultant set tenant_id=7,delete_date=? where consultant_id=?",
        java.sql.Timestamp.from(CLOCK.instant()),
        ACCOUNT);
    assertThrows(RuntimeException.class, () -> service.read(ACCOUNT));
    server.verify();
  }

  @Test
  void consultantOnlyReadRequiresItsOwnCurrentSqlRowAndScopeWithoutAdminAdoption() {
    human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    human.setGrantedAuthorities(Set.of(Authority.AuthorityValue.USER_ADMIN));
    request.setUserPrincipal(authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    jdbc.update("delete from admin where admin_id=?", ACCOUNT);
    jdbc.update(
        "insert into consultant(consultant_id,tenant_id,username,first_name,last_name,email,delete_date,update_date,managed_paused) values(?,7,'original-admin','Old','Name','old@example.test',null,?,true)",
        ACCOUNT,
        java.sql.Timestamp.from(CLOCK.instant()));
    var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    assertEquals(new AdminScope.Tenant(7L), actualScope.current());
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
    jdbc.update("update consultant set tenant_id=8 where consultant_id=?", ACCOUNT);
    assertThrows(
        de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
        () -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
    jdbc.update("update consultant set tenant_id=7 where consultant_id=?", ACCOUNT);
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    readReply();
    var result = assertDoesNotThrow(() -> service.read(ACCOUNT));
    assertEquals(ACCOUNT, result.id());
    assertEquals(7L, result.tenantId());
    assertTrue(result.enabled());
    assertThrows(RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
    assertEquals(
        0,
        jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
    assertEquals(0, jdbc.queryForObject("select count(*) from admin", Integer.class));
    server.verify();
  }

  @Test
  void actualGenericStatusAdapterRequiresThePrivateCurrentHumanSqlReadBeforeNative() {
    human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    assertEquals(new AdminScope.Tenant(7L), actualScope.current());
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    var legacy = mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient.class);
    var users = mock(org.keycloak.admin.client.resource.UsersResource.class);
    var user = mock(org.keycloak.admin.client.resource.UserResource.class);
    when(legacy.getUsersResource()).thenReturn(users);
    when(users.get(ACCOUNT)).thenReturn(user);
    var misleading = new org.keycloak.representations.idm.UserRepresentation();
    misleading.setEnabled(false);
    when(user.toRepresentation()).thenReturn(misleading);
    var adapter =
        new de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService(
            human,
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .UserAccountInputValidator.class),
            mode,
            legacy,
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper.class),
            mock(de.caritas.cob.userservice.api.helper.UserHelper.class),
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient.class));
    adapter.setHumanAdminMaintenance(service);
    readReply();
    assertEquals(Optional.of(true), assertDoesNotThrow(() -> adapter.findEnabledById(ACCOUNT)));
    verifyNoInteractions(legacy);
    server.verify();
  }

  @Test
  void actualStatusActivationReadsOutsideSqlAndCommitsOnlyTheSelectedManagementPause()
      throws Exception {
    for (String scenario : List.of("single", "dual", "drift", "foreign-secondary", "ambient")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
      if (scenario.equals("dual") || scenario.equals("foreign-secondary")) {
        jdbc.update(
            "insert into consultant(consultant_id,tenant_id,username,first_name,last_name,email,managed_paused,status) values(?,?,?,'Consultant','Name','old@example.test',true,'CREATED')",
            ACCOUNT,
            scenario.equals("dual") ? 7L : 8L,
            "original-admin");
      }
      var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
      assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
      assertEquals(new AdminScope.Tenant(7L), actualScope.current());
      assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
      var publicStatus = actualStatusService(actualScope, scenario.equals("drift"));
      if (scenario.equals("ambient")) {
        assertThrows(
            RuntimeException.class,
            () ->
                new TransactionTemplate(transactions)
                    .execute(ignored -> publicStatus.update(ACCOUNT, true)));
      } else if (scenario.equals("foreign-secondary")) {
        assertThrows(RuntimeException.class, () -> publicStatus.update(ACCOUNT, true));
      } else {
        readReply();
        if (scenario.equals("drift")) {
          assertThrows(RuntimeException.class, () -> publicStatus.update(ACCOUNT, true));
          assertEquals(
              "Foreign",
              jdbc.queryForObject(
                  "select first_name from admin where admin_id=?", String.class, ACCOUNT));
        } else {
          readReply();
          var result = assertDoesNotThrow(() -> publicStatus.update(ACCOUNT, true));
          assertEquals(true, result.active());
          assertEquals(true, result.loginEnabled());
          assertFalse(result.paused());
          assertEquals(
              "Old",
              jdbc.queryForObject(
                  "select first_name from admin where admin_id=?", String.class, ACCOUNT));
          if (scenario.equals("dual")) {
            assertEquals(
                false,
                jdbc.queryForObject(
                    "select managed_paused from consultant where consultant_id=?",
                    Boolean.class,
                    ACCOUNT));
            assertEquals(
                "Consultant",
                jdbc.queryForObject(
                    "select first_name from consultant where consultant_id=?",
                    String.class,
                    ACCOUNT));
          }
        }
      }
      boolean shouldRemainPaused = !Set.of("single", "dual").contains(scenario);
      assertEquals(
          shouldRemainPaused,
          jdbc.queryForObject(
              "select managed_paused from admin where admin_id=?", Boolean.class, ACCOUNT));
      assertEquals(
          "ACTIVE",
          jdbc.queryForObject(
              "select status from account_inactivity where identity_id=?", String.class, ACCOUNT));
      server.verify();
    }
  }

  @Test
  void actualPublicConsultantEditUsesScopedProfileAndCommitsLocalDataAfterNativeAck() {
    for (String scenario :
        List.of(
            "single",
            "dual",
            "unknown",
            "changed-body",
            "rollback",
            "drift",
            "foreign-secondary",
            "replay")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void actualPublicConsultantRoleAndProfileUseOneAtomicReviewedCommand() throws Exception {
    for (String scenario :
        List.of(
            "roles-add",
            "roles-remove",
            "roles-unknown",
            "roles-replay",
            "roles-missing",
            "roles-preserve")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      var ddl =
          new org.springframework.core.io.ClassPathResource(
                  "db/changelog/changeset/20260916_account_inactivity/migrate.sql")
              .getContentAsString(StandardCharsets.UTF_8);
      jdbc.execute(ddl.substring(ddl.indexOf("CREATE TABLE"), ddl.indexOf("-- No trustworthy")));
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void actualSelfConsultantProfileKeepsTheWholeCallbackAndCanonicalDualOwner() {
    for (String scenario :
        List.of(
            "self-single",
            "self-dual",
            "self-agency",
            "self-single-tenant-dual",
            "self-platform",
            "self-unchanged",
            "self-unknown",
            "self-replay")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void actualSelfConsultantProfileCannotChangeAnotherIdentityOrRoles() {
    for (String scenario : List.of("self-foreign", "self-roles")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void actualSelfEmailRefusalNeedsBoundNativeTerminalBeforeAnyLocalCallback() {
    for (String scenario : List.of("self-signed403", "self-signed409", "self-unsigned403")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void actualSelfNativeFullRolesMustMatchTheConfiguredCanonicalSqlKind() {
    for (String scenario :
        List.of(
            "self-native-user",
            "self-native-combined",
            "self-native-agency-as-protected",
            "self-missing-catalog")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      runActualConsultantEdit(scenario);
    }
  }

  @Test
  void appliedSelfAndAdminReplayRechecksOriginalNativeGrantWithoutRepeatingLocalEffects() {
    assertAll(
        () -> {
          server.reset();
          actualSchemaAndValidatedHumanFixture();
          runActualConsultantEdit("self-replay-revoked");
        },
        () -> {
          server.reset();
          actualSchemaAndValidatedHumanFixture();
          runActualConsultantEdit("replay-revoked");
        });
  }

  private void runActualConsultantEdit(String scenario) {
    boolean self = scenario.startsWith("self-");
    if (!Set.of(
            "dual",
            "foreign-secondary",
            "self-dual",
            "self-agency",
            "self-single-tenant-dual",
            "self-native-agency-as-protected",
            "self-platform")
        .contains(scenario)) jdbc.update("delete from admin where admin_id=?", ACCOUNT);
    jdbc.update(
        "insert into consultant(consultant_id,tenant_id,username,first_name,last_name,email,managed_paused,status) values(?,7,'original-admin','Old','Name','old@example.test',true,'CREATED')",
        ACCOUNT);
    if ("foreign-secondary".equals(scenario))
      jdbc.update("update admin set tenant_id=8 where admin_id=?", ACCOUNT);
    if ("self-agency".equals(scenario))
      jdbc.update("update admin set type='AGENCY' where admin_id=?", ACCOUNT);
    if ("self-platform".equals(scenario)) {
      jdbc.update("update admin set tenant_id=0,type='SUPER' where admin_id=?", ACCOUNT);
      jdbc.update("update consultant set tenant_id=0 where consultant_id=?", ACCOUNT);
    }
    human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    human.setGrantedAuthorities(
        Set.of(Authority.AuthorityValue.TENANT_ADMIN, Authority.AuthorityValue.USER_ADMIN));
    request.setUserPrincipal(authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
    request.setMethod("PUT");
    request.setRequestURI("/service/useradmin/consultants/" + ACCOUNT);
    var actualScope =
        self ? scope : actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    if (self) {
      human.setUserId("self-foreign".equals(scenario) ? HUMAN : ACCOUNT);
      human.setTenantId("self-platform".equals(scenario) ? 0L : 7L);
      human.setRoles(Set.of(UserRole.CONSULTANT.getValue()));
      human.setGrantedAuthorities(Set.of());
      request.setUserPrincipal(
          authentication(
              "synthetic-current-human", ISSUER, human.getUserId(), "app", human.getTenantId()));
      request.setRequestURI("/service/users/data");
      doThrow(new AssertionError("SELF never obtains human AdminScope"))
          .when(scope)
          .assertMay(any());
    } else {
      assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
      assertEquals(new AdminScope.Tenant(7L), actualScope.current());
      assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
      jdbc.update("update consultant set tenant_id=8 where consultant_id=?", ACCOUNT);
      assertThrows(
          RuntimeException.class,
          () -> actualScope.assertMay(AdminScope.Target.counsellor(ACCOUNT)));
      jdbc.update("update consultant set tenant_id=7 where consultant_id=?", ACCOUNT);
    }
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    if (self && !"self-missing-catalog".equals(scenario))
      service.configureReviewedSelfProfileRoles(
          Map.of(
              "CONSULTANT",
                  List.of(Set.of("consultant"), Set.of("consultant", "group-chat-consultant")),
              "AGENCY_ADMIN",
                  List.of(
                      Set.of("consultant", "restricted-agency-admin", "user-admin"),
                      Set.of(
                          "consultant",
                          "group-chat-consultant",
                          "restricted-agency-admin",
                          "user-admin")),
              "TENANT_ADMIN",
                  List.of(
                      Set.of("consultant", "tenant-admin"),
                      Set.of("consultant", "single-tenant-admin")),
              "PLATFORM_ADMIN", List.of(Set.of("consultant", "tenant-admin"))));
    boolean roleEdit = scenario.startsWith("roles-");
    if (roleEdit && !"roles-missing".equals(scenario))
      service.configureReviewedOrdinaryGroups(
          Map.of(
              "/human-roles/test-consultant-basic", Set.of("consultant"),
              "/human-roles/test-consultant-chat", Set.of("consultant", "group-chat-consultant")));
    var adapter =
        new de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService(
            human,
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .UserAccountInputValidator.class),
            mode,
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient.class),
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper.class),
            mock(de.caritas.cob.userservice.api.helper.UserHelper.class),
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient.class));
    adapter.setHumanAdminMaintenance(service);
    var consultants = mock(de.caritas.cob.userservice.api.service.ConsultantService.class);
    when(consultants.getConsultant(ACCOUNT)).thenAnswer(ignored -> Optional.of(localConsultant()));
    var failWrite = new java.util.concurrent.atomic.AtomicBoolean("rollback".equals(scenario));
    when(consultants.saveConsultant(any()))
        .thenAnswer(
            invocation -> {
              assertTrue(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive());
              de.caritas.cob.userservice.api.model.Consultant updated = invocation.getArgument(0);
              if (failWrite.getAndSet(false))
                throw new IllegalStateException("synthetic local callback failure");
              jdbc.update(
                  "update consultant set first_name=?,last_name=?,email=?,is_absent=?,update_date=? where consultant_id=?",
                  updated.getFirstName(),
                  updated.getLastName(),
                  updated.getEmail(),
                  updated.isAbsent(),
                  java.sql.Timestamp.valueOf(updated.getUpdateDate()),
                  ACCOUNT);
              return updated;
            });
    var updater =
        new de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService(
            adapter,
            adapter,
            consultants,
            mock(de.caritas.cob.userservice.api.service.ConsultantPublicSlugService.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .UserAccountInputValidator.class),
            mock(MatrixUserClient.class),
            mock(de.caritas.cob.userservice.api.service.appointment.AppointmentService.class),
            mock(SessionRepository.class),
            mock(
                de.caritas.cob.userservice.api.service.notification.EventNotificationService.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .ConsultantTopicAgencyCompatibilityValidator.class),
            new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver());
    updater.setScopedAdminMaintenance(mode, service);
    var proxy = new org.springframework.aop.framework.ProxyFactory(updater);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactions,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    var publicEdit =
        (de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService)
            proxy.getProxy();
    var dto =
        self
            ? new de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper()
                .updateAdminConsultantOf(
                    new de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO()
                        .firstname("New")
                        .lastname("Name")
                        .email("new@example.test")
                        .publicSlug("requested-self-slug"),
                    localConsultant())
            : new de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO()
                .firstname("New")
                .lastname("Name")
                .email("new@example.test")
                .formalLanguage(true)
                .absent(true);
    if (roleEdit) dto.setIsGroupchatConsultant(!"roles-remove".equals(scenario));
    if ("self-unchanged".equals(scenario)) dto.setEmail("old@example.test");
    if ("self-roles".equals(scenario)) dto.setIsGroupchatConsultant(true);
    if (Set.of("self-foreign", "self-roles").contains(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, false));
      assertEquals(
          0,
          jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
      verifyNoInteractions(broker);
      server.verify();
      return;
    }
    if ("roles-missing".equals(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertEquals(
          0,
          jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
      server.verify();
      return;
    }
    if ("foreign-secondary".equals(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertEquals(
          0,
          jdbc.queryForObject("select count(*) from general_admin_identity_intent", Integer.class));
      server.verify();
      return;
    }
    List<String> originalRoles =
        "roles-preserve".equals(scenario)
            ? List.of("consultant", "tenant-admin")
            : "roles-remove".equals(scenario)
                ? List.of("consultant", "group-chat-consultant")
                : List.of("consultant");
    if ("self-native-user".equals(scenario)) originalRoles = List.of("user");
    if (Set.of("self-native-combined", "self-native-agency-as-protected").contains(scenario))
      originalRoles = List.of("consultant", "restricted-agency-admin", "user-admin");
    if ("self-single-tenant-dual".equals(scenario))
      originalRoles = List.of("consultant", "single-tenant-admin");
    final List<String> projectedRoles = originalRoles;
    boolean coherenceNegative =
        scenario.startsWith("self-native-") || "self-missing-catalog".equals(scenario);
    List<String> desiredRoles =
        "roles-remove".equals(scenario)
            ? List.of("consultant")
            : List.of("consultant", "group-chat-consultant");
    String command =
        self && !"self-unchanged".equals(scenario)
            ? "email"
            : roleEdit ? "ordinary-profile" : "profile";
    readReply(
        false,
        roleEdit || coherenceNegative || "self-single-tenant-dual".equals(scenario)
            ? projectedRoles
            : null,
        self,
        "self-unchanged".equals(scenario));
    if ("roles-preserve".equals(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertEquals(
          "Old",
          jdbc.queryForObject(
              "select first_name from consultant where consultant_id=?", String.class, ACCOUNT));
      server.verify();
      return;
    }
    var frozen = new AtomicReference<String>();
    var mutations = new java.util.concurrent.atomic.AtomicInteger();
    var mutation =
        server
            .expect(requestTo(URL + "/" + command))
            .andExpect(
                r -> {
                  mutations.incrementAndGet();
                  frozen.set(((MockClientHttpRequest) r).getBodyAsString());
                  if (self) {
                    var body = json.readTree(frozen.get());
                    assertEquals(
                        "SELF", body.path("target").path("origin").path("kind").textValue());
                    assertEquals(
                        ACCOUNT,
                        body.path("target").path("origin").path("humanSubject").textValue());
                    var fields = new HashSet<String>();
                    body.path("change").fieldNames().forEachRemaining(fields::add);
                    assertEquals(Set.of("email", "firstName", "lastName"), fields);
                    assertFalse(
                        org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    assertEquals(
                        "Old",
                        jdbc.queryForObject("select first_name from consultant", String.class));
                  }
                });
    if (scenario.startsWith("self-signed") || scenario.startsWith("self-unsigned")) {
      int status = scenario.endsWith("409") ? 409 : 403;
      boolean signed = scenario.startsWith("self-signed");
      mutation.andRespond(noEffectReply(status, scenario.substring(5), command));
      var failure =
          assertThrows(
              RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, false));
      assertDoesNotThrow(() -> assertPublicMutationOutcome(failure, status, signed));
      assertEquals("Old", jdbc.queryForObject("select first_name from consultant", String.class));
      assertEquals(
          "old@example.test", jdbc.queryForObject("select email from consultant", String.class));
      assertEquals(
          signed ? "NO_EFFECT" : "FROZEN",
          jdbc.queryForObject("select status from general_admin_identity_intent", String.class));
      verify(consultants, never()).saveConsultant(any());
      if (signed) {
        var replay =
            assertThrows(
                RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, false));
        assertDoesNotThrow(() -> assertPublicMutationOutcome(replay, status, true));
      }
      server.verify();
      return;
    }
    boolean unknown =
        Set.of("unknown", "changed-body", "roles-unknown", "self-unknown").contains(scenario);
    if (unknown)
      mutation.andRespond(withException(new IOException("synthetic lost profile response")));
    else
      mutation.andRespond(
          profileReceipt(
              command,
              roleEdit
                  ? ("roles-remove".equals(scenario)
                      ? "/human-roles/test-consultant-basic"
                      : "/human-roles/test-consultant-chat")
                  : null,
              "drift".equals(scenario)
                  ? () ->
                      jdbc.update(
                          "update consultant set is_absent=true where consultant_id=?", ACCOUNT)
                  : null,
              OPERATION,
              "self-unchanged".equals(scenario) ? "old@example.test" : "new@example.test"));
    if (!unknown && !"drift".equals(scenario))
      readReply(
          true,
          roleEdit ? desiredRoles : self ? projectedRoles : null,
          self,
          "self-unchanged".equals(scenario));
    if (unknown)
      server
          .expect(requestTo(URL + "/" + command))
          .andExpect(r -> assertEquals(frozen.get(), ((MockClientHttpRequest) r).getBodyAsString()))
          .andRespond(
              profileReceipt(command, roleEdit ? "/human-roles/test-consultant-chat" : null, null));
    if (unknown || "rollback".equals(scenario))
      readReply(
          true,
          roleEdit ? desiredRoles : self ? projectedRoles : null,
          self,
          "self-unchanged".equals(scenario));
    boolean revokedReplay = scenario.endsWith("replay-revoked");
    var authorityChecks = new java.util.concurrent.atomic.AtomicInteger();
    if (revokedReplay)
      server
          .expect(requestTo(URL + "/" + command))
          .andExpect(
              r -> {
                assertFalse(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
                assertEquals(frozen.get(), ((MockClientHttpRequest) r).getBodyAsString());
                authorityChecks.incrementAndGet();
              })
          .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN));
    if (coherenceNegative) {
      assertAll(
          () ->
              assertThrows(
                  RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, false)),
          () ->
              assertEquals(
                  0,
                  mutations.get(),
                  "Wrong or unconfigured SQL/native family must refuse before mutation"),
          () ->
              assertEquals(
                  "Old", jdbc.queryForObject("select first_name from consultant", String.class)),
          () ->
              assertEquals(
                  0,
                  jdbc.queryForObject(
                      "select count(*) from general_admin_identity_intent where status<>'PREPARED'",
                      Integer.class)),
          () -> verify(consultants, never()).saveConsultant(any()));
      // The prepared response proves old source would succeed; correct source never dispatches it.
      return;
    }
    if ("drift".equals(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertEquals(
          "Old",
          jdbc.queryForObject(
              "select first_name from consultant where consultant_id=?", String.class, ACCOUNT));
      assertEquals(
          true,
          jdbc.queryForObject(
              "select is_absent from consultant where consultant_id=?", Boolean.class, ACCOUNT));
      assertEquals(
          "FROZEN",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      server.verify();
      return;
    }
    if (unknown || "rollback".equals(scenario)) {
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertEquals(
          "Old",
          jdbc.queryForObject(
              "select first_name from consultant where consultant_id=?", String.class, ACCOUNT));
      assertEquals(
          false,
          jdbc.queryForObject(
              "select is_absent from consultant where consultant_id=?", Boolean.class, ACCOUNT));
      assertEquals(
          unknown ? "FROZEN" : "ACKNOWLEDGED",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      if ("changed-body".equals(scenario)) {
        dto.setAbsent(false);
        assertThrows(
            RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
        dto.setAbsent(true);
      }
    }
    var updated = assertDoesNotThrow(() -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
    assertEquals("New", updated.getFirstName());
    assertEquals(!self, updated.isAbsent());
    assertEquals(
        "New",
        jdbc.queryForObject(
            "select first_name from consultant where consultant_id=?", String.class, ACCOUNT));
    assertEquals(
        !self,
        jdbc.queryForObject(
            "select is_absent from consultant where consultant_id=?", Boolean.class, ACCOUNT));
    assertEquals(
        true,
        jdbc.queryForObject(
            "select managed_paused from consultant where consultant_id=?", Boolean.class, ACCOUNT));
    assertEquals(
        "APPLIED",
        jdbc.queryForObject(
            "select status from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    assertNotNull(
        jdbc.queryForObject(
            "select applied_json from general_admin_identity_intent where operation_id=?",
            String.class,
            OPERATION));
    if (revokedReplay) {
      String originalReceipt =
          jdbc.queryForObject(
              "select native_receipt from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION);
      var refusal =
          assertThrows(
              de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
              () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      assertDoesNotThrow(() -> assertPublicMutationOutcome(refusal, 403, false));
      assertEquals(1, authorityChecks.get());
      assertEquals(
          "APPLIED",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      assertEquals(
          originalReceipt,
          jdbc.queryForObject(
              "select native_receipt from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      verify(consultants, times(1)).saveConsultant(any());
      server.verify();
      return;
    }
    if (Set.of("dual", "self-dual", "self-agency", "self-single-tenant-dual", "self-platform")
        .contains(scenario)) {
      assertEquals(
          "New",
          jdbc.queryForObject(
              "select first_name from admin where admin_id=?", String.class, ACCOUNT));
      assertEquals(
          true,
          jdbc.queryForObject(
              "select managed_paused from admin where admin_id=?", Boolean.class, ACCOUNT));
    }
    if (Set.of("replay", "roles-replay", "self-replay").contains(scenario)) {
      assertEquals(
          "New",
          assertDoesNotThrow(() -> publicEdit.updateConsultant(ACCOUNT, dto, !self))
              .getFirstName());
      verify(consultants, times(1)).saveConsultant(any());
      jdbc.update("update consultant set is_absent=? where consultant_id=?", self, ACCOUNT);
      assertThrows(RuntimeException.class, () -> publicEdit.updateConsultant(ACCOUNT, dto, !self));
      verify(consultants, times(1)).saveConsultant(any());
    }
    server.verify();
  }

  @Test
  void onlySignedBoundNoEffectReleasesTheOriginalFrozenProfileAfterAnAuthenticatedFence()
      throws Exception {
    for (String scenario :
        List.of(
            "unsigned403",
            "unsigned409",
            "wrong-mac",
            "foreign-intent",
            "signed403",
            "signed409")) {
      server.reset();
      actualSchemaAndValidatedHumanFixture();
      var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
      assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
      assertEquals(new AdminScope.Tenant(7L), actualScope.current());
      assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
      service =
          new HumanAdminAccountMaintenance(
              mode,
              human,
              actualScope,
              jdbc,
              transactions,
              request,
              new KeycloakHumanAdminMaintenance(
                  http,
                  actors,
                  broker,
                  codec,
                  "https://internal-auth.example.test",
                  "test",
                  "realm-1"),
              CLOCK,
              ISSUER);
      int status = scenario.endsWith("403") ? 403 : 409;
      readReply();
      server.expect(requestTo(URL + "/profile")).andRespond(noEffectReply(status, scenario));
      var failure =
          assertThrows(
              RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
      boolean signed = scenario.startsWith("signed");
      assertEquals(
          signed ? "NO_EFFECT" : "FROZEN",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION),
          scenario);
      assertEquals(
          "Old",
          jdbc.queryForObject(
              "select first_name from admin where admin_id=?", String.class, ACCOUNT));
      server.verify();
      assertPublicMutationOutcome(failure, status, signed);
      if (!signed) continue;
      assertNotNull(
          jdbc.queryForObject(
              "select native_receipt from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      if (status == 403)
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class,
            () -> service.updateAdminProfile(ACCOUNT, desired()));
      else
        assertThrows(
            de.caritas.cob.userservice.api.exception.httpresponses.ConflictException.class,
            () -> service.updateAdminProfile(ACCOUNT, desired()));
      var replayFailure =
          assertThrows(
              RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
      assertPublicMutationOutcome(replayFailure, status, true);
      request.setUserPrincipal(authentication("revoked-current-human", ISSUER, HUMAN, "admin", 7L));
      var revoked =
          assertThrows(
              RuntimeException.class, () -> service.updateAdminProfile(ACCOUNT, desired()));
      assertPublicMutationOutcome(revoked, 403, false);
      assertEquals(
          "NO_EFFECT",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      request.setUserPrincipal(
          authentication("synthetic-current-human", ISSUER, HUMAN, "admin", 7L));
      server.verify();
      server.reset();
      String next = "81000000-0000-4000-8000-000000000008";
      request.removeHeader("Idempotency-Key");
      request.addHeader("Idempotency-Key", next);
      readReply();
      server
          .expect(requestTo(URL + "/profile"))
          .andRespond(profileReceipt("profile", null, null, next));
      readReply(true);
      assertDoesNotThrow(() -> service.updateAdminProfile(ACCOUNT, desired()));
      assertEquals(
          "APPLIED",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              next));
      assertEquals(
          "NO_EFFECT",
          jdbc.queryForObject(
              "select status from general_admin_identity_intent where operation_id=?",
              String.class,
              OPERATION));
      server.verify();
    }
  }

  private void assertPublicMutationOutcome(RuntimeException failure, int status, boolean definite)
      throws Exception {
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new MutationFailureBoundary(failure))
            .setControllerAdvice(
                new de.caritas.cob.userservice.api.adapters.web.controller.interceptor
                    .ApiResponseEntityExceptionHandler())
            .build();
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/profile-outcome")
                    .header("Idempotency-Key", OPERATION)
                    .requestAttr("X-ORISO-Mutation-Outcome", "NO_EFFECT")
                    .requestAttr("X-ORISO-Mutation-Operation", OPERATION))
            .andReturn()
            .getResponse();
    if (definite) {
      assertInstanceOf(HumanAdminAccountMaintenance.DefiniteProfileNoEffect.class, failure);
      assertEquals(
          UUID.fromString(OPERATION),
          ((HumanAdminAccountMaintenance.DefiniteProfileNoEffect) failure).operationId());
      assertTrue(
          Arrays.stream(failure.getClass().getDeclaredConstructors())
              .allMatch(c -> Modifier.isPrivate(c.getModifiers())));
      assertTrue(HumanAdminAccountMaintenance.DefiniteProfileNoEffect.class.isSealed());
    } else assertFalse(failure instanceof HumanAdminAccountMaintenance.DefiniteProfileNoEffect);
    assertEquals(status, response.getStatus());
    assertEquals(definite ? "NO_EFFECT" : null, response.getHeader("X-ORISO-Mutation-Outcome"));
    assertEquals(definite ? OPERATION : null, response.getHeader("X-ORISO-Mutation-Operation"));
    assertFalse(response.getContentAsString().contains("receipt"));
    assertFalse(response.getContentAsString().contains("synthetic"));
    assertEquals("", response.getContentAsString());
    if (definite) {
      for (String otherKey :
          Arrays.asList(
              null,
              "81000000-0000-4000-8000-000000000099",
              "not-a-canonical-uuid",
              OPERATION + " ")) {
        var replay =
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/profile-outcome")
                .requestAttr("X-ORISO-Mutation-Outcome", "NO_EFFECT")
                .requestAttr("X-ORISO-Mutation-Operation", OPERATION);
        if (otherKey != null) replay.header("Idempotency-Key", otherKey);
        var unbound = mvc.perform(replay).andReturn().getResponse();
        assertAll(
            () -> assertEquals(status, unbound.getStatus()),
            () -> assertNull(unbound.getHeader("X-ORISO-Mutation-Outcome")),
            () -> assertNull(unbound.getHeader("X-ORISO-Mutation-Operation")));
      }
    }
  }

  @org.springframework.web.bind.annotation.RestController
  @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
  static final class MutationFailureBoundary {
    private final RuntimeException failure;

    MutationFailureBoundary(RuntimeException failure) {
      this.failure = failure;
    }

    @org.springframework.web.bind.annotation.PatchMapping("/profile-outcome")
    void fail() {
      throw failure;
    }
  }

  private ResponseCreator noEffectReply(int status, String scenario) {
    return noEffectReply(status, scenario, "profile");
  }

  private ResponseCreator noEffectReply(int status, String scenario, String command) {
    return request -> {
      try {
        assertFalse(
            org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive());
        JsonNode body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        verifyProof(request.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
        var outcome = new TreeMap<String, Object>();
        outcome.put("realmId", "realm-1");
        outcome.put("accountId", ACCOUNT);
        outcome.put("operationId", OPERATION);
        outcome.put("command", command);
        outcome.put("ownerClient", actor.clientId());
        outcome.put("ownerSubject", SUBJECT);
        outcome.put(
            "intentDigest",
            "foreign-intent".equals(scenario)
                ? "b".repeat(64)
                : digest("maintenance-intent", body));
        outcome.put("previousState", body.path("expectedState").textValue());
        outcome.put("tenantId", "7");
        outcome.put(
            "originDigest", digest("maintenance-origin", body.path("target").path("origin")));
        outcome.put("result", "NO_EFFECT");
        outcome.put("status", status);
        String encoded =
            Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(codec.canonical(outcome).getBytes(StandardCharsets.UTF_8));
        String receipt =
            encoded
                + "."
                + ("wrong-mac".equals(scenario)
                    ? "0".repeat(64)
                    : mac("maintenance-no-effect-receipt/v1\n" + encoded));
        String reply =
            scenario.startsWith("unsigned")
                ? "{\"status\":" + status + "}"
                : codec.canonical(
                    Map.of(
                        "operationId",
                        OPERATION,
                        "accountId",
                        ACCOUNT,
                        "result",
                        "NO_EFFECT",
                        "status",
                        status,
                        "receipt",
                        receipt));
        var response =
            withStatus(org.springframework.http.HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body(reply)
                .createResponse(request);
        response.getHeaders().setCacheControl("no-store");
        return response;
      } catch (Exception failure) {
        throw new IOException(failure);
      }
    };
  }

  @Test
  void explicitFactorStatusReadIsClosedNonsecretAndKeepsDefaultProjectionSeparate() {
    var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    assertEquals(new AdminScope.Tenant(7L), actualScope.current());
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    factorReply(null);
    var factors = assertDoesNotThrow(() -> service.factorStatus(ACCOUNT));
    assertEquals(new HumanAdminMaintenanceTransport.FactorStatus(true, false), factors);
    server.verify();
    for (String malformed :
        List.of("missing", "omitted", "extra", "string", "null", "secret", "top-extra")) {
      server.reset();
      factorReply(malformed);
      assertThrows(RuntimeException.class, () -> service.factorStatus(ACCOUNT), malformed);
      server.verify();
    }
    server.reset();
    readReply();
    assertDoesNotThrow(() -> service.read(ACCOUNT));
    server.verify();
    server.reset();
    jdbc.update("update admin set tenant_id=8 where admin_id=?", ACCOUNT);
    assertThrows(RuntimeException.class, () -> service.factorStatus(ACCOUNT));
    server.verify();
  }

  @Test
  void actualStatusUsesScopedNonsecretFactorMetadataAndNeverTheLegacyCredentialObserver()
      throws Exception {
    human.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue()));
    var actualScope = actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT);
    assertTrue(((JwtAuthenticationToken) request.getUserPrincipal()).isAuthenticated());
    assertEquals(new AdminScope.Tenant(7L), actualScope.current());
    assertDoesNotThrow(() -> actualScope.assertMay(AdminScope.Target.admin(ACCOUNT)));
    var status = actualStatusService(actualScope, false);
    status.setHumanAdminMaintenance(service);
    readReply();
    factorReply(null);
    var result = assertDoesNotThrow(() -> status.read(ACCOUNT));
    assertEquals(
        new de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService
            .TwoFactor(true, true, false),
        result.twoFactor());
    verifyNoInteractions(
        org.springframework.test.util.ReflectionTestUtils.getField(status, "profiles"),
        org.springframework.test.util.ReflectionTestUtils.getField(status, "factors"));
    server.verify();
  }

  private void factorReply(String malformed) {
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
              assertEquals(Set.of("target", "include"), fieldNames(body));
              assertEquals("TWO_FACTOR_STATUS_V1", body.path("include").textValue());
              verifyProof(r.getHeaders().getFirst("X-ORISO-Task-Context"), "read", body);
            })
        .andRespond(
            r -> {
              var body = projection(false);
              var factors =
                  body.putObject("twoFactor").put("configured", true).put("active", false);
              if ("missing".equals(malformed)) factors.remove("active");
              if ("omitted".equals(malformed)) body.remove("twoFactor");
              if ("extra".equals(malformed)) factors.put("credentialId", "forbidden");
              if ("string".equals(malformed)) factors.put("configured", "true");
              if ("null".equals(malformed)) body.putNull("twoFactor");
              if ("secret".equals(malformed)) factors.put("secret", "synthetic-never-allowed");
              if ("top-extra".equals(malformed)) body.put("provider", "forbidden");
              var reply =
                  withSuccess(codec.canonical(body), MediaType.APPLICATION_JSON).createResponse(r);
              reply.getHeaders().setCacheControl("no-store");
              return reply;
            });
  }

  private de.caritas.cob.userservice.api.model.Consultant localConsultant() {
    var row = jdbc.queryForMap("select * from consultant where consultant_id=?", ACCOUNT);
    return de.caritas.cob.userservice.api.model.Consultant.builder()
        .id(ACCOUNT)
        .tenantId(((Number) row.get("tenant_id")).longValue())
        .username((String) row.get("username"))
        .firstName((String) row.get("first_name"))
        .lastName((String) row.get("last_name"))
        .email((String) row.get("email"))
        .managedPaused((Boolean) row.get("managed_paused"))
        .absent((Boolean) row.get("is_absent"))
        .build();
  }

  private de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService
      actualStatusService(AdminScope actualScope, boolean changeSqlDuringExternalRead)
          throws Exception {
    var ddl =
        new org.springframework.core.io.ClassPathResource(
                "db/changelog/changeset/20260916_account_inactivity/migrate.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    // The actual table definition is independent of rollout data, which this case does not execute.
    jdbc.execute(ddl.substring(ddl.indexOf("CREATE TABLE"), ddl.indexOf("-- No trustworthy")));
    var lifecycle =
        new de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService(
            jdbc,
            transactions,
            CLOCK,
            mock(
                de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects
                    .class));
    lifecycle.assignAtCreation(ACCOUNT, 7L, 6, 1, CLOCK.instant());
    assertEquals(
        de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService.Status
            .ACTIVE,
        lifecycle.snapshot(ACCOUNT).orElseThrow().status());
    var tenant = mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class);
    var changed = new java.util.concurrent.atomic.AtomicBoolean();
    when(tenant.getRestrictedTenantDataFresh(7L))
        .thenAnswer(
            ignored -> {
              assertFalse(
                  org.springframework.transaction.support.TransactionSynchronizationManager
                      .isActualTransactionActive(),
                  "Tenant HTTP read must be outside the owning write transaction");
              if (changeSqlDuringExternalRead && changed.compareAndSet(false, true))
                jdbc.update("update admin set first_name='Foreign' where admin_id=?", ACCOUNT);
              return new de.caritas.cob.userservice.tenantservice.generated.web.model
                      .RestrictedTenantDTO()
                  .active(true);
            });
    var adminRows = mock(AdminRepository.class);
    when(adminRows.findById(ACCOUNT))
        .thenAnswer(
            ignored ->
                Optional.of(
                    localAdmin(de.caritas.cob.userservice.api.model.Admin.AdminType.TENANT)));
    when(adminRows.save(any()))
        .thenThrow(new AssertionError("C pause must not merge a stale whole Admin entity"));
    var consultantRows = mock(ConsultantRepository.class);
    when(consultantRows.save(any()))
        .thenThrow(new AssertionError("C pause must not merge a stale whole Consultant entity"));
    service =
        new HumanAdminAccountMaintenance(
            mode,
            human,
            actualScope,
            jdbc,
            transactions,
            request,
            new KeycloakHumanAdminMaintenance(
                http,
                actors,
                broker,
                codec,
                "https://internal-auth.example.test",
                "test",
                "realm-1"),
            CLOCK,
            ISSUER);
    var adapter =
        new de.caritas.cob.userservice.api.adapters.keycloak.KeycloakService(
            human,
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .UserAccountInputValidator.class),
            mode,
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakClient.class),
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakMapper.class),
            mock(de.caritas.cob.userservice.api.helper.UserHelper.class),
            mock(de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient.class));
    adapter.setHumanAdminMaintenance(service);
    var status =
        new de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService(
            human,
            actualScope,
            adminRows,
            consultantRows,
            adapter,
            mock(IdentityProfileLookup.class),
            mock(IdentitySecondFactor.class),
            lifecycle,
            tenant);
    status.setScopedLocalWrites(mode, jdbc, transactions);
    var proxy = new org.springframework.aop.framework.ProxyFactory(status);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new org.springframework.transaction.interceptor.TransactionInterceptor(
            transactions,
            new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
    return (de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService)
        proxy.getProxy();
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
    request.setUserPrincipal(
        new JwtAuthenticationToken(
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

  private AdminScope actualScope(de.caritas.cob.userservice.api.model.Admin.AdminType type) {
    var adminRows = mock(AdminRepository.class);
    when(adminRows.findById(ACCOUNT))
        .thenAnswer(
            ignored ->
                jdbc.queryForObject(
                            "select count(*) from admin where admin_id=?", Integer.class, ACCOUNT)
                        == 0
                    ? Optional.empty()
                    : Optional.of(localAdmin(type)));
    var consultantRows = mock(ConsultantRepository.class);
    when(consultantRows.findById(ACCOUNT))
        .thenAnswer(
            ignored -> {
              var rows =
                  jdbc.queryForList("select * from consultant where consultant_id=?", ACCOUNT);
              if (rows.isEmpty()) return Optional.empty();
              var row = rows.getFirst();
              var deleted = (java.sql.Timestamp) row.get("delete_date");
              return Optional.of(
                  de.caritas.cob.userservice.api.model.Consultant.builder()
                      .id(ACCOUNT)
                      .tenantId(((Number) row.get("tenant_id")).longValue())
                      .username((String) row.get("username"))
                      .firstName((String) row.get("first_name"))
                      .lastName((String) row.get("last_name"))
                      .email((String) row.get("email"))
                      .deleteDate(deleted == null ? null : deleted.toLocalDateTime())
                      .build());
            });
    var actual =
        new AdminScope(
            human,
            adminRows,
            mock(AdminAgencyRepository.class),
            consultantRows,
            mock(ConsultantAgencyRepository.class),
            mock(de.caritas.cob.userservice.api.service.agency.AgencyService.class),
            mock(UserRepository.class),
            mock(SessionRepository.class),
            mock(UserAgencyRepository.class));
    org.springframework.test.util.ReflectionTestUtils.setField(actual, "multitenancyEnabled", true);
    return actual;
  }

  private de.caritas.cob.userservice.api.model.Admin localAdmin(
      de.caritas.cob.userservice.api.model.Admin.AdminType type) {
    var row = jdbc.queryForMap("select * from admin where admin_id=?", ACCOUNT);
    return de.caritas.cob.userservice.api.model.Admin.builder()
        .id(ACCOUNT)
        .tenantId(((Number) row.get("tenant_id")).longValue())
        .username((String) row.get("username"))
        .type(type)
        .firstName((String) row.get("first_name"))
        .lastName((String) row.get("last_name"))
        .email((String) row.get("email"))
        .managedPaused((Boolean) row.get("managed_paused"))
        .build();
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
    readReply(applied, null);
  }

  private void readReply(boolean applied, List<String> roles) {
    readReply(applied, roles, false, false);
  }

  private void readReply(
      boolean applied, List<String> roles, boolean self, boolean unchangedEmail) {
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
              assertEquals(
                  self ? human.getTenantId().toString() : "7",
                  body.path("target").path("tenantId").textValue());
              assertEquals(
                  self ? "SELF" : "HUMAN_ADMIN",
                  body.path("target").path("origin").path("kind").textValue());
              boolean protectedSelf =
                  self
                      && jdbc.queryForObject(
                              "select count(*) from admin where admin_id=? and type in ('TENANT','SUPER')",
                              Integer.class,
                              ACCOUNT)
                          == 1;
              assertEquals(
                  !self || protectedSelf ? "PROTECTED_ACCOUNT_OPERATIONS_V1" : null,
                  body.path("target").path("origin").path("humanAuthority").textValue());
            })
        .andRespond(
            r -> {
              var projection = projection(applied);
              if (self) {
                projection.put("tenantId", human.getTenantId().toString());
                if (unchangedEmail) projection.put("email", "old@example.test");
                var selfRoles = projection.putArray("effectiveRoles").add("consultant");
                if (jdbc.queryForObject(
                        "select count(*) from admin where admin_id=?", Integer.class, ACCOUNT)
                    == 1) {
                  if ("AGENCY"
                      .equals(
                          jdbc.queryForObject(
                              "select type from admin where admin_id=?", String.class, ACCOUNT)))
                    selfRoles.add("restricted-agency-admin").add("user-admin");
                  else selfRoles.add("tenant-admin");
                }
              }
              if (roles != null) {
                var values = projection.putArray("effectiveRoles");
                roles.forEach(values::add);
              }
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
    return profileReceipt("profile", null, beforeReply);
  }

  private ResponseCreator profileReceipt(String command, String group, Runnable beforeReply) {
    return profileReceipt(command, group, beforeReply, OPERATION);
  }

  private ResponseCreator profileReceipt(
      String command, String group, Runnable beforeReply, String operation) {
    return profileReceipt(command, group, beforeReply, operation, "new@example.test");
  }

  private ResponseCreator profileReceipt(
      String command, String group, Runnable beforeReply, String operation, String expectedEmail) {
    return request -> {
      try {
        assertFalse(
            org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive());
        JsonNode body = json.readTree(((MockClientHttpRequest) request).getBodyAsString());
        verifyProof(request.getHeaders().getFirst("X-ORISO-Task-Context"), command, body);
        assertEquals(operation, body.path("operationId").textValue());
        assertEquals("a".repeat(64), body.path("expectedState").textValue());
        JsonNode profile = body.path("change");
        if (group != null) {
          assertEquals("ordinary-profile", command);
          assertEquals(Set.of("group", "profile"), fieldNames(profile));
          assertEquals(group, profile.path("group").textValue());
          profile = profile.path("profile");
        }
        assertEquals(Set.of("firstName", "lastName", "email"), fieldNames(profile));
        assertEquals(expectedEmail, profile.path("email").textValue());
        var outcome = new TreeMap<String, Object>();
        outcome.put("realmId", "realm-1");
        outcome.put("accountId", ACCOUNT);
        outcome.put("operationId", operation);
        outcome.put("command", command);
        outcome.put("ownerClient", actor.clientId());
        outcome.put("ownerSubject", SUBJECT);
        outcome.put("intentDigest", digest("maintenance-intent", body));
        outcome.put("previousState", "a".repeat(64));
        outcome.put("tenantId", body.path("target").path("tenantId").textValue());
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
                            operation,
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
