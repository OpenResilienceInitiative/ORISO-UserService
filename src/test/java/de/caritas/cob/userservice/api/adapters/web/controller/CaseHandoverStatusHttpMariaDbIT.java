package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.model.Session.RegistrationType.REGISTERED;
import static de.caritas.cob.userservice.api.model.Session.SessionStatus.IN_PROGRESS;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
@SpringBootTest(
    properties = {
      "multitenancy.enabled=true",
      "feature.multitenancy.with.single.domain.enabled=true"
    })
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
  @Autowired ConsultantTopicRepository consultantTopicRepository;
  @Autowired SessionTopicRepository sessionTopicRepository;
  @Autowired AccountInactivityService inactivity;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean TenantService tenantService;
  private TransactionTemplate transactions;
  private Long sessionId;
  private Long secondSessionId;
  private Consultant ownerA, ownerB, ownerC;
  private final List<Long> candidateSessionIds = new ArrayList<>();

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
    TenantContext.setCurrentTenant(0L);
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
    TenantContext.setCurrentTenant(0L);
    try {
      if (sessionId != null)
        transactions.executeWithoutResult(
            ignored -> {
              caseHandoverRequestRepository.deleteAllBySessionId(sessionId);
              candidateSessionIds.forEach(caseHandoverRequestRepository::deleteAllBySessionId);
              candidateSessionIds.forEach(sessionTopicRepository::deleteAllBySessionId);
              sessionRepository.deleteAllById(candidateSessionIds);
              sessionRepository.deleteAllById(List.of(sessionId, secondSessionId));
              jdbc.update("DELETE FROM consultant_topic WHERE consultant_id = ?", OWNER_B_ID);
              consultantAgencyRepository.deleteAll(
                  consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(OWNER_B_ID));
              consultantRepository.deleteAllById(List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID));
              userRepository.deleteById(USER_ID);
            });
    } finally {
      for (String id : List.of(OWNER_A_ID, OWNER_B_ID, OWNER_C_ID))
        jdbc.update("DELETE FROM account_inactivity WHERE identity_id=?", id);
      TenantContext.clear();
    }
  }

  @Test
  void candidatesPageIncludesScopedSecondaryTopicsAndCountsOnlyVisibleCases() throws Exception {
    transactions.executeWithoutResult(
        ignored -> {
          var now = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1);
          var user = userRepository.findById(USER_ID).orElseThrow();
          var owner = consultantRepository.findById(OWNER_A_ID).orElseThrow();
          var requester = consultantRepository.findById(OWNER_B_ID).orElseThrow();
          consultantTopicRepository.save(
              ConsultantTopic.builder()
                  .consultant(requester)
                  .agencyId(700L)
                  .topicId(5L)
                  .createDate(now)
                  .updateDate(now)
                  .build());

          Session secondaryMatch = proofSession(user, owner, now);
          secondaryMatch.setMainTopicId(99L);
          candidateSessionIds.add(sessionRepository.save(secondaryMatch).getId());
          saveCandidateRequest(
              secondaryMatch, requester, CaseHandoverRequest.Status.DENIED, now, null, 7L);
          saveCandidateRequest(
              secondaryMatch,
              requester,
              CaseHandoverRequest.Status.GRANTED_PENDING_CLIENT_OPTOUT,
              now,
              now.plusHours(2),
              7L);
          saveCandidateRequest(
              secondaryMatch,
              requester,
              CaseHandoverRequest.Status.DENIED,
              now.plusSeconds(1),
              null,
              8L);
          saveCandidateRequest(
              secondaryMatch,
              ownerC,
              CaseHandoverRequest.Status.DENIED,
              now.plusSeconds(2),
              null,
              7L);
          sessionTopicRepository.save(
              SessionTopic.builder()
                  .session(secondaryMatch)
                  .topicId(5L)
                  .createDate(now)
                  .updateDate(now)
                  .build());

          Session primaryMatch = proofSession(user, owner, now);
          primaryMatch.setMainTopicId(5L);
          candidateSessionIds.add(sessionRepository.save(primaryMatch).getId());
          saveCandidateRequest(
              primaryMatch,
              requester,
              CaseHandoverRequest.Status.GRANTED,
              now,
              now.minusSeconds(1),
              7L);

          Session formerTakeover = proofSession(user, owner, now);
          formerTakeover.setMainTopicId(5L);
          candidateSessionIds.add(sessionRepository.save(formerTakeover).getId());
          saveCandidateRequest(
              formerTakeover,
              requester,
              CaseHandoverRequest.Status.GRANTED,
              CaseHandoverRequest.AccessType.TAKEOVER,
              now,
              null,
              7L);

          Session noRequest = proofSession(user, owner, now);
          noRequest.setMainTopicId(5L);
          candidateSessionIds.add(sessionRepository.save(noRequest).getId());
          saveCandidateRequest(noRequest, ownerC, CaseHandoverRequest.Status.DENIED, now, null, 7L);

          Session outOfScope = proofSession(user, owner, now.plusSeconds(3));
          outOfScope.setMainTopicId(99L);
          outOfScope.setAgencyId(701L);
          candidateSessionIds.add(sessionRepository.save(outOfScope).getId());
          sessionTopicRepository.save(
              SessionTopic.builder()
                  .session(outOfScope)
                  .topicId(5L)
                  .createDate(now)
                  .updateDate(now)
                  .build());
        });

    var expectedStatuses =
        List.of("GRANTED_PENDING_CLIENT_OPTOUT", "EXPIRED", "GRANTED", "NOT_REQUESTED");
    for (String term : List.of("", "  STATUS-HTTP-USER  ")) {
      for (int offset = 0; offset < expectedStatuses.size(); offset++) {
        mvc.perform(
                get("/service/users/case-handover/candidates")
                    .header("Authorization", "Bearer " + OWNER_B_ID)
                    .param("query", term)
                    .param("count", "1")
                    .param("offset", Integer.toString(offset)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(expectedStatuses.size()))
            .andExpect(jsonPath("$.offset").value(offset))
            .andExpect(jsonPath("$.count").value(1))
            .andExpect(jsonPath("$.sessions[0].session.id").value(candidateSessionIds.get(offset)))
            .andExpect(jsonPath("$.sessions[0].caseHandoverStatus.length()").value(3))
            .andExpect(
                jsonPath("$.sessions[0].caseHandoverStatus.status")
                    .value(expectedStatuses.get(offset)))
            .andExpect(
                jsonPath("$.sessions[0].caseHandoverStatus.canViewContent").value(offset == 0))
            .andExpect(jsonPath("$.sessions[0].caseHandoverStatus.hasRequest").value(offset < 3));
        mvc.perform(
                get("/service/users/sessions/" + candidateSessionIds.get(offset) + "/case-handover")
                    .header("Authorization", "Bearer " + OWNER_B_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(expectedStatuses.get(offset)))
            .andExpect(jsonPath("$.canViewContent").value(offset == 0));
      }
    }
    mvc.perform(
            get("/service/users/case-handover/candidates")
                .header("Authorization", "Bearer " + OWNER_C_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.sessions").isEmpty());
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
      mvc.perform(get(route).header("tenantId", "7")).andExpect(status().isUnauthorized());
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

  private void saveCandidateRequest(
      Session session,
      Consultant requester,
      CaseHandoverRequest.Status status,
      LocalDateTime createdAt,
      LocalDateTime expiresAt,
      Long tenantId) {
    saveCandidateRequest(session, requester, status, null, createdAt, expiresAt, tenantId);
  }

  private void saveCandidateRequest(
      Session session,
      Consultant requester,
      CaseHandoverRequest.Status status,
      CaseHandoverRequest.AccessType accessType,
      LocalDateTime createdAt,
      LocalDateTime expiresAt,
      Long tenantId) {
    caseHandoverRequestRepository.save(
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .initiatorConsultant(requester)
            .previousConsultant(session.getConsultant())
            .expectedOwnershipRevision(session.getOwnershipRevision())
            .reasonCode("COUNSELLOR_ASKED_FOR_ADVICE")
            .reasonLabel("Synthetic case status")
            .explanation("")
            .status(status)
            .accessType(accessType)
            .clientConsent(CaseHandoverConsentMode.OPT_OUT)
            .clientConsentRequired(false)
            .policyAuthority("test")
            .auditOutcome(status.name())
            .createdAt(createdAt)
            .expiresAt(expiresAt)
            .tenantId(tenantId)
            .build());
  }

  @BeforeEach
  void configureIdentityBoundary() {
    when(tenantService.getRestrictedTenantDataFresh(7L))
        .thenReturn(new RestrictedTenantDTO().id(7L).active(true).subdomain("synthetic"));
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
