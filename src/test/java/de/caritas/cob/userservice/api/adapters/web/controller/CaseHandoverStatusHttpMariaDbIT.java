package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

/** Real HTTP/security/service transaction and MariaDB regression for PR1388. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
class CaseHandoverStatusHttpMariaDbIT {
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

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    registry.add(
        "spring.datasource.username",
        () -> System.getenv().getOrDefault("LIQUIBASE_IT_DB_USERNAME", "root"));
    registry.add("spring.datasource.password", () -> System.getenv("LIQUIBASE_IT_DB_PASSWORD"));
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
  }

  @BeforeEach
  void createFixture() {
    transactions = new TransactionTemplate(transactionManager);
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
              ownerC = consultantRepository.save(consultant(OWNER_C_ID, now));
              consultantAgencyRepository.save(
                  ConsultantAgency.builder()
                      .consultant(ownerB)
                      .agencyId(700L)
                      .tenantId(7L)
                      .createDate(now)
                      .updateDate(now)
                      .build());
              Session first = sessionRepository.save(proofSession(user, ownerA, now));
              secondSessionId = sessionRepository.save(proofSession(user, ownerB, now)).getId();
              return first.getId();
            });
    for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID))
      inactivity.assignAtCreation(id, 7L, 12, 1L, Instant.now());
  }

  @AfterEach
  void removeFixture() {
    try {
      if (sessionId != null)
        transactions.executeWithoutResult(
            ignored -> {
              caseHandoverRequestRepository.deleteAllBySessionId(sessionId);
              sessionRepository.deleteAllById(List.of(sessionId, secondSessionId));
              consultantAgencyRepository.deleteAll(
                  consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_B_ID));
              consultantRepository.deleteAllById(List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID));
              userRepository.deleteById(USER_ID);
            });
    } finally {
      for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID))
        jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", id);
    }
  }

  @Test
  void authenticatedRecipientReadsPendingOfferWhileUnrelatedOrWrongSessionCannotReadIt()
      throws Exception {
    Long requestId =
        transactions.execute(
            ignored ->
                caseHandoverRequestRepository
                    .save(
                        CaseHandoverRequest.builder()
                            .session(sessionRepository.findById(sessionId).orElseThrow())
                            .requesterConsultant(ownerB)
                            .initiatorConsultant(ownerA)
                            .previousConsultant(ownerA)
                            .direction(CaseHandoverRequest.Direction.PUSH)
                            .expectedOwnershipRevision(0L)
                            .operationId(
                                java.util.UUID.fromString("5b0f3a63-2a43-4f43-9a54-3f0c8e1d2b7a"))
                            .reasonCode("UNPLANNED_ABSENCE")
                            .reasonLabel("Other emergency")
                            .explanation("")
                            .status(CaseHandoverRequest.Status.PENDING_RECIPIENT_ACCEPTANCE)
                            .clientConsent(
                                de.caritas.cob.userservice.api.model.CaseHandoverConsentMode.NONE)
                            .accessType(CaseHandoverRequest.AccessType.TAKEOVER)
                            .clientConsentRequired(false)
                            .policyAuthority("test")
                            .auditOutcome("PENDING_RECIPIENT_ACCEPTANCE")
                            .createdAt(LocalDateTime.now())
                            .tenantId(7L)
                            .build())
                    .getId());

    for (String prefix : List.of("", "/service")) {
      String route = prefix + "/users/sessions/" + sessionId + "/case-handover/" + requestId;
      mvc.perform(get(route).header("Authorization", "Bearer " + OWNER_B_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.requestId").value(requestId))
          .andExpect(jsonPath("$.sessionId").value(sessionId))
          .andExpect(jsonPath("$.status").value("PENDING_RECIPIENT_ACCEPTANCE"))
          .andExpect(jsonPath("$.canViewContent").value(false));
      mvc.perform(get(route).header("Authorization", "Bearer " + OWNER_C_ID))
          .andExpect(status().isNotFound());
      mvc.perform(get(route)).andExpect(status().isUnauthorized());
      mvc.perform(
              get(prefix + "/users/sessions/" + secondSessionId + "/case-handover")
                  .header("Authorization", "Bearer " + OWNER_B_ID))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("GRANTED"));
      mvc.perform(
              get(prefix + "/users/sessions/" + secondSessionId + "/case-handover/" + requestId)
                  .header("Authorization", "Bearer " + OWNER_B_ID))
          .andExpect(status().isNotFound());
    }
  }

  @BeforeEach
  void configureIdentityBoundary() {
    for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID)) {
      Jwt token =
          Jwt.withTokenValue(id)
              .header("alg", "RS256")
              .subject(id)
              .claim("preferred_username", id)
              .claim("tenantId", 7)
              .claim("realm_access", Map.of("roles", List.of("consultant")))
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
