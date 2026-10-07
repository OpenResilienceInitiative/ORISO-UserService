package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.controller.EventNotificationController;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.NotificationRoomLevel;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.matrix.MatrixFeedUpdateSignalService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityEffects;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real controller, membership, room-level and feed repositories; external auth/mail remain ports.
 */
@DataJpaTest
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing,team-notification-account-fixture",
      "multitenancy.enabled=true"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
  TeamDiscussionNotificationIT.AccountFixture.class,
  EventNotificationController.class,
  MessageEventCurrentAccessService.class,
  MatrixCaseReplyActorAuthorizer.class,
  TeamDiscussionNotificationService.class,
  EventNotificationService.class,
  EventNotificationDeduplicationWriter.class,
  ConsultantDisplayNameResolver.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamDiscussionNotificationIT {
  private static final String ROOM = "!team-qualification:synthetic.oriso.test";
  private static final String SENDER = "team-sender";
  private static final String RECEIVER = "team-receiver";
  private static final String LEGACY = "team-legacy";

  @TestConfiguration(proxyBeanMethods = false)
  @org.springframework.context.annotation.Profile("team-notification-account-fixture")
  @EnableAspectJAutoProxy
  static class AccountFixture {
    @Bean
    AccountInactivityService accountLifecycle(
        javax.sql.DataSource dataSource, PlatformTransactionManager transactionManager) {
      return new AccountInactivityService(
          new JdbcTemplate(dataSource),
          transactionManager,
          Clock.systemUTC(),
          mock(AccountInactivityEffects.class));
    }
  }

  @Autowired private javax.sql.DataSource dataSource;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AccountInactivityService lifecycle;
  @Autowired private UserRepository users;
  @Autowired private ConsultantRepository consultants;
  @Autowired private ConsultantAgencyRepository agencies;
  @Autowired private SessionRepository sessions;
  @Autowired private TeamDiscussionRepository discussions;
  @Autowired private TeamDiscussionParticipantRepository participants;
  @Autowired private NotificationRoomLevelRepository levels;
  @Autowired private EventNotificationRepository events;
  @Autowired private EventNotificationController controller;
  @MockitoBean private AuthenticatedUser authenticatedUser;
  @MockitoBean private IdentityTombstoneService tombstones;
  @MockitoBean private MatrixFeedUpdateSignalService feedSignals;
  @MockitoBean private FeedbackMessageEmailService feedbackMail;
  @MockitoBean private InternalChatEmailService internalMail;
  @MockitoBean private MatrixSynapseService matrix;
  @MockitoBean private SessionService sessionService;
  private MockMvc mvc;
  private Long discussionId;

  @BeforeEach
  void seedCurrentMembership() {
    TenantContext.setCurrentTenant(7L);
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute(
        "CREATE TABLE IF NOT EXISTS account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,"
            + "tenant_id BIGINT,assigned_months INT NOT NULL,revision BIGINT NOT NULL,"
            + "last_activity TIMESTAMP(6) NOT NULL,due_at TIMESTAMP(6) NOT NULL,"
            + "status VARCHAR(20) NOT NULL,last_error VARCHAR(1000),attempts INT DEFAULT 0 NOT NULL)");
    jdbc.update("DELETE FROM account_inactivity");
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              var owner =
                  users.save(
                      User.builder()
                          .userId("team-owner")
                          .username("team-owner")
                          .email("owner@synthetic.oriso.test")
                          .tenantId(7L)
                          .matrixUserId("@team-owner:synthetic.oriso.test")
                          .encourage2fa(true)
                          .magicLinkLoginEnabled(false)
                          .languageCode(LanguageCode.de)
                          .build());
              var session = new Session(owner, 1, "12345", 70L, Session.SessionStatus.NEW, false);
              session.setTenantId(7L);
              session.setLanguageCode(LanguageCode.de);
              session.setIsConsultantDirectlySet(false);
              session.setMatrixRoomId("!private-client:synthetic.oriso.test");
              sessions.save(session);
              discussionId =
                  discussions
                      .save(
                          TeamDiscussion.builder()
                              .sessionId(session.getId())
                              .matrixRoomId(ROOM)
                              .tenantId(7L)
                              .createDate(LocalDateTime.now())
                              .build())
                      .getId();
              for (String id : new String[] {SENDER, RECEIVER, LEGACY}) {
                var consultant = new Consultant();
                consultant.setId(id);
                consultant.setTenantId(7L);
                consultant.setUsername(id);
                consultant.setFirstName("Synthetic");
                consultant.setLastName(id);
                consultant.setEmail(id + "@synthetic.oriso.test");
                consultant.setMatrixUserId("@" + id + ":synthetic.oriso.test");
                consultant.setEncourage2fa(true);
                consultant.setMagicLinkLoginEnabled(false);
                consultant.setNotifyEnquiriesRepeating(true);
                consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
                consultant.setLanguageCode(LanguageCode.de);
                consultant.setNotificationsEnabled(true);
                consultant = consultants.save(consultant);
                agencies.save(
                    ConsultantAgency.builder()
                        .consultant(consultant)
                        .agencyId(70L)
                        .status(ConsultantAgencyStatus.CREATED)
                        .tenantId(7L)
                        .createDate(LocalDateTime.now())
                        .build());
              }
            });
    when(authenticatedUser.getUserId()).thenReturn(SENDER);
    mvc = MockMvcBuilders.standaloneSetup(controller).build();
  }

  @AfterEach
  void removeFixture() {
    TenantContext.setCurrentTenant(0L);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              events.deleteAll();
              participants.deleteAll();
              levels.deleteAll();
              discussions.deleteAll();
              agencies.deleteAll();
              sessions.deleteAll();
              consultants.deleteAll();
              users.deleteAll();
            });
    TenantContext.clear();
  }

  @ParameterizedTest
  @EnumSource(AccountInactivityService.Status.class)
  void currentTeamRecipientRequiresActiveLifecycleDespiteRetainedDomainAndMembership(
      AccountInactivityService.Status accountStatus) throws Exception {
    lifecycle.assignAtCreation(RECEIVER, 7L, 12, 1L, Instant.now());
    new JdbcTemplate(dataSource)
        .update(
            "UPDATE account_inactivity SET status=? WHERE identity_id=?",
            accountStatus.name(),
            RECEIVER);
    assertThat(lifecycle.snapshot(RECEIVER).orElseThrow().status()).isEqualTo(accountStatus);
    assertThat(consultants.findById(RECEIVER).orElseThrow().getDeleteDate()).isNull();
    assertThat(consultants.findById(RECEIVER).orElseThrow().isNotificationsEnabled()).isTrue();
    assertThat(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(RECEIVER, 70L)).isTrue();
    assertThat(lifecycle.snapshot(LEGACY)).isEmpty();

    mvc.perform(
            post("/users/event-notifications/message-events")
                .contentType("application/json")
                .content(
                    "{\"roomId\":\""
                        + ROOM
                        + "\",\"teamDiscussion\":true,\"senderDisplayName\":\"Synthetic sender\"}"))
        .andExpect(status().isNoContent());
    assertThat(discussions.findById(discussionId).orElseThrow().isFirstNotified()).isTrue();
    assertThat(participants.findByTeamDiscussionId(discussionId))
        .extracting(participant -> participant.getConsultantId())
        .containsExactly(SENDER);
    assertThat(events.findAll())
        .extracting(event -> event.getRecipientUserId())
        .contains(LEGACY)
        .doesNotContain(SENDER, "team-owner");
    assertThat(events.findAll())
        .filteredOn(event -> RECEIVER.equals(event.getRecipientUserId()))
        .hasSize(accountStatus == AccountInactivityService.Status.ACTIVE ? 1 : 0);
    when(authenticatedUser.getUserId()).thenReturn(RECEIVER);
    mvc.perform(get("/users/event-notifications"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items.length()")
                .value(accountStatus == AccountInactivityService.Status.ACTIVE ? 1 : 0));
    verifyNoInteractions(feedbackMail, internalMail);
  }

  @Test
  void deletedTeamRecipientDoesNotReceiveNewActivityEvenIfAgencyRelationAndLegacyLifecycleRemain()
      throws Exception {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              var recipient = consultants.findById(RECEIVER).orElseThrow();
              recipient.setDeleteDate(LocalDateTime.now());
              consultants.saveAndFlush(recipient);
            });
    assertThat(lifecycle.snapshot(RECEIVER)).isEmpty();
    assertThat(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(RECEIVER, 70L)).isTrue();
    postTeam(false);
    assertThat(events.findAll())
        .extracting(event -> event.getRecipientUserId())
        .contains(LEGACY)
        .doesNotContain(RECEIVER, SENDER, "team-owner");
  }

  @Test
  void deletedTeamSenderCannotCreateActivityOrParticipationDespiteRetainedAgencyRelation()
      throws Exception {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              var sender = consultants.findById(SENDER).orElseThrow();
              sender.setDeleteDate(LocalDateTime.now());
              consultants.saveAndFlush(sender);
            });
    assertThat(lifecycle.snapshot(SENDER)).isEmpty();
    assertThat(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(SENDER, 70L)).isTrue();
    postTeam(false);
    assertThat(events.findAll()).isEmpty();
    assertThat(discussions.findById(discussionId).orElseThrow().isFirstNotified()).isFalse();
    assertThat(participants.findByTeamDiscussionId(discussionId)).isEmpty();
    verifyNoInteractions(feedbackMail, internalMail, sessionService, matrix);
  }

  @ParameterizedTest
  @CsvSource({
    "ALL,false,0,1",
    "MENTIONS,false,0,0",
    "MENTIONS,true,0,1",
    "MUTED,true,0,0",
    "ALL,false,60,0",
    "ALL,false,-60,1"
  })
  void persistedOwnRoomLevelControlsNewActivityAndRetainsExistingFeed(
      NotificationRoomLevel.Level level, boolean mentioned, int snoozeMinutes, int expectedNew)
      throws Exception {
    postTeam(false);
    when(authenticatedUser.getUserId()).thenReturn(RECEIVER);
    postTeam(false); // Become a real persisted participant before the later-post hybrid rule.
    var oldIds =
        events.findAll().stream()
            .filter(event -> RECEIVER.equals(event.getRecipientUserId()))
            .map(event -> event.getId())
            .toList();
    assertThat(oldIds).hasSize(1);
    String snooze =
        snoozeMinutes == 0
            ? ""
            : ",\"snoozedUntil\":\"" + LocalDateTime.now().plusMinutes(snoozeMinutes) + "\"";
    mvc.perform(
            patch("/users/event-notifications/conversation-level")
                .contentType("application/json")
                .content("{\"roomId\":\"" + ROOM + "\",\"level\":\"" + level + "\"" + snooze + "}"))
        .andExpect(status().isNoContent());
    assertThat(levels.findByUserIdAndRoomId(RECEIVER, ROOM).orElseThrow().getLevel())
        .isEqualTo(level);
    assertThat(levels.findByUserIdAndRoomId(SENDER, ROOM)).isEmpty();
    when(authenticatedUser.getUserId()).thenReturn(SENDER);
    postTeam(mentioned);
    assertThat(events.findAll())
        .filteredOn(event -> RECEIVER.equals(event.getRecipientUserId()))
        .hasSize(1 + expectedNew)
        .extracting(event -> event.getId())
        .containsAll(oldIds);
    assertThat(events.findAll())
        .filteredOn(event -> LEGACY.equals(event.getRecipientUserId()))
        .hasSize(3);
    when(authenticatedUser.getUserId()).thenReturn(RECEIVER);
    mvc.perform(get("/users/event-notifications"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1 + expectedNew));
    verifyNoInteractions(feedbackMail, internalMail, sessionService, matrix);
  }

  enum MembershipChange {
    RELATION_DELETED,
    OTHER_AGENCY,
    OTHER_TENANT
  }

  @ParameterizedTest
  @EnumSource(MembershipChange.class)
  void currentAgencyAndTenantMembershipIsRequiredEvenForAnExistingParticipant(
      MembershipChange change) throws Exception {
    postTeam(false);
    when(authenticatedUser.getUserId()).thenReturn(RECEIVER);
    postTeam(false);
    var oldReceiverIds =
        events.findAll().stream()
            .filter(event -> RECEIVER.equals(event.getRecipientUserId()))
            .map(event -> event.getId())
            .toList();
    assertThat(oldReceiverIds).hasSize(1);
    TenantContext.setCurrentTenant(0L);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              var membership =
                  agencies.findByAgencyIdAndDeleteDateIsNull(70L).stream()
                      .filter(relation -> RECEIVER.equals(relation.getConsultant().getId()))
                      .findFirst()
                      .orElseThrow();
              switch (change) {
                case RELATION_DELETED -> membership.setDeleteDate(LocalDateTime.now());
                case OTHER_AGENCY -> membership.setAgencyId(99L);
                case OTHER_TENANT -> {
                  membership.setTenantId(8L);
                  membership.getConsultant().setTenantId(8L);
                }
              }
              agencies.save(membership);
            });
    TenantContext.setCurrentTenant(7L);
    when(authenticatedUser.getUserId()).thenReturn(SENDER);
    postTeam(true); // A mention and retained participant row cannot restore revoked access.
    assertThat(events.findAll())
        .filteredOn(event -> RECEIVER.equals(event.getRecipientUserId()))
        .extracting(event -> event.getId())
        .containsExactlyInAnyOrderElementsOf(oldReceiverIds);
    assertThat(events.findAll())
        .filteredOn(event -> LEGACY.equals(event.getRecipientUserId()))
        .hasSize(3);
    assertThat(participants.findByTeamDiscussionId(discussionId))
        .extracting(participant -> participant.getConsultantId())
        .contains(RECEIVER);
    TenantContext.setCurrentTenant(8L);
    assertThat(discussions.findByMatrixRoomId(ROOM)).isEmpty();
    when(authenticatedUser.getUserId()).thenReturn(LEGACY);
    mvc.perform(get("/users/event-notifications"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    verifyNoInteractions(feedbackMail, internalMail, sessionService, matrix);
  }

  enum ClosedCase {
    ARCHIVED,
    ASSIGNED
  }

  @ParameterizedTest
  @EnumSource(ClosedCase.class)
  void acceptedOrArchivedDiscussionCannotCreateActivityOrParticipation(ClosedCase closed)
      throws Exception {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              var discussion = discussions.findById(discussionId).orElseThrow();
              if (closed == ClosedCase.ARCHIVED) {
                discussion.setStatus(TeamDiscussion.Status.ARCHIVED);
                discussions.saveAndFlush(discussion);
              } else {
                var session = sessions.findById(discussion.getSessionId()).orElseThrow();
                session.setConsultant(consultants.findById(SENDER).orElseThrow());
                sessions.save(session);
              }
            });
    postTeam(true);
    assertThat(events.findAll()).isEmpty();
    assertThat(participants.findByTeamDiscussionId(discussionId)).isEmpty();
    assertThat(discussions.findById(discussionId).orElseThrow().isFirstNotified()).isFalse();
    verifyNoInteractions(feedbackMail, internalMail, sessionService, matrix);
  }

  @Test
  void privateClientPrincipalCannotActivateTheTeamDiscussionOrObserveItsFeed() throws Exception {
    when(authenticatedUser.getUserId()).thenReturn("team-owner");
    postTeam(true);
    assertThat(events.findAll()).isEmpty();
    assertThat(participants.findByTeamDiscussionId(discussionId)).isEmpty();
    assertThat(discussions.findById(discussionId).orElseThrow().isFirstNotified()).isFalse();
    when(authenticatedUser.getUserId()).thenReturn(SENDER);
    postTeam(false);
    when(authenticatedUser.getUserId()).thenReturn("team-owner");
    mvc.perform(get("/users/event-notifications"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
    verifyNoInteractions(feedbackMail, internalMail, sessionService, matrix);
  }

  private void postTeam(boolean mentionReceiver) throws Exception {
    String mentions =
        mentionReceiver ? "\"" + LEGACY + "\",\"" + RECEIVER + "\"" : "\"" + LEGACY + "\"";
    mvc.perform(
            post("/users/event-notifications/message-events")
                .contentType("application/json")
                .content(
                    "{\"roomId\":\""
                        + ROOM
                        + "\",\"teamDiscussion\":true,\"mentionedUserIds\":["
                        + mentions
                        + "]}"))
        .andExpect(status().isNoContent());
  }
}
