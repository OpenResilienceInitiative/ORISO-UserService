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
    var authentication = new KeycloakAuthClient(boundedHttp(), new AuthenticatedUser(), legacy);
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
            boolean detail = exchange.getRequestURI().getPath().equals("/agencyadmin/agencies/10");
            String expected = detail ? "backend-setup" : "backend-runtime";
            if (!expected.equals(caller.getClaimAsString("azp")))
              throw new IllegalArgumentException();
            if (detail) observedAgencyBearer.set(header.substring(7));
            byte[] body =
                (detail
                        ? "{\"_embedded\":{\"id\":10,\"tenantId\":1,\"deleteDate\":\"null\",\"departments\":[]}}"
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
    var currentAgencies =
        new AgencyServiceAgencyFacts(securityHeaders, tenantHeaders, adminFactory);
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
                "lastPolicyHttpStatus", lastPolicyStatus.get(), "policyQueryAttempts", queries())));
    TenantContext.clear();
    if (agency != null) agency.stop(0);
    if (persistence != null) persistence.destroy();
  }

  @Test
  void actualHeldRepositoryOriginAndActualBrokerReachTheRealNarrowProjection() throws Exception {
    long before = queries();
    var policy =
        de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext.callWith(
            "synthetic-unrelated-ambient-token",
            () ->
                transaction.execute(
                    status ->
                        policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1))));
    assertThat(policy.id()).isEqualTo(1L);
    assertThat(policy.allowedNumberOfUsers()).isEqualTo(5);
    assertThat(cacheControl.get()).contains("no-store");
    assertSameExplicitBearer();
    assertThat(queries()).isEqualTo(before + 1);
    var origin = claims(captured.get().proof()).path("origin");
    assertThat(origin.path("reference").asText()).isEqualTo("invite:" + inviteId);
    assertThat(claims(captured.get().proof()).path("purpose").asText())
        .isEqualTo("INVITE_RESERVATIONS");
    assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void unheldRevokedChangedAndForeignTransactionOriginsNeverQueryTenant() throws Exception {
    long before = queries();
    assertThatThrownBy(
            () -> policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1)))
        .isInstanceOf(AccessDeniedException.class);
    transaction.executeWithoutResult(
        status ->
            policies.withInvitation(
                RAW_LINK,
                "CONSULTANT",
                () -> {
                  invites
                      .findByIdForUpdate(inviteId)
                      .orElseThrow()
                      .setStatus(AccountInviteStatus.REVOKED);
                  assertThatThrownBy(() -> policies.read(1))
                      .isInstanceOf(AccessDeniedException.class);
                  return null;
                }));
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
                  return null;
                }));
    assertThat(queries()).isEqualTo(before);
    assertThat(captured.get()).isNull();
  }

  @Test
  void independentlySignedPurposeTargetAndOriginMutationsFailBeforeTheActualPolicyQuery()
      throws Exception {
    transaction.executeWithoutResult(
        status -> policies.withInvitation(RAW_LINK, "CONSULTANT", () -> policies.read(1)));
    Captured valid = captured.get();
    long before = queries();
    for (String field :
        List.of("purpose", "target", "azp", "sub", "tokenIssuer", "nonce", "payloadDigest")) {
      var changed = JSON.convertValue(claims(valid.proof()), TreeMap.class);
      changed.put(field, "purpose".equals(field) ? "CONSULTANT_IMPORT" : "invalid");
      String proof = independentlySign(changed);
      var headers = new HttpHeaders();
      headers.setBearerAuth(valid.bearer());
      headers.set("X-ORISO-Wizard-Policy-Context", proof);
      assertThatThrownBy(
              () ->
                  boundedHttp()
                      .exchange(
                          receiver + "/internal/tenants/1/account-provisioning-policy",
                          HttpMethod.GET,
                          new HttpEntity<Void>(headers),
                          String.class))
          .isInstanceOf(HttpClientErrorException.Forbidden.class);
      assertThat(queries()).isEqualTo(before);
    }
  }

  @Test
  void actualConfiguredCsvLoopIssuesThePrivateImportOriginForTheRealHttpRead() throws Exception {
    var users = mock(UserHelper.class);
    when(users.isUsernameValid("nativeuser")).thenReturn(true);
    when(users.isValidEmail("native-policy@example.invalid")).thenReturn(true);
    var consultants = mock(ConsultantService.class);
    when(consultants.findConsultantByUsernameOrEmail(anyString(), anyString()))
        .thenReturn(Optional.empty());
    var types = mock(ConsultingTypeManager.class);
    var consultantRoles =
        new de.caritas.cob.userservice.api.manager.consultingtype.roles.Consultant();
    consultantRoles.setRoleSets(
        new java.util.LinkedHashMap<>(Map.of("ordinary", List.of("consultant"))));
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
    var saga = mock(CreateConsultantSaga.class);
    var observed = new AtomicReference<CheckedAccountPolicyReads.Policy>();
    when(saga.createImportedConsultant(
            any(), any(), any(), anyString(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenAnswer(
            call -> {
              observed.set(policies.read(1));
              var consultant = new Consultant();
              consultant.setId("native-fixture-consultant");
              return consultant;
            });
    var importer =
        new ConsultantImportService(
            mock(IdentityUsernameAvailability.class),
            consultants,
            types,
            agencyService,
            users,
            saga,
            mock(ConsultantAgencyRelationCreatorService.class));
    Path csv = temporary.resolve("configured-native.csv");
    String content =
        ",1,nativeuser,\"Native\nGiven\",Family,native-policy@example.invalid,nein,,10;ordinary\r\n";
    Files.writeString(csv, content);
    ReflectionTestUtils.setField(importer, "importFilename", csv.toString());
    ReflectionTestUtils.setField(
        importer, "protocolFilename", temporary.resolve("protocol").toString());
    ReflectionTestUtils.setField(importer, "multiTenancyEnabled", false);
    ReflectionTestUtils.setField(importer, "lifecycleEnvironment", environment);
    ReflectionTestUtils.setField(importer, "lifecycleIdentities", identities);
    ReflectionTestUtils.setField(importer, "checkedPolicyReads", policies);
    long before = queries();
    importer.startImport();
    assertThat(observed.get()).isNotNull();
    assertThat(observed.get().allowedNumberOfUsers()).isEqualTo(5);
    assertThat(cacheControl.get()).contains("no-store");
    assertSameExplicitBearer();
    assertThat(queries()).isEqualTo(before + 1);
    var signed = claims(captured.get().proof());
    assertThat(signed.path("purpose").asText()).isEqualTo("CONSULTANT_IMPORT");
    String digest =
        java.util.HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
    assertThat(signed.path("origin").path("reference").asText())
        .isEqualTo("import:" + digest + ":1");
    assertThatThrownBy(() -> policies.read(1)).isInstanceOf(AccessDeniedException.class);
  }

  private String independentlySign(Map<String, Object> claims) throws Exception {
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(JSON.writeValueAsBytes(new TreeMap<>(claims)));
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return encoded
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(("proof\n" + encoded).getBytes(StandardCharsets.US_ASCII)));
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
