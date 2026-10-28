package de.caritas.cob.userservice.api.service.accountpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakCustomConfig;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.AgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.IdentityConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.config.auth.TaskAwareIdentityClientConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityUsernameAvailability;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInvitePurpose;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteStatus;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteTargetRole;
import de.caritas.cob.userservice.api.service.accountinvite.AgencyServiceAgencyFacts;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.httpheader.HttpHeadersResolver;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * Explicit native slice: actual broker, locked JPA repository and helper reach real Tenant HTTP.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckedAccountPolicyNativeGate {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String RAW_LINK = "synthetic-native-policy-link";
  private final AtomicReference<Captured> captured = new AtomicReference<>();
  private final AtomicReference<String> cacheControl = new AtomicReference<>();
  private final AtomicReference<String> observedAgencyBearer = new AtomicReference<>();
  private LocalContainerEntityManagerFactoryBean persistence;
  private EntityManagerFactory factory;
  private EntityManager manager;
  private AccountInviteRepository invites;
  private TransactionTemplate transaction;
  private CheckedAccountPolicyReads policies;
  private TaskActorTokenBroker broker;
  private TaskAwareIdentityClientConfig identities;
  private JwtDecoder decoder;
  private MockEnvironment environment;
  private HttpServer agency;
  private final java.util.concurrent.atomic.AtomicInteger lastPolicyStatus =
      new java.util.concurrent.atomic.AtomicInteger();
  private de.caritas.cob.userservice.api.service.accountinvite.AgencyFacts currentAgencies;
  private KeycloakAuthClient authentication;
  private final AtomicReference<String> agencyMode = new AtomicReference<>("live");
  private AgencyService agencyService;
  private RestTemplate http;
  private String receiver;
  private byte[] key;
  private long inviteId;
  @TempDir Path temporary;

  private record Captured(String proof, String bearer) {
    @Override
    public String toString() {
      return "Captured[redacted]";
    }
  }

  @BeforeAll
  void requireRealFixtureAndModules() throws Exception {
    JsonNode fixture =
        JSON.readTree(Files.readString(Path.of(required("ORISO_TASK_TOKEN_FIXTURE"))));
    assertThat(fixture.path("identityModel").asText()).isEqualTo("FIVE_ACTORS");
    assertThat(fixture.path("tasks").size()).isEqualTo(5);
    receiver = required("ORISO_POLICY_RECEIVER_URL");
    key = Base64.getDecoder().decode(required("ORISO_POLICY_SHARED_KEY"));
    environment =
        new MockEnvironment()
            .withProperty("TASK_IDENTITY_MODEL", "FIVE_ACTORS")
            .withProperty("ORISO_TASK_CONTEXT_KEY", required("ORISO_POLICY_SHARED_KEY"));
    var actorNames = new java.util.HashSet<String>();
    for (JsonNode actor : fixture.path("tasks")) {
      String name = actor.path("key").asText();
      assertThat(actorNames.add(name)).isTrue();
      String prefix = "IDENTITY_" + name;
      environment.setProperty(prefix + "_CLIENT_ID", actor.path("clientId").asText());
      environment.setProperty(prefix + "_CLIENT_SECRET", actor.path("secret").asText());
      environment.setProperty(prefix + "_SERVICE_SUBJECT", actor.path("subject").asText());
    }
    assertThat(actorNames)
        .isEqualTo(Set.of("SETUP", "ACCOUNT_CREATE", "ACCOUNT_ADMIN", "MAIL", "RUNTIME"));
    String issuer = fixture.path("issuer").asText();
    int realmStart = issuer.lastIndexOf("/realms/");
    assertThat(realmStart).isPositive();
    var legacy = new IdentityConfig();
    legacy.setOpenidConnectUrl(issuer + "/protocol/openid-connect");
    authentication = new KeycloakAuthClient(boundedHttp(), new AuthenticatedUser(), legacy);
    ReflectionTestUtils.setField(authentication, "keycloakClientId", "app");
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl(issuer.substring(0, realmStart));
    keycloak.setRealm(issuer.substring(realmStart + "/realms/".length()));
    decoder = NimbusJwtDecoder.withJwkSetUri(issuer + "/protocol/openid-connect/certs").build();
    broker =
        new TaskActorTokenBroker(
            new TaskActorConfiguration(environment, new KeycloakCustomConfig(), legacy),
            authentication,
            decoder,
            keycloak);
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton("nativeBroker", broker);
    identities =
        new TaskAwareIdentityClientConfig(
            legacy, environment, beans.getBeanProvider(TaskActorTokenBroker.class));

    persistence = new LocalContainerEntityManagerFactoryBean();
    persistence.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:policy-native-" + UUID.randomUUID() + ";MODE=MariaDB;DB_CLOSE_DELAY=-1",
            "sa",
            ""));
    persistence.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    persistence.setManagedTypes(PersistenceManagedTypes.of(AccountInvite.class.getName()));
    persistence.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop"));
    persistence.afterPropertiesSet();
    factory = persistence.getObject();
    assertThat(factory).isNotNull();
    manager = SharedEntityManagerCreator.createSharedEntityManager(factory);
    invites = new JpaRepositoryFactory(manager).getRepository(AccountInviteRepository.class);
    transaction = new TransactionTemplate(new JpaTransactionManager(factory));

    agency = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    agency.createContext(
        "/",
        exchange -> {
          try {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header == null || !header.startsWith("Bearer "))
              throw new IllegalArgumentException();
            var caller = decoder.decode(header.substring(7));
            String path = exchange.getRequestURI().getPath();
            boolean detail = path.equals("/agencyadmin/agencies/10");
            if (!"GET".equals(exchange.getRequestMethod())
                || !(detail || path.equals("/agencies/10"))) throw new IllegalArgumentException();
            if ("outage".equals(agencyMode.get())
                || detail && "policy-outage".equals(agencyMode.get())) {
              exchange.sendResponseHeaders(503, -1);
              return;
            }
            if ("missing".equals(agencyMode.get())) {
              exchange.sendResponseHeaders(404, -1);
              return;
            }
            String expected = detail ? "backend-setup" : "backend-runtime";
            if (!expected.equals(caller.getClaimAsString("azp")))
              throw new IllegalArgumentException();
            if (detail) observedAgencyBearer.set(header.substring(7));
            long tenant = "foreign".equals(agencyMode.get()) ? 2L : 1L;
            String deleted = "deleted".equals(agencyMode.get()) ? "2026-01-01T00:00:00" : "null";
            byte[] body =
                (detail
                        ? "{\"_embedded\":{\"id\":10,\"tenantId\":"
                            + tenant
                            + ",\"deleteDate\":\""
                            + deleted
                            + "\",\"departments\":[]}}"
                        : "[{\"id\":10,\"tenantId\":1,\"consultingType\":1,\"teamAgency\":false}]")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception invalid) {
            exchange.sendResponseHeaders(503, -1);
          } finally {
            exchange.close();
          }
        });
    agency.start();
    var securityHeaders = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(securityHeaders, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(securityHeaders, "csrfCookieProperty", "csrfCookie");
    var tenantHeaders = new TenantHeaderSupplier(new HttpHeadersResolver());
    var adminFactory = new AgencyAdminServiceApiControllerFactory();
    ReflectionTestUtils.setField(adminFactory, "restTemplate", boundedHttp());
    ReflectionTestUtils.setField(adminFactory, "agencyAdminServiceApiUrl", agencyUrl());
    currentAgencies = new AgencyServiceAgencyFacts(securityHeaders, tenantHeaders, adminFactory);
    var agencyFactory = new AgencyServiceApiControllerFactory();
    ReflectionTestUtils.setField(agencyFactory, "restTemplate", boundedHttp());
    ReflectionTestUtils.setField(agencyFactory, "agencyServiceApiUrl", agencyUrl());
    agencyService =
        new AgencyService(
            securityHeaders, tenantHeaders, agencyFactory, new ConcurrentMapCacheManager());
    http = boundedHttp();
    http.getInterceptors()
        .add(
            (request, body, execution) -> {
              String authorization = request.getHeaders().getFirst("Authorization");
              captured.set(
                  new Captured(
                      request.getHeaders().getFirst("X-ORISO-Wizard-Policy-Context"),
                      authorization == null ? "" : authorization.substring(7)));
              var response = execution.execute(request, body);
              lastPolicyStatus.set(response.getStatusCode().value());
              cacheControl.set(response.getHeaders().getCacheControl());
              return response;
            });
    policies =
        new CheckedAccountPolicyReads(
            invites, currentAgencies, broker, decoder, http, environment, receiver);
    ReflectionTestUtils.setField(policies, "entityManager", manager);
  }

  @BeforeEach
  void persistedInvitation() {
    captured.set(null);
    agencyMode.set("live");
    cacheControl.set(null);
    observedAgencyBearer.set(null);
    transaction.executeWithoutResult(
        status -> {
          invites.deleteAllInBatch();
          var invite =
              AccountInvite.builder()
                  .purpose(AccountInvitePurpose.INVITE)
                  .targetRole(AccountInviteTargetRole.COUNSELLOR)
                  .tenantId(1L)
                  .agencyId(10L)
                  .recipientEmail("native-policy@example.invalid")
                  .firstName("Native")
                  .lastName("Family")
                  .departmentId(11L)
                  .tokenHash(AccountInviteService.hash(RAW_LINK))
                  .status(AccountInviteStatus.EMAIL_SENT)
                  .expiresAt(LocalDateTime.now().plusHours(1))
                  .createDate(LocalDateTime.now())
                  .build();
          inviteId = invites.saveAndFlush(invite).getId();
        });
  }

  @AfterAll
  void cleanup() throws Exception {
    Files.writeString(
        Path.of(required("ORISO_POLICY_OBSERVATIONS")),
        JSON.writeValueAsString(
            Map.of(
                "lastPolicyHttpStatus",
                lastPolicyStatus.get(),
                "policyFacadeEntries",
                queries(),
                "actualTenantOrmQueryExecutions",
                sqlQueries())));
    TenantContext.clear();
    if (agency != null) agency.stop(0);
    if (persistence != null) persistence.destroy();
  }

  @Test
  void actualHeldRepositoryOriginAndActualBrokerReachTheRealNarrowProjection() throws Exception {
    long before = queries();
    long beforeSql = sqlQueries();
    var saga = mock(CreateConsultantSaga.class);
    var observed = new AtomicReference<CheckedAccountPolicyReads.Policy>();
    when(saga.createInvitedConsultant(
            any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any()))
        .thenAnswer(
            call -> {
              observed.set(policies.read(1));
              return invites.findByIdForUpdate(inviteId).orElseThrow();
            });
    var service = invitationCaller(saga);
    var accepted =
        de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext.callWith(
            "synthetic-unrelated-ambient-token",
            () ->
                transaction.execute(
                    status ->
                        service.acceptInvite(
                            RAW_LINK,
                            new de.caritas.cob.userservice.api.service.accountinvite
                                .CounsellorInviteProvisioningService.ProvisionCounsellorCommand(
                                "nativeuser", "synthetic-private-password", true, null))));
    assertThat(accepted.getId()).isEqualTo(inviteId);
    assertThat(observed.get().id()).isEqualTo(1L);
    assertThat(observed.get().allowedNumberOfUsers()).isEqualTo(5);
    assertThat(cacheControl.get()).contains("no-store");
    assertSameExplicitBearer();
    assertThat(queries()).isEqualTo(before + 1);
    assertThat(sqlQueries()).isEqualTo(beforeSql + 1);
    Captured valid = captured.get();
    var origin = claims(valid.proof()).path("origin");
    assertThat(origin.path("reference").asText()).isEqualTo("invite:" + inviteId);
    assertThat(claims(valid.proof()).path("purpose").asText()).isEqualTo("INVITE_RESERVATIONS");
    var response = policyHttp(valid.bearer(), valid.proof());
    var projection = JSON.readTree(response.getBody());
    var fields = new java.util.HashSet<String>();
    projection.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).isEqualTo(Set.of("id", "allowedNumberOfUsers"));
    assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void unheldRevokedChangedAndForeignTransactionOriginsNeverQueryTenant() throws Exception {
    long before = queries();
    assertThatThrownBy(
            () -> policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                transaction.execute(
                    status ->
                        policies.withInvitation(
                            "missing-link", "CONSULTANT", () -> policies.read(1))))
        .isInstanceOf(AccessDeniedException.class);
    for (java.util.function.Consumer<AccountInvite> change :
        List.<java.util.function.Consumer<AccountInvite>>of(
            invite -> invite.setStatus(AccountInviteStatus.REVOKED),
            invite -> invite.setExpiresAt(LocalDateTime.now().minusSeconds(1)),
            invite -> invite.setAgencyId(11L),
            invite -> invite.setTenantId(2L),
            invite -> invite.setPurpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP),
            invite -> invite.setTokenHash(AccountInviteService.hash("replaced-link")),
            invite -> invite.setTargetRole(AccountInviteTargetRole.AGENCY_ADMIN))) {
      persistedInvitation();
      transaction.executeWithoutResult(
          status ->
              policies.withInvitation(
                  RAW_LINK,
                  "CONSULTANT",
                  () -> {
                    change.accept(invites.findByIdForUpdate(inviteId).orElseThrow());
                    assertThatThrownBy(() -> policies.read(1))
                        .isInstanceOf(AccessDeniedException.class);
                    return null;
                  }));
    }
    for (String mode : List.of("foreign", "deleted", "missing", "outage")) {
      persistedInvitation();
      agencyMode.set(mode);
      var failure =
          org.assertj.core.api.Assertions.catchThrowable(
              () ->
                  transaction.execute(
                      status ->
                          policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1))));
      if ("outage".equals(mode)) {
        assertThat(failure)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Account provisioning policy is unavailable")
            .hasNoCause();
      } else assertThat(failure).isInstanceOf(AccessDeniedException.class);
    }
    assertThat(queries()).isEqualTo(before);
    assertThat(captured.get()).isNull();
  }

  @Test
  void aDifferentLiveTransactionCannotBorrowTheHeldInvitationCapability() throws Exception {
    long before = queries();
    var nested = new TransactionTemplate(new JpaTransactionManager(factory));
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.executeWithoutResult(
        outer ->
            policies.withInvitation(
                RAW_LINK,
                "CONSULTANT",
                () -> {
                  nested.executeWithoutResult(
                      inner ->
                          assertThatThrownBy(() -> policies.read(1))
                              .isInstanceOf(AccessDeniedException.class));
                  try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    var refused =
                        executor.submit(
                            () ->
                                org.assertj.core.api.Assertions.catchThrowable(
                                        () -> policies.read(1))
                                    instanceof AccessDeniedException);
                    assertThat(refused.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                  } catch (Exception failure) {
                    throw new AssertionError("Thread lifetime control failed", failure);
                  }
                  assertThatThrownBy(() -> policies.read(2))
                      .isInstanceOf(AccessDeniedException.class);
                  return null;
                }));
    assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
    assertThat(queries()).isEqualTo(before);
    assertThat(captured.get()).isNull();
    assertThat(
            java.util.Arrays.stream(
                    ConsultantImportService.CheckedPolicyOrigin.class.getDeclaredConstructors())
                .allMatch(
                    constructor ->
                        java.lang.reflect.Modifier.isPrivate(constructor.getModifiers())))
        .isTrue();
  }

  @Test
  void independentlySignedPurposeTargetAndOriginMutationsFailBeforeTheActualPolicyQuery()
      throws Exception {
    transaction.executeWithoutResult(
        status -> policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1)));
    Captured valid = captured.get();
    long before = queries();
    var wrong = new java.util.LinkedHashMap<String, Object>();
    wrong.put("v", 1);
    wrong.put("iss", "oriso-userservice");
    wrong.put("aud", "agencyservice");
    wrong.put("actor", "MAIL");
    wrong.put("purpose", "CONSULTANT_IMPORT");
    wrong.put("method", "POST");
    wrong.put("operation", "wizard.account-policy.write");
    wrong.put("azp", "foreign-client");
    wrong.put("sub", "foreign-subject");
    wrong.put("tokenIssuer", "https://foreign.invalid/realms/test");
    wrong.put("tenantId", 2);
    wrong.put("target", "tenant/2/account-provisioning-policy");
    wrong.put("nonce", "invalid");
    wrong.put("payloadDigest", "0".repeat(64));
    wrong.put("iat", java.time.Instant.now().getEpochSecond() + 60);
    wrong.put("exp", java.time.Instant.now().getEpochSecond() - 1);
    for (var mutation : wrong.entrySet()) {
      var changed = mutableClaims(valid);
      changed.put(mutation.getKey(), mutation.getValue());
      rejectHttp(valid, independentlySign(changed), before);
    }
    for (String field : List.of("v", "iat", "exp", "tenantId")) {
      var changed = mutableClaims(valid);
      changed.put(field, 1.5);
      rejectHttp(valid, independentlySign(changed), before);
    }
    for (String field : List.of("nonce", "origin", "purpose")) {
      var changed = mutableClaims(valid);
      changed.remove(field);
      rejectHttp(valid, independentlySign(changed), before);
    }
    var extra = mutableClaims(valid);
    extra.put("publicOrigin", "self-supplied");
    rejectHttp(valid, independentlySign(extra), before);
    var longLifetime = mutableClaims(valid);
    longLifetime.put("exp", ((Number) longLifetime.get("iat")).longValue() + 61);
    rejectHttp(valid, independentlySign(longLifetime), before);
    var wrongOrigin = new java.util.LinkedHashMap<String, Object>();
    wrongOrigin.put("kind", "PUBLIC");
    wrongOrigin.put("tenantId", 2);
    wrongOrigin.put("agencyIds", List.of());
    wrongOrigin.put("profile", "TENANT_ADMIN");
    wrongOrigin.put("reference", "invite:0");
    wrongOrigin.put("revision", "invalid");
    wrongOrigin.put("sourceDigest", "extra-field");
    for (var mutation : wrongOrigin.entrySet()) {
      var changed = mutableClaims(valid);
      var origin = JSON.convertValue(changed.get("origin"), TreeMap.class);
      origin.put(mutation.getKey(), mutation.getValue());
      changed.put("origin", origin);
      changed.put(
          "payloadDigest",
          independentlyDigest(
              Map.of(
                  "tenantId", 1, "origin", origin, "projection", "account-provisioning-policy")));
      rejectHttp(valid, independentlySign(changed), before);
    }
    for (List<?> ids : List.of(List.of(0), List.of(10, 10), List.of(11, 10), List.of(1.5))) {
      var changed = mutableClaims(valid);
      var origin = JSON.convertValue(changed.get("origin"), TreeMap.class);
      origin.put("agencyIds", ids);
      changed.put("origin", origin);
      changed.put(
          "payloadDigest",
          independentlyDigest(
              Map.of(
                  "tenantId", 1, "origin", origin, "projection", "account-provisioning-policy")));
      rejectHttp(valid, independentlySign(changed), before);
    }
    String duplicate =
        new String(independentCanonical(mutableClaims(valid)), StandardCharsets.UTF_8)
            .replaceFirst("\\{", "{\"v\":2,");
    rejectHttp(valid, signRaw(duplicate.getBytes(StandardCharsets.UTF_8)), before);
    rejectHttp(valid, null, before);
    rejectHttp(
        new Captured(
            valid.proof(),
            broker
                .issue(de.caritas.cob.userservice.api.config.auth.TaskPurpose.SYSTEM_EMAIL_DELIVERY)
                .accessToken()),
        valid.proof(),
        before);
  }

  @Test
  void actualConfiguredCsvLoopIssuesThePrivateImportOriginForTheRealHttpRead() throws Exception {
    var saga = mock(CreateConsultantSaga.class);
    var observed = new java.util.ArrayList<Captured>();
    when(saga.createImportedConsultant(
            any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(
            call -> {
              assertThat(policies.read(1).allowedNumberOfUsers()).isEqualTo(5);
              observed.add(captured.get());
              var consultant = new Consultant();
              consultant.setId("native-fixture-" + observed.size());
              return consultant;
            });
    String row =
        ",1,nativeuser,\"Native\nGiven\",Family,native-policy@example.invalid,nein,,10;ordinary\r\n";
    String content =
        row + ",2,nativeother,Other,Family,native-other@example.invalid,nein,,10;ordinary\r\n";
    long before = queries();
    long beforeSql = sqlQueries();
    runVerifiedConfiguredImport(configuredImporter(saga, content, List.of("consultant"), false));
    assertThat(observed).hasSize(2);
    assertThat(cacheControl.get()).contains("no-store");
    assertSameExplicitBearer();
    assertThat(queries()).isEqualTo(before + 2);
    assertThat(sqlQueries()).isEqualTo(beforeSql + 2);
    String digest =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
    for (int index = 0; index < observed.size(); index++) {
      var signed = claims(observed.get(index).proof());
      assertThat(signed.path("purpose").asText()).isEqualTo("CONSULTANT_IMPORT");
      assertThat(signed.path("origin").path("reference").asText())
          .isEqualTo("import:" + digest + ":" + (index + 1));
    }
    assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
    Captured valid = observed.get(1);
    long afterValid = queries();
    for (String field : List.of("sourceDigest", "reference", "profile", "revision")) {
      var changed = mutableClaims(valid);
      var origin = JSON.convertValue(changed.get("origin"), TreeMap.class);
      origin.put(field, "invalid");
      changed.put("origin", origin);
      changed.put(
          "payloadDigest",
          independentlyDigest(
              Map.of(
                  "tenantId", 1, "origin", origin, "projection", "account-provisioning-policy")));
      rejectHttp(valid, independentlySign(changed), afterValid);
    }
    var stale = mock(CreateConsultantSaga.class);
    when(stale.createImportedConsultant(
            any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(
            call -> {
              ((ConsultantImportService.ImportRecord) call.getArgument(0))
                  .setEmail("changed@example.invalid");
              assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
              var consultant = new Consultant();
              consultant.setId("stale-fixture");
              return consultant;
            });
    captured.set(null);
    runVerifiedConfiguredImport(configuredImporter(stale, row, List.of("consultant"), false));
    assertThat(captured.get()).isNull();
    assertThat(queries()).isEqualTo(afterValid);
    for (boolean foreign : List.of(true, false)) {
      var refused = mock(CreateConsultantSaga.class);
      runVerifiedConfiguredImport(
          configuredImporter(
              refused,
              foreign ? row.stripTrailing() + ",2\r\n" : row,
              foreign ? List.of("consultant") : List.of("tenant-admin"),
              foreign));
      org.mockito.Mockito.verifyNoInteractions(refused);
      assertThat(queries()).isEqualTo(afterValid);
    }
    var afterPolicy = new java.util.concurrent.atomic.AtomicInteger();
    assertThatThrownBy(
            () ->
                runVerifiedConfiguredImport(
                    configuredImporter(
                        actualQuotaSaga(0, afterPolicy), row, List.of("consultant"), false)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
    assertThat(afterPolicy.get()).isEqualTo(1);
    assertThat(queries()).isEqualTo(afterValid + 1);
    afterPolicy.set(0);
    assertThatThrownBy(
            () ->
                runVerifiedConfiguredImport(
                    configuredImporter(
                        actualQuotaSaga(5, afterPolicy), row, List.of("consultant"), false)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
    assertThat(afterPolicy.get()).isZero();
    assertThat(queries()).isEqualTo(afterValid + 2);
    agencyMode.set("policy-outage");
    captured.set(null);
    assertThatThrownBy(
            () ->
                runVerifiedConfiguredImport(
                    configuredImporter(
                        actualQuotaSaga(0, afterPolicy), row, List.of("consultant"), false)))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                .class);
    assertThat(afterPolicy.get()).isZero();
    assertThat(captured.get()).isNull();
    assertThat(queries()).isEqualTo(afterValid + 2);
    agencyMode.set("live");
    Path tenantMode = Path.of(required("ORISO_POLICY_TENANT_MODE"));
    long beforeUnavailableSql = sqlQueries();
    try {
      Files.writeString(tenantMode, "outage");
      assertThatThrownBy(
              () ->
                  runVerifiedConfiguredImport(
                      configuredImporter(
                          actualQuotaSaga(0, afterPolicy), row, List.of("consultant"), false)))
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException
                  .class);
      assertThat(afterPolicy.get()).isZero();
      assertThat(queries()).isEqualTo(afterValid + 3);
      assertThat(lastPolicyStatus.get()).isEqualTo(503);
      assertThat(sqlQueries()).isEqualTo(beforeUnavailableSql);
    } finally {
      Files.writeString(tenantMode, "live");
    }
  }

  private void runVerifiedConfiguredImport(ConsultantImportService importer) {
    var token =
        broker.issue(de.caritas.cob.userservice.api.config.auth.TaskPurpose.CONSULTANT_IMPORT);
    try (var trigger = broker.verifyConsultantImportTrigger(token.accessToken())) {
      importer.startImport(trigger);
    }
  }

  private ConsultantImportService configuredImporter(
      CreateConsultantSaga saga, String content, List<String> roles, boolean multi)
      throws Exception {
    var users = mock(UserHelper.class);
    when(users.isUsernameValid(anyString())).thenReturn(true);
    when(users.isValidEmail(anyString())).thenReturn(true);
    var consultants = mock(ConsultantService.class);
    when(consultants.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());
    var types = mock(ConsultingTypeManager.class);
    var consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    consultantRoles.setRoleSets(new java.util.LinkedHashMap<>(Map.of("ordinary", roles)));
    when(types.getConsultingTypeSettings(1))
        .thenReturn(
            new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                    .ExtendedConsultingTypeResponseDTO()
                .slug("native")
                .languageFormal(true)
                .roles(
                    new de.caritas.cob.userservice.consultingtypeservice.generated.web.model
                            .RolesDTO()
                        .consultant(consultantRoles)));
    var importer =
        new ConsultantImportService(
            mock(IdentityUsernameAvailability.class),
            consultants,
            types,
            agencyService,
            users,
            saga,
            mock(ConsultantAgencyRelationCreatorService.class));
    Path csv = temporary.resolve("configured-" + UUID.randomUUID() + ".csv");
    Files.writeString(csv, content);
    ReflectionTestUtils.setField(importer, "importFilename", csv.toString());
    ReflectionTestUtils.setField(
        importer,
        "protocolFilename",
        temporary.resolve("protocol-" + UUID.randomUUID()).toString());
    ReflectionTestUtils.setField(importer, "multiTenancyEnabled", multi);
    ReflectionTestUtils.setField(importer, "lifecycleEnvironment", environment);
    ReflectionTestUtils.setField(importer, "lifecycleIdentities", identities);
    ReflectionTestUtils.setField(importer, "checkedPolicyReads", policies);
    return importer;
  }

  private CreateConsultantSaga actualQuotaSaga(
      int active, java.util.concurrent.atomic.AtomicInteger afterPolicy) {
    var recovery =
        mock(de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.class);
    when(recovery.forNewConsultant(any()))
        .thenAnswer(
            call -> {
              afterPolicy.incrementAndGet();
              throw new IllegalStateException("Synthetic stop after actual policy boundary");
            });
    var consultants = mock(ConsultantService.class);
    when(consultants.getNumberOfActiveConsultants(1L)).thenReturn((long) active);
    var saga =
        new CreateConsultantSaga(
            recovery,
            mock(de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService.class),
            mock(de.caritas.cob.userservice.api.port.out.IdentityClient.class),
            mock(de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater.class),
            consultants,
            mock(de.caritas.cob.userservice.api.service.ConsultantPublicSlugService.class),
            mock(UserHelper.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .UserAccountInputValidator.class),
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantAdminService.class),
            mock(de.caritas.cob.userservice.api.port.out.MatrixUserClient.class),
            new de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver(),
            mock(ConsultantAgencyRelationCreatorService.class),
            mock(
                de.caritas.cob.userservice.api.admin.service.consultant.validation
                    .ConsultantTopicAgencyCompatibilityValidator.class),
            mock(de.caritas.cob.userservice.api.facade.rollback.RollbackFacade.class),
            new AuthenticatedUser(),
            mock(de.caritas.cob.userservice.api.service.appointment.AppointmentService.class));
    var beans = new DefaultListableBeanFactory();
    beans.registerSingleton(
        "ordinary",
        mock(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .class));
    ReflectionTestUtils.setField(
        saga,
        "ordinaryAccountCreation",
        beans.getBeanProvider(
            de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.OrdinaryAccountCreation
                .class));
    ReflectionTestUtils.setField(saga, "lifecycleEnvironment", environment);
    ReflectionTestUtils.setField(saga, "checkedPolicyReads", policies);
    return saga;
  }

  private de.caritas.cob.userservice.api.service.accountinvite.CounsellorInviteProvisioningService
      invitationCaller(CreateConsultantSaga saga) {
    var accountService =
        new AccountInviteService(
            invites,
            mock(de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository.class),
            new AuthenticatedUser(),
            mock(de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup.class),
            new JpaTransactionManager(factory),
            mock(
                de.caritas.cob.userservice.api.service.accountinvite.AccountInviteAccessPolicy
                    .class),
            currentAgencies,
            mock(de.caritas.cob.userservice.api.service.accountinvite.InviteTargetResolver.class),
            mock(de.caritas.cob.userservice.api.service.accountinvite.ReservationLedger.class),
            mock(de.caritas.cob.userservice.api.service.accountinvite.UnitQueue.class),
            mock(de.caritas.cob.userservice.api.service.accountinvite.InviteDelivery.class),
            mock(
                de.caritas.cob.userservice.api.service.accountinvite.ExistingAccountSetupIssuer
                    .class));
    var check =
        new de.caritas.cob.userservice.api.service.accountinvite.AcceptTimeAgencyCheck(
            currentAgencies,
            mock(de.caritas.cob.userservice.api.port.out.IdentityAuthentication.class),
            identities,
            mock(de.caritas.cob.userservice.api.admin.service.tenant.TenantService.class));
    var service =
        new de.caritas.cob.userservice.api.service.accountinvite
            .CounsellorInviteProvisioningService(
            accountService,
            invites,
            mock(de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade.class),
            mock(de.caritas.cob.userservice.api.port.out.ConsultantRepository.class),
            saga,
            mock(
                de.caritas.cob.userservice.api.service.accountinvite
                    .CounsellorAgencyAdminGrantService.class),
            mock(ConsultantAgencyRelationCreatorService.class),
            check,
            mock(de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository.class),
            mock(de.caritas.cob.userservice.api.port.out.IdentityPasswordUpdater.class));
    ReflectionTestUtils.setField(service, "lifecycleEnvironment", environment);
    ReflectionTestUtils.setField(service, "checkedPolicyReads", policies);
    return service;
  }

  private org.springframework.http.ResponseEntity<String> policyHttp(String bearer, String proof) {
    var headers = new HttpHeaders();
    headers.setBearerAuth(bearer);
    headers.set("tenantId", "1");
    if (proof != null) headers.set("X-ORISO-Wizard-Policy-Context", proof);
    return boundedHttp()
        .exchange(
            receiver + "/internal/tenants/1/account-provisioning-policy",
            HttpMethod.GET,
            new HttpEntity<Void>(headers),
            String.class);
  }

  private void rejectHttp(Captured valid, String proof, long before) throws Exception {
    long beforeSql = sqlQueries();
    assertThatThrownBy(() -> policyHttp(valid.bearer(), proof))
        .isInstanceOf(HttpClientErrorException.Forbidden.class);
    assertThat(queries()).isEqualTo(before);
    assertThat(sqlQueries()).isEqualTo(beforeSql);
  }

  private TreeMap<String, Object> mutableClaims(Captured value) throws Exception {
    return JSON.convertValue(claims(value.proof()), TreeMap.class);
  }

  private static byte[] independentCanonical(Object value) throws Exception {
    return new ObjectMapper()
        .configure(
            com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
        .writeValueAsBytes(value);
  }

  private String independentlyDigest(Object value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    mac.update("payload\n".getBytes(StandardCharsets.US_ASCII));
    return java.util.HexFormat.of().formatHex(mac.doFinal(independentCanonical(value)));
  }

  private String signRaw(byte[] raw) throws Exception {
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return encoded
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(("proof\n" + encoded).getBytes(StandardCharsets.US_ASCII)));
  }

  private String independentlySign(Map<String, Object> claims) throws Exception {
    return signRaw(independentCanonical(claims));
  }

  private JsonNode claims(String proof) throws Exception {
    return JSON.readTree(Base64.getUrlDecoder().decode(proof.split("\\.", -1)[0]));
  }

  private String agencyUrl() {
    return "http://127.0.0.1:" + agency.getAddress().getPort();
  }

  private void assertSameExplicitBearer() {
    assertThat(observedAgencyBearer.get() != null && captured.get() != null).isTrue();
    // Boolean comparison ensures a failed assertion cannot print the actual native JWT.
    assertThat(
            MessageDigest.isEqual(
                observedAgencyBearer.get().getBytes(StandardCharsets.UTF_8),
                captured.get().bearer().getBytes(StandardCharsets.UTF_8)))
        .isTrue();
  }

  private long sqlQueries() throws Exception {
    return Long.parseLong(Files.readString(Path.of(required("ORISO_POLICY_SQL_COUNT"))).trim());
  }

  private long queries() throws Exception {
    return Long.parseLong(Files.readString(Path.of(required("ORISO_POLICY_QUERY_COUNT"))).trim());
  }

  private static RestTemplate boundedHttp() {
    var request = new SimpleClientHttpRequestFactory();
    request.setConnectTimeout(Duration.ofSeconds(10));
    request.setReadTimeout(Duration.ofSeconds(20));
    return new RestTemplate(request);
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank())
      throw new IllegalStateException("Native policy fixture missing: " + name);
    return value;
  }
}
