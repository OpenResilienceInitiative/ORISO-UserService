package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairScheduler;
import de.caritas.cob.userservice.api.service.CaseHandoverMatrixRepairService;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * Public authenticated co-access, MariaDB rollback and actual Synapse authorization for #1396.
 * Synthetic fixtures only; local integration is separate from Dev browser acceptance.
 */
@SpringBootTest(
    properties = {
      "logging.level.org.springframework.web=INFO",
      "logging.level.org.springframework.web.HttpLogging=OFF",
      "logging.level.org.springframework.web.servlet.mvc.method.annotation=INFO",
      "oriso.testing.coaccess-http-fixture.enabled=true",
      "case.handover.matrix-repair.retry-delay-ms=200",
      "case.handover.matrix-repair.claim-duration=PT0.1S",
      "case.handover.matrix-repair.retry-backoff=PT0.1S"
    })
@Import(CaseHandoverCoAccessHttpSynapseIT.FixtureSchedulers.class)
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class CaseHandoverCoAccessHttpSynapseIT {
  // Imported only, never annotated: AppConfig's component scan would put this frozen @Primary
  // clock into every other test context.
  @ConditionalOnProperty(name = "oriso.testing.coaccess-http-fixture.enabled", havingValue = "true")
  static class FixtureSchedulers {
    // Testing normally omits this worker; register its real production implementation so delayed
    // compensation is exercised by its normal schedule, never by a private helper invocation.
    @Bean
    @Primary
    Clock coAccessFixtureClock() {
      return new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return Clock.fixed(DOMAIN_TIME.get(), zone);
        }

        @Override
        public Instant instant() {
          return DOMAIN_TIME.get();
        }
      };
    }

    @Bean
    CaseHandoverMatrixRepairScheduler realMatrixRepairScheduler(
        CaseHandoverMatrixRepairService repairs, ScheduledTaskClaimService claims) {
      return new CaseHandoverMatrixRepairScheduler(repairs, claims);
    }
  }

  private static final AtomicReference<Instant> DOMAIN_TIME =
      new AtomicReference<>(Instant.now().truncatedTo(ChronoUnit.SECONDS));
  private static final String USER_ID = "status-http-user";
  private static final String OWNER_A_ID = "status-http-owner-a";
  private static final String OWNER_B_ID = "status-http-owner-b";
  private static final String OWNER_C_ID = "status-http-unrelated";
  @Autowired MockMvc mvc;
  @Autowired SessionRepository sessionRepository;
  @Autowired ConsultantRepository consultantRepository;
  @Autowired UserRepository userRepository;
  @Autowired CaseHandoverRequestRepository caseHandoverRequestRepository;
  @Autowired ConsultantAgencyRepository consultantAgencyRepository;
  @Autowired AccountInactivityService inactivity;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean JwtDecoder jwtDecoder;
  private TransactionTemplate transactions;
  private Long sessionId;
  private Long secondSessionId;
  private Consultant ownerA, ownerB, ownerC;

  private static final String SERVER = "matrix-browser-uia.test";
  private static final String ADMIN_PASSWORD = UUID.randomUUID().toString();
  private static final RestTemplate HTTP = new RestTemplate();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final MariaDBContainer<?> DB =
      new MariaDBContainer<>("mariadb:11.4").withDatabaseName("userservice");
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
  private static final GenericContainer<?> SYNAPSE =
      new GenericContainer<>("ghcr.io/element-hq/synapse:v1.158.0")
          .withExposedPorts(8008)
          .withEnv("UID", "0")
          .withEnv("GID", "0")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("matrix-browser-uia/homeserver.yaml"),
              "/data/homeserver.yaml")
          .withCopyFileToContainer(
              MountableFile.forClasspathResource("matrix-browser-uia/test.signing.key"),
              "/data/test.signing.key")
          .waitingFor(Wait.forHttp("/_matrix/client/versions").forStatusCode(200))
          .withStartupTimeout(Duration.ofMinutes(2));
  private static String baseUrl, adminToken;
  private String roomId, selmaToken, pattyToken;
  @Autowired MatrixSynapseService matrix;
  @Autowired RestTemplate matrixTransport;
  @Autowired TenantCaseHandoverPolicyCacheRepository policyCache;

  @DynamicPropertySource
  static void realBoundaries(DynamicPropertyRegistry registry) throws Exception {
    DB.start();
    SYNAPSE.start();
    REDIS.start();
    baseUrl = "http://" + SYNAPSE.getHost() + ":" + SYNAPSE.getMappedPort(8008);
    var nonce = matrixGet("/_synapse/admin/v1/register", null).get("nonce");
    var mac = Mac.getInstance("HmacSHA1");
    mac.init(
        new SecretKeySpec(
            "public-disposable-uia-registration-fixture".getBytes(StandardCharsets.UTF_8),
            "HmacSHA1"));
    var digest =
        java.util.HexFormat.of()
            .formatHex(
                mac.doFinal(
                    (nonce + "\0testadmin\0" + ADMIN_PASSWORD + "\0admin")
                        .getBytes(StandardCharsets.UTF_8)));
    adminToken =
        (String)
            matrixPost(
                    "/_synapse/admin/v1/register",
                    null,
                    Map.of(
                        "nonce",
                        nonce,
                        "username",
                        "testadmin",
                        "password",
                        ADMIN_PASSWORD,
                        "admin",
                        true,
                        "mac",
                        digest))
                .get("access_token");
    registry.add("spring.datasource.url", DB::getJdbcUrl);
    registry.add("spring.datasource.username", DB::getUsername);
    registry.add("spring.datasource.password", DB::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("matrix.apiUrl", () -> baseUrl);
    registry.add("matrix.serverName", () -> SERVER);
    registry.add("matrix.adminUsername", () -> "testadmin");
    registry.add("matrix.adminPassword", () -> ADMIN_PASSWORD);
    registry.add("logging.level.org.springframework.web", () -> "WARN");
    registry.add(
        "logging.level.org.springframework.web.servlet.mvc.method.annotation", () -> "WARN");
  }

  @BeforeEach
  void createFixture() {
    DOMAIN_TIME.set(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    transactions = new TransactionTemplate(transactionManager);
    String selmaId = createMatrixUser(OWNER_A_ID + "-" + UUID.randomUUID());
    String pattyId = createMatrixUser(OWNER_B_ID + "-" + UUID.randomUUID());
    String herbId = createMatrixUser(OWNER_C_ID + "-" + UUID.randomUUID());
    selmaToken = matrix.loginAsUserAccessToken(selmaId);
    pattyToken = matrix.loginAsUserAccessToken(pattyId);
    roomId =
        (String)
            matrixPost(
                    "/_matrix/client/v3/createRoom",
                    selmaToken,
                    Map.of(
                        "preset", "private_chat", "room_version", "11", "invite", List.of(pattyId)))
                .get("room_id");
    assertThat(matrix.joinRoom(roomId, pattyToken)).isTrue();
    assertThat(matrix.setUserPowerLevel(roomId, pattyId, 100, selmaToken)).isTrue();
    sessionId =
        transactions.execute(
            ignored -> {
              LocalDateTime now = LocalDateTime.now();
              User user =
                  userRepository.save(
                      User.builder()
                          .userId(USER_ID)
                          .username(USER_ID)
                          .email("status-http-user@example.invalid")
                          .languageFormal(false)
                          .encourage2fa(true)
                          .magicLinkLoginEnabled(false)
                          .languageCode(LanguageCode.de)
                          .tenantId(7L)
                          .notificationsEnabled(false)
                          .createDate(now)
                          .updateDate(now)
                          .build());
              ownerA = consultantRepository.save(consultant(OWNER_A_ID, now));
              ownerB = consultantRepository.save(consultant(OWNER_B_ID, now));
              ownerA.setMatrixUserId(selmaId);
              consultantRepository.save(ownerA);
              ownerB.setMatrixUserId(pattyId);
              consultantRepository.save(ownerB);
              ownerC = consultantRepository.save(consultant(OWNER_C_ID, now));
              ownerC.setMatrixUserId(herbId);
              consultantRepository.save(ownerC);
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(ownerC)
                      .agencyId(700L)
                      .tenantId(7L)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(ownerA)
                      .agencyId(700L)
                      .tenantId(7L)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              policyCache.save(
                  TenantCaseHandoverPolicyCache.builder()
                      .tenantId(7L)
                      .refreshedAt(now)
                      .policies(
                          """
                {"reasons":{"COUNSELLOR_ASKED_FOR_ADVICE":{"code":"COUNSELLOR_ASKED_FOR_ADVICE","labels":{"value":{"de":"Rat erbeten"}},"enabled":{"value":true},"accessAllowed":{"value":true},"clientConsent":{"value":"OPT_OUT"},"maxAccessDurationMinutes":{"value":180},"approvalRoles":{"value":[]}},"COUNSELLOR_IS_ILL":{"code":"COUNSELLOR_IS_ILL","labels":{"value":{"de":"Ungeplant abwesend"}},"enabled":{"value":true},"accessAllowed":{"value":true},"clientConsent":{"value":"NONE"},"approvalRoles":{"value":[]}}}}
                """)
                      .build());
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(ownerB)
                      .agencyId(700L)
                      .tenantId(7L)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              Session fixture = proofSession(user, ownerB, now);
              fixture.setMatrixRoomId(roomId);
              Session first = sessionRepository.save(fixture);
              secondSessionId = sessionRepository.save(proofSession(user, ownerB, now)).getId();
              return first.getId();
            });
    for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID, USER_ID))
      inactivity.assignAtCreation(id, 7L, 12, 1L, Instant.now());
  }

  @AfterEach
  void removeFixture() {
    try {
      if (sessionId != null)
        transactions.executeWithoutResult(
            ignored -> {
              jdbc.update(
                  "DELETE FROM case_handover_matrix_repair_task WHERE session_id=?", sessionId);
              caseHandoverRequestRepository.deleteAllBySessionId(sessionId);
              sessionRepository.deleteAllById(List.of(sessionId, secondSessionId));
              consultantAgencyRepository.deleteAll(
                  consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_B_ID));
              consultantAgencyRepository.deleteAll(
                  consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_A_ID));
              consultantAgencyRepository.deleteAll(
                  consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_C_ID));
              jdbc.update(
                  "DELETE FROM event_notification WHERE recipient_user_id IN (?, ?, ?, ?)",
                  USER_ID,
                  OWNER_A_ID,
                  OWNER_B_ID,
                  OWNER_C_ID);
              policyCache.deleteById(7L);
              consultantRepository.deleteAllById(List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID));
              userRepository.deleteById(USER_ID);
            });
    } finally {
      for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID, USER_ID))
        jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", id);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 100})
  void formerOwnerReadsButCannotWriteAndClientObjectionEndsAccessWithoutChangingCurrentOwner(
      int initialPower) throws Exception {
    if (initialPower == 0) {
      assertThat(matrix.setUserPowerLevel(roomId, ownerA.getMatrixUserId(), 0, selmaToken))
          .isTrue();
    }
    assertThat(matrixSend(pattyToken, "Existing advice history: the blue folder"))
        .containsKey("event_id");
    var result =
        mvc.perform(
                post(route())
                    .header("Authorization", "Bearer " + OWNER_A_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
          {"reasonCode":"ADVICE_REQUESTED","explanation":"Please review the existing case","expectedOwnershipRevision":0,"operationId":"35259c37-b5f7-49b5-9648-86fe76e9fb17"}
          """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("GRANTED_PENDING_CLIENT_OPTOUT"))
            .andExpect(jsonPath("$.canViewContent").value(true))
            .andExpect(jsonPath("$.accessType").value("CO_ACCESS"))
            .andExpect(jsonPath("$.ownershipRevision").value(0))
            .andReturn();
    long requestId =
        JSON.readTree(result.getResponse().getContentAsString()).path("requestId").asLong();
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
        .andExpect(jsonPath("$.ownershipRevision").value(0));
    var history =
        (List<Map<String, Object>>)
            matrixGet("/_matrix/client/v3/rooms/" + roomId + "/messages?dir=b", selmaToken)
                .get("chunk");
    assertThat(history)
        .anySatisfy(
            event ->
                assertThat(((Map<?, ?>) event.get("content")).get("body"))
                    .isEqualTo("Existing advice history: the blue folder"));
    assertThatThrownBy(() -> matrixSend(selmaToken, "Selma must not send"))
        .isInstanceOfSatisfying(
            HttpClientErrorException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    assertThat(matrixSend(pattyToken, "Patty remains responsible")).containsKey("event_id");
    mvc.perform(
            post(route() + "/" + requestId + "/client-consent")
                .header("Authorization", "Bearer " + USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approved\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CLIENT_CONSENT_DECLINED"));
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewContent").value(false));
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
        .andExpect(jsonPath("$.ownershipRevision").value(0));
    for (String alias : List.of("/matrix", "/service/matrix")) {
      mvc.perform(
              get(alias + "/sessions/" + sessionId + "/messages")
                  .header("Authorization", "Bearer " + OWNER_A_ID))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(alias + "/sessions/" + sessionId + "/messages")
                  .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "public-csrf-fixture"))
                  .header("X-CSRF-Token", "public-csrf-fixture")
                  .header("Authorization", "Bearer " + OWNER_A_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"message\":\"Denied after objection\"}"))
          .andExpect(status().isForbidden());
    }
    for (String alias : List.of("/matrix", "/service/matrix")) {
      mvc.perform(
              post(alias + "/sessions/" + sessionId + "/messages")
                  .cookie(new jakarta.servlet.http.Cookie("CSRF-TOKEN", "public-csrf-fixture"))
                  .header("X-CSRF-Token", "public-csrf-fixture")
                  .header("Authorization", "Bearer " + OWNER_B_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"message\":\"Current owner may still send\"}"))
          .andExpect(status().isOk());
    }
    // ADR002: objection ends app access; standing membership remains at ordinary member power.
    var users =
        (Map<?, ?>)
            matrixGet(
                    "/_matrix/client/v3/rooms/" + roomId + "/state/m.room.power_levels", pattyToken)
                .get("users");
    assertThat(users.get(ownerA.getMatrixUserId())).isEqualTo(0);
    assertThat(users.get(ownerB.getMatrixUserId())).isEqualTo(100);
  }

  @Test
  void deniedOwnerAndSelfPowerWritesLeaveNoApplicationGrant() throws Exception {
    roomId =
        (String)
            matrixPost(
                    "/_matrix/client/v3/createRoom",
                    selmaToken,
                    Map.of(
                        "preset",
                        "private_chat",
                        "room_version",
                        "11",
                        "invite",
                        List.of(ownerB.getMatrixUserId()),
                        "initial_state",
                        List.of(
                            Map.of(
                                "type",
                                "m.room.power_levels",
                                "state_key",
                                "",
                                "content",
                                Map.of(
                                    "users",
                                    Map.of(
                                        ownerA.getMatrixUserId(),
                                        100,
                                        ownerB.getMatrixUserId(),
                                        100),
                                    "events",
                                    Map.of("m.room.power_levels", 150))))))
                .get("room_id");
    assertThat(matrix.joinRoom(roomId, pattyToken)).isTrue();
    transactions.executeWithoutResult(
        ignored -> {
          var session = sessionRepository.findById(sessionId).orElseThrow();
          session.setMatrixRoomId(roomId);
          sessionRepository.save(session);
        });
    mvc.perform(
            post(route())
                .header("Authorization", "Bearer " + OWNER_A_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
        {"reasonCode":"ADVICE_REQUESTED","explanation":"Review","expectedOwnershipRevision":0,"operationId":"399de884-2edb-4374-b6b3-a01bb28e0cb3"}
        """))
        .andExpect(status().isInternalServerError());
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewContent").value(false))
        .andExpect(jsonPath("$.status").value("NOT_REQUESTED"));
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
        .andExpect(jsonPath("$.ownershipRevision").value(0));
    assertThat(powerOfFormerOwner()).isEqualTo(100);
  }

  @Test
  void rejectedDatabaseGrantRestoresFormerOwnerPowerAndKeepsTheCaseHidden() throws Exception {
    // Failure injection only in this disposable MariaDB, after Matrix demotion but before grant
    // persistence. Assertions still use the public request/status APIs and actual Matrix state.
    jdbc.execute(
        "CREATE TRIGGER fail_advice_grant BEFORE INSERT ON case_handover_request FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='disposable grant failure'");
    try {
      mvc.perform(
              post(route())
                  .header("Authorization", "Bearer " + OWNER_A_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
          {"reasonCode":"ADVICE_REQUESTED","explanation":"Review","expectedOwnershipRevision":0,"operationId":"11b14fd5-48bb-4b4d-a05b-f65632b66ca0"}
          """))
          .andExpect(status().isInternalServerError());
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS fail_advice_grant");
    }
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canViewContent").value(false));
    mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
        .andExpect(jsonPath("$.ownershipRevision").value(0));
    var users =
        (Map<?, ?>)
            matrixGet(
                    "/_matrix/client/v3/rooms/" + roomId + "/state/m.room.power_levels", pattyToken)
                .get("users");
    assertThat(users.get(ownerA.getMatrixUserId())).isEqualTo(100);
    assertThat(users.get(ownerB.getMatrixUserId())).isEqualTo(100);
  }

  @Test
  void temporaryMatrixOutageAfterDatabaseRollbackIsRepairedWithoutAUserRetry() throws Exception {
    var outage = new AtomicBoolean(true);
    var attempts = new AtomicInteger();
    var fault = rejectRestorationDuring(outage, attempts);
    matrixTransport.getInterceptors().add(fault);
    try {
      rejectedGrant();
      await().atMost(Duration.ofSeconds(8)).until(() -> attempts.get() >= 2);
      long queuedRepairId = awaitFailedScheduledRepair();
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.canViewContent").value(false));
      assertThat(powerOfFormerOwner()).isEqualTo(-1);
      outage.set(false);
      DOMAIN_TIME.set(DOMAIN_TIME.get().plusSeconds(1));
      awaitRepairCompletion(queuedRepairId);
      assertThat(powerOfFormerOwner()).isEqualTo(100);
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
          .andExpect(jsonPath("$.ownershipRevision").value(0));
    } finally {
      outage.set(false);
      matrixTransport.getInterceptors().remove(fault);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void delayedRollbackRepairCannotGiveWriteAccessOverANewerCoAccessGrant(boolean expiresBeforeRetry)
      throws Exception {
    var outage = new AtomicBoolean(true);
    var attempts = new AtomicInteger();
    var fault = rejectRestorationDuring(outage, attempts);
    matrixTransport.getInterceptors().add(fault);
    try {
      rejectedGrant();
      await().atMost(Duration.ofSeconds(8)).until(() -> attempts.get() >= 2);
      long queuedRepairId = awaitFailedScheduledRepair();
      var result =
          mvc.perform(
                  post(route())
                      .header("Authorization", "Bearer " + OWNER_A_ID)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          """
          {"reasonCode":"ADVICE_REQUESTED","explanation":"New review","expectedOwnershipRevision":0,"operationId":"5533109b-1c2a-4770-a578-8169ea307b4e"}
          """))
              .andExpect(status().isCreated())
              .andExpect(jsonPath("$.canViewContent").value(true))
              .andExpect(jsonPath("$.createdAt").value(fixtureTimestamp()))
              .andReturn();
      long requestId =
          JSON.readTree(result.getResponse().getContentAsString()).path("requestId").asLong();
      if (expiresBeforeRetry) {
        DOMAIN_TIME.set(DOMAIN_TIME.get().plus(Duration.ofMinutes(181)));
        mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("EXPIRED"))
            .andExpect(jsonPath("$.canViewContent").value(false));
      }
      outage.set(false);
      DOMAIN_TIME.set(DOMAIN_TIME.get().plusSeconds(1));
      awaitRepairCompletion(queuedRepairId);
      assertThat(powerOfFormerOwner()).isEqualTo(-1);
      assertThatThrownBy(() -> matrixSend(selmaToken, "Still forbidden"))
          .isInstanceOfSatisfying(
              HttpClientErrorException.class,
              e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
      if (!expiresBeforeRetry) {
        mvc.perform(
                post(route() + "/" + requestId + "/client-consent")
                    .header("Authorization", "Bearer " + USER_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"approved\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLIENT_CONSENT_DECLINED"));
        assertThat(powerOfFormerOwner()).isEqualTo(0);
        mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.canViewContent").value(false));
      }
    } finally {
      outage.set(false);
      matrixTransport.getInterceptors().remove(fault);
    }
  }

  @Test
  void matchingOperationRetryProtectsExpiredGrantDespiteBackwardClockAdjustment() throws Exception {
    var originalTime = DOMAIN_TIME.get();
    var outage = new AtomicBoolean(true);
    var attempts = new AtomicInteger();
    var fault = rejectRestorationDuring(outage, attempts);
    matrixTransport.getInterceptors().add(fault);
    try {
      rejectedGrant();
      await().atMost(Duration.ofSeconds(8)).until(() -> attempts.get() >= 2);
      long queuedRepairId = awaitFailedScheduledRepair();
      // The failed attempt's request was rolled back. Retry its public operation identity with
      // an older clock, so creation/resolution dates cannot protect it from the old repair task.
      DOMAIN_TIME.set(originalTime.minus(Duration.ofHours(4)));
      mvc.perform(
              post(route())
                  .header("Authorization", "Bearer " + OWNER_A_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
          {"reasonCode":"ADVICE_REQUESTED","explanation":"Review","expectedOwnershipRevision":0,"operationId":"11b14fd5-48bb-4b4d-a05b-f65632b66ca0"}
          """))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("GRANTED_PENDING_CLIENT_OPTOUT"))
          .andExpect(jsonPath("$.operationId").value("11b14fd5-48bb-4b4d-a05b-f65632b66ca0"))
          .andExpect(jsonPath("$.createdAt").value(fixtureTimestamp()))
          .andExpect(jsonPath("$.resolvedAt").isEmpty())
          .andExpect(jsonPath("$.ownershipRevision").value(0));
      DOMAIN_TIME.set(originalTime.plusSeconds(1));
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("EXPIRED"))
          .andExpect(jsonPath("$.canViewContent").value(false));
      outage.set(false);
      awaitRepairCompletion(queuedRepairId);
      assertThat(powerOfFormerOwner()).isEqualTo(-1);
      assertThatThrownBy(() -> matrixSend(selmaToken, "Expired retry must not grant writing"))
          .isInstanceOfSatisfying(
              HttpClientErrorException.class,
              e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_B_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
          .andExpect(jsonPath("$.ownershipRevision").value(0));
    } finally {
      outage.set(false);
      DOMAIN_TIME.set(originalTime.plusSeconds(1));
      matrixTransport.getInterceptors().remove(fault);
    }
  }

  @Test
  void laterTakeoverSupersedesQueuedRollbackWithoutAnyNewCoAccessGrant() throws Exception {
    var outage = new AtomicBoolean(true);
    var attempts = new AtomicInteger();
    var fault = rejectRestorationDuring(outage, attempts);
    matrixTransport.getInterceptors().add(fault);
    try {
      rejectedGrant();
      await().atMost(Duration.ofSeconds(8)).until(() -> attempts.get() >= 2);
      long queuedRepairId = awaitFailedScheduledRepair();
      mvc.perform(
              post(route())
                  .header("Authorization", "Bearer " + OWNER_C_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
          {"reasonCode":"UNPLANNED_ABSENCE","explanation":"Cover the current case","expectedOwnershipRevision":0,"operationId":"c203d3ed-9a5c-4324-80c1-d421eed61572"}
          """))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("GRANTED"))
          .andExpect(jsonPath("$.accessType").value("TAKEOVER"))
          .andExpect(jsonPath("$.ownershipRevision").value(1));
      outage.set(false);
      DOMAIN_TIME.set(DOMAIN_TIME.get().plusSeconds(1));
      awaitRepairCompletion(queuedRepairId);
      assertThat(powerOfFormerOwner()).isEqualTo(-1);
      assertThatThrownBy(() -> matrixSend(selmaToken, "Old rollback must not grant writing"))
          .isInstanceOfSatisfying(
              HttpClientErrorException.class,
              e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_C_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.auditOutcome").value("ACTIVE_OWNER"))
          .andExpect(jsonPath("$.ownershipRevision").value(1));
      mvc.perform(get(route()).header("Authorization", "Bearer " + OWNER_A_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("NOT_REQUESTED"))
          .andExpect(jsonPath("$.canViewContent").value(false));
      var herbToken = matrix.loginAsUserAccessToken(ownerC.getMatrixUserId());
      assertThat(matrixSend(herbToken, "The new owner can still send")).containsKey("event_id");
    } finally {
      outage.set(false);
      matrixTransport.getInterceptors().remove(fault);
    }
  }

  /**
   * Queue identity/attempt completion are fixture coordination only. Permission and ownership
   * outcomes remain asserted through public HTTP and actual Matrix, never through database rows.
   */
  private long awaitFailedScheduledRepair() {
    var queuedId = new AtomicReference<Long>();
    await()
        .atMost(Duration.ofSeconds(8))
        .until(
            () -> {
              var ids =
                  jdbc.queryForList(
                      """
          SELECT id FROM case_handover_matrix_repair_task
          WHERE session_id = ? AND room_id = ? AND member_id = ? AND action = 'POWER_RESTORE'
            AND attempt_count > 0 AND last_attempt_at IS NOT NULL
          """,
                      Long.class,
                      sessionId,
                      roomId,
                      ownerA.getMatrixUserId());
              if (ids.size() != 1) {
                return false;
              }
              queuedId.set(ids.get(0));
              return true;
            });
    return queuedId.get();
  }

  private void awaitRepairCompletion(long queuedRepairId) {
    // A guarded no-op need not send any Matrix PUT. Wait for the exact previously observed task's
    // committed completion, so a quiet/paused worker can never make unchanged power a false pass.
    await()
        .atMost(Duration.ofSeconds(8))
        .until(
            () ->
                jdbc.queryForList(
                        "SELECT id FROM case_handover_matrix_repair_task WHERE id = ?",
                        Long.class,
                        queuedRepairId)
                    .isEmpty());
  }

  private String fixtureTimestamp() {
    return LocalDateTime.ofInstant(DOMAIN_TIME.get(), ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
  }

  private ClientHttpRequestInterceptor rejectRestorationDuring(
      AtomicBoolean outage, AtomicInteger attempts) {
    return (request, body, next) -> {
      if (request.getMethod() == HttpMethod.PUT
          && request.getURI().getPath().endsWith("/state/m.room.power_levels")) {
        var users = JSON.readTree(body).path("users");
        if (users.path(ownerA.getMatrixUserId()).asInt() == 100 && outage.get()) {
          attempts.incrementAndGet();
          throw new ResourceAccessException("Disposable Matrix restoration outage");
        }
      }
      return next.execute(request, body);
    };
  }

  private int powerOfFormerOwner() {
    var users =
        (Map<?, ?>)
            matrixGet(
                    "/_matrix/client/v3/rooms/" + roomId + "/state/m.room.power_levels", pattyToken)
                .get("users");
    return ((Number) users.get(ownerA.getMatrixUserId())).intValue();
  }

  private void rejectedGrant() throws Exception {
    jdbc.execute(
        "CREATE TRIGGER fail_advice_grant BEFORE INSERT ON case_handover_request FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='disposable grant failure'");
    try {
      mvc.perform(
              post(route())
                  .header("Authorization", "Bearer " + OWNER_A_ID)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
          {"reasonCode":"ADVICE_REQUESTED","explanation":"Review","expectedOwnershipRevision":0,"operationId":"11b14fd5-48bb-4b4d-a05b-f65632b66ca0"}
          """))
          .andExpect(status().isInternalServerError());
    } finally {
      jdbc.execute("DROP TRIGGER IF EXISTS fail_advice_grant");
    }
  }

  private String route() {
    return "/service/users/sessions/" + sessionId + "/case-handover";
  }

  private Map<String, Object> matrixSend(String token, String text) {
    return HTTP.exchange(
            baseUrl
                + "/_matrix/client/v3/rooms/"
                + roomId
                + "/send/m.room.message/"
                + UUID.randomUUID(),
            HttpMethod.PUT,
            entity(token, Map.of("msgtype", "m.text", "body", text)),
            Map.class)
        .getBody();
  }

  private static String createMatrixUser(String name) {
    String id = "@" + name + ":" + SERVER;
    HTTP.exchange(
        baseUrl + "/_synapse/admin/v2/users/{id}",
        HttpMethod.PUT,
        entity(adminToken, Map.of("admin", false)),
        Map.class,
        id);
    return id;
  }

  private static Map<String, Object> matrixGet(String path, String token) {
    return HTTP.exchange(baseUrl + path, HttpMethod.GET, entity(token, null), Map.class).getBody();
  }

  private static Map<String, Object> matrixPost(
      String path, String token, Map<String, Object> body) {
    return HTTP.postForObject(baseUrl + path, entity(token, body), Map.class);
  }

  private static HttpEntity<Map<String, Object>> entity(String token, Map<String, Object> body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return new HttpEntity<>(body, headers);
  }

  @BeforeEach
  void configureIdentityBoundary() {
    for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID, USER_ID)) {
      Jwt token =
          Jwt.withTokenValue(id)
              .header("alg", "RS256")
              .subject(id)
              .claim("preferred_username", id)
              .claim("tenantId", 7)
              .claim(
                  "realm_access",
                  Map.of("roles", List.of(id.equals(USER_ID) ? "user" : "consultant")))
              .claim("auth_time", Instant.now().getEpochSecond())
              .issuedAt(Instant.now())
              .expiresAt(Instant.now().plusSeconds(300))
              .build();
      when(jwtDecoder.decode(id)).thenReturn(token);
    }
  }

  private static Session proofSession(User user, Consultant owner, LocalDateTime now) {
    return Session.builder()
        .user(user)
        .consultant(owner)
        .tenantId(7L)
        .agencyId(700L)
        .consultingTypeId(0)
        .registrationType(REGISTERED)
        .conversationType(ConversationType.AGENCY_COUNSELLING)
        .postcode("00000")
        .languageCode(LanguageCode.de)
        .status(IN_PROGRESS)
        .teamSession(false)
        .isConsultantDirectlySet(false)
        .createDate(now)
        .updateDate(now)
        .ownershipRevision(0L)
        .rowVersion(0L)
        .build();
  }

  private static Consultant consultant(String id, LocalDateTime now) {
    return Consultant.builder()
        .id(id)
        .matrixUserId("@" + id + ":oriso.invalid")
        .username(id)
        .firstName(id)
        .lastName("Proof")
        .email(id + "@example.invalid")
        .encourage2fa(true)
        .magicLinkLoginEnabled(false)
        .notifyEnquiriesRepeating(true)
        .notifyNewChatMessageFromAdviceSeeker(true)
        .walkThroughEnabled(true)
        .languageCode(LanguageCode.de)
        .tenantId(7L)
        .status(ConsultantStatus.IN_PROGRESS)
        .createDate(now)
        .updateDate(now)
        .build();
  }
}
