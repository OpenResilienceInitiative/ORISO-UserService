package de.caritas.cob.userservice.api.service.matrixgroup;

import static org.assertj.core.api.Assertions.assertThat;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MariaDBContainer;

/**
 * Stopping a recurring group runs inside one transaction, while the next room is reserved and
 * committed in a transaction of its own. MariaDB's REPEATABLE READ hides that commit from the outer
 * transaction, which is why this needs the real database: H2 reads committed and never fails.
 */
@SpringBootTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ChatMatrixRoomRotationMariaDbIT {
  private static final long TENANT = 1L;
  private static final String NEXT_ROOM = "!synthetic-next:synthetic.oriso.test";
  private static final MariaDBContainer<?> DATABASE =
      new MariaDBContainer<>("mariadb:10.11").withDatabaseName("userservice");

  static {
    DATABASE.start();
  }

  @DynamicPropertySource
  private static void databaseProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::getJdbcUrl);
    registry.add("spring.datasource.username", DATABASE::getUsername);
    registry.add("spring.datasource.password", DATABASE::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
    registry.add("spring.liquibase.enabled", () -> "true");
    registry.add(
        "spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    registry.add("spring.liquibase.contexts", () -> "dev,seed");
    registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.MariaDBDialect");
    registry.add("spring.jpa.defer-datasource-initialization", () -> "false");
    registry.add("spring.sql.init.mode", () -> "never");
  }

  @Autowired
  private de.caritas.cob.userservice.api.workflow.deactivate.service.DeactivateGroupChatService
      staleService;

  @Autowired private de.caritas.cob.userservice.api.facade.StopChatFacade manualStop;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.service.chat.GroupChatPermissionService permissions;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService members;

  @Autowired private de.caritas.cob.userservice.api.facade.JoinAndLeaveChatFacade joinLeave;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.caritas.cob.userservice.api.service.chat.RecurringGroupStopCoordinator coordinator;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.service.chat.GroupCounsellingDpaPolicy dpaPolicy;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.helper.ChatPermissionVerifier chatPermissions;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.service.ConsultantService consultantService;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.service.chat.GroupChatRoleService roleService;

  @Autowired private jakarta.persistence.EntityManager entityManager;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository participants;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private de.caritas.cob.userservice.api.actions.chat.ChatReCreator reCreator;

  @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
  private ChatMatrixRoomHistory history;

  @Autowired
  private de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService cleanup;

  @Autowired
  private de.caritas.cob.userservice.api.port.out.GroupChatMatrixCleanupTaskRepository tasks;

  @org.springframework.test.context.bean.override.mockito.MockitoBean
  private de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService matrix;

  private static final String STALE_MEMBER = "@stale:synthetic.oriso.test";

  @Autowired private ChatRepository chats;
  @Autowired private ConsultantRepository consultants;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactions;

  @BeforeEach
  void inTenant() {
    TenantContext.setCurrentTenant(TENANT);
    // Every room but a fixture's current one is a next room. It reports one stale member, so each
    // transfer also exercises the eviction of someone who is no longer in the current room.
    org.mockito.Mockito.when(
            members.resolveHumanMembers(
                org.mockito.ArgumentMatchers.argThat(
                    room -> room != null && !room.startsWith("!stored-"))))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    STALE_MEMBER, "stale", "stale", "Stale", false, null, null)));
    org.mockito.Mockito.when(
            members.removeMemberFromRoomAndConfirm(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
  }

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  @Test
  void aRotationReservedInItsOwnTransactionCanBeRecordedInTheOuterTransaction() {
    var chat = recurringChat();

    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              // The first consistent read fixes the snapshot of the outer transaction.
              jdbc.queryForObject("select count(*) from chat_matrix_room", Long.class);
              assertThat(history.reserveNextRoom(chat, NEXT_ROOM)).isEqualTo(NEXT_ROOM);
              history.recordRotation(chat, NEXT_ROOM);
            });

    assertThat(
            jdbc.queryForObject(
                "select count(*) from chat_matrix_room where series_id = ?",
                Long.class,
                chat.getId()))
        .isEqualTo(2);
  }

  @Test
  void cleanupNeverPurgesACommittedNextRoomBeforeTheChatAdvances() {
    var chat = recurringChat();
    var nextRoom = "!cleanup-next:synthetic.oriso.test";
    var owner =
        new de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService.GroupOwner(
            chat.getId(), chat.getChatOwner().getId(), TENANT);
    var cleanupId = cleanup.recordRoom(owner, nextRoom);
    assertThat(history.reserveNextRoom(chat, nextRoom)).isEqualTo(nextRoom);

    cleanup.retry(cleanupId);

    org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
        .purgeRoomOrConfirmGone(nextRoom);
    assertThat(tasks.findById(cleanupId)).isEmpty();
  }

  @Test
  void cleanupWaitingForOwnerSeesReservationCommittedAfterItsSnapshot() throws Exception {
    var chat = recurringChat();
    var nextRoom = "!concurrent-next:synthetic.oriso.test";
    var owner =
        new de.caritas.cob.userservice.api.service.chat.GroupChatMatrixCleanupService.GroupOwner(
            chat.getId(), chat.getChatOwner().getId(), TENANT);
    var cleanupId = cleanup.recordRoom(owner, nextRoom);
    var workerStarted = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    var worker = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                cleanup.lockOwner(chat.getChatOwner());
                worker.set(
                    executor.submit(
                        () -> {
                          TenantContext.setCurrentTenant(TENANT);
                          try {
                            workerStarted.countDown();
                            cleanup.retry(cleanupId);
                          } finally {
                            TenantContext.clear();
                          }
                        }));
                try {
                  assertThat(workerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                      .isTrue();
                  var deadline =
                      System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                  long waiting = 0;
                  while (System.nanoTime() < deadline && waiting == 0) {
                    waiting =
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'",
                            Long.class);
                    if (waiting == 0) Thread.sleep(10);
                  }
                  assertThat(waiting).as("cleanup worker waits for the owner lock").isPositive();
                  assertThat(history.reserveNextRoom(chat, nextRoom)).isEqualTo(nextRoom);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(failure);
                }
              });
      worker.get().get(10, java.util.concurrent.TimeUnit.SECONDS);
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .purgeRoomOrConfirmGone(nextRoom);
      assertThat(tasks.findById(cleanupId)).isEmpty();
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void scheduledRecurringChatWithoutPreparedRoomAdvances() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    chat.getChatOwner().getMatrixUserId(),
                    chat.getChatOwner().getId(),
                    "owner",
                    "Owner",
                    true,
                    null,
                    null)));
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId("!scheduled-next:synthetic.oriso.test");
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(response));
    staleService.deactivateStaleGroupChats();
    var result = chats.findById(chat.getId()).orElseThrow();
    assertThat(result.isActive()).isFalse();
    assertThat(result.getCurrentOccurrenceIndex()).isEqualTo(1);
    assertThat(result.getMatrixRoomId()).isEqualTo("!scheduled-next:synthetic.oriso.test");
  }

  @Test
  void simultaneousSchedulerAndModeratorAdvanceAndTransferOnlyOnce() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    chat.getChatOwner().getMatrixUserId(),
                    chat.getChatOwner().getId(),
                    "owner",
                    "Owner",
                    true,
                    null,
                    null),
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    "@guest:synthetic.oriso.test", "guest", "guest", "Guest", false, null, null)));
    org.mockito.Mockito.when(
            members.addMemberToRoom(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(true);
    var creating = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId("!race-next:synthetic.oriso.test");
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              creating.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("timeout");
              return org.springframework.http.ResponseEntity.ok(response);
            });
    org.mockito.Mockito.when(matrix.purgeRoom(oldRoom)).thenReturn(true);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var scheduled =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  staleService.deactivateStaleGroupChats();
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(creating.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var manual =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  manualStop.stopChat(chat, chat.getChatOwner());
                } catch (
                    de.caritas.cob.userservice.api.exception.httpresponses.ConflictException
                        expected) {
                } finally {
                  TenantContext.clear();
                }
              });
      var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      long waiting = 0;
      while (System.nanoTime() < deadline && waiting == 0) {
        waiting =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'",
                Long.class);
        if (waiting == 0) Thread.sleep(10);
      }
      assertThat(waiting).as("moderator waits for owner lock").isPositive();
      release.countDown();
      scheduled.get(15, java.util.concurrent.TimeUnit.SECONDS);
      manual.get(15, java.util.concurrent.TimeUnit.SECONDS);
      var result = chats.findById(chat.getId()).orElseThrow();
      assertThat(result.isActive()).isFalse();
      assertThat(result.getCurrentOccurrenceIndex()).isEqualTo(1);
      assertThat(result.getMatrixRoomId()).isEqualTo("!race-next:synthetic.oriso.test");
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1))
          .createRoomAsMatrixUser(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString());
      org.mockito.Mockito.verify(members, org.mockito.Mockito.times(1))
          .addMemberToRoom(
              "!race-next:synthetic.oriso.test",
              chat.getChatOwner().getMatrixUserId(),
              "@guest:synthetic.oriso.test");
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1)).purgeRoom(oldRoom);
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void carriedMarkerCommitsWhileChatIsLockedAndSurvivesOuterRollback() {
    var chat = recurringChat();
    var nextRoom = "!marked-next:synthetic.oriso.test";
    history.reserveNextRoom(chat, nextRoom);
    assertThat(history.membersWereCarried(chat, nextRoom)).isFalse();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions)
                    .executeWithoutResult(
                        status -> {
                          var locked = chats.findByIdForUpdate(chat.getId()).orElseThrow();
                          history.markMembersCarried(locked.getId(), TENANT, 1, nextRoom);
                          throw new IllegalStateException(
                              "simulated rollback after old-room teardown");
                        }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(history.membersWereCarried(chat, nextRoom)).isTrue();
  }

  @Test
  void leavingBeforeStopDoesNotReinviteDepartedMember() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    var caller =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class);
    var leaving = new Consultant();
    leaving.setId("leaving-consultant");
    leaving.setMatrixUserId("@departing:synthetic.oriso.test");
    org.mockito.Mockito.when(consultantService.getConsultantViaAuthenticatedUser(caller))
        .thenReturn(java.util.Optional.of(leaving));
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    var left = new java.util.concurrent.atomic.AtomicBoolean(false);
    var removing = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              removing.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("timeout");
              left.set(true);
              return null;
            })
        .when(roleService)
        .leaveSeries(
            org.mockito.ArgumentMatchers.any(Chat.class), org.mockito.ArgumentMatchers.eq(leaving));
    org.mockito.Mockito.when(
            members.hasRemainingHumanMembers(
                org.mockito.ArgumentMatchers.any(Chat.class),
                org.mockito.ArgumentMatchers.eq(leaving.getMatrixUserId())))
        .thenReturn(true);
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenAnswer(
            invocation -> {
              var ownerMember =
                  new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                      .ResolvedRoomMember(
                      chat.getChatOwner().getMatrixUserId(),
                      chat.getChatOwner().getId(),
                      "owner",
                      "Owner",
                      true,
                      null,
                      null);
              var departingMember =
                  new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                      .ResolvedRoomMember(
                      leaving.getMatrixUserId(),
                      leaving.getId(),
                      "guest",
                      "Guest",
                      true,
                      null,
                      null);
              return left.get()
                  ? java.util.List.of(ownerMember)
                  : java.util.List.of(ownerMember, departingMember);
            });
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId("!leave-next:synthetic.oriso.test");
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(response));
    org.mockito.Mockito.when(matrix.purgeRoom(oldRoom)).thenReturn(true);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var leave =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  joinLeave.leaveChat(chat.getId(), caller);
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(removing.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var scheduled =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  staleService.deactivateStaleGroupChats();
                } finally {
                  TenantContext.clear();
                }
              });
      var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      long waiting = 0;
      while (System.nanoTime() < deadline && waiting == 0) {
        waiting =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'",
                Long.class);
        if (waiting == 0) Thread.sleep(10);
      }
      assertThat(waiting)
          .as(
              "scheduler waits for leaving member; processlist=%s; scheduledDone=%s",
              jdbc.queryForList(
                  "SELECT COALESCE(INFO, '') FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID()",
                  String.class),
              scheduled.isDone())
          .isPositive();
      release.countDown();
      leave.get(15, java.util.concurrent.TimeUnit.SECONDS);
      scheduled.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(chats.findById(chat.getId()).orElseThrow().getCurrentOccurrenceIndex())
          .isEqualTo(1);
      org.mockito.Mockito.verify(members, org.mockito.Mockito.never())
          .addMemberToRoom(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.eq(leaving.getMatrixUserId()));
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1)).purgeRoom(oldRoom);
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void stoppingBeforeLeaveNeverInvitesAfterTheMemberHasDeparted() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    var caller =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class);
    var leaving = new Consultant();
    leaving.setId("leaving-second");
    leaving.setMatrixUserId("@leaving-second:synthetic.oriso.test");
    org.mockito.Mockito.when(consultantService.getConsultantViaAuthenticatedUser(caller))
        .thenReturn(java.util.Optional.of(leaving));
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    var left = new java.util.concurrent.atomic.AtomicBoolean(false);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              left.set(true);
              return null;
            })
        .when(roleService)
        .leaveSeries(
            org.mockito.ArgumentMatchers.any(Chat.class), org.mockito.ArgumentMatchers.eq(leaving));
    org.mockito.Mockito.when(
            members.hasRemainingHumanMembers(
                org.mockito.ArgumentMatchers.any(Chat.class),
                org.mockito.ArgumentMatchers.eq(leaving.getMatrixUserId())))
        .thenReturn(true);
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenAnswer(
            invocation -> {
              var ownerMember =
                  new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                      .ResolvedRoomMember(
                      chat.getChatOwner().getMatrixUserId(),
                      chat.getChatOwner().getId(),
                      "owner",
                      "Owner",
                      true,
                      null,
                      null);
              var departing =
                  new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                      .ResolvedRoomMember(
                      leaving.getMatrixUserId(),
                      leaving.getId(),
                      "guest",
                      "Guest",
                      true,
                      null,
                      null);
              return left.get()
                  ? java.util.List.of(ownerMember)
                  : java.util.List.of(ownerMember, departing);
            });
    var joinedAfterLeaving = new java.util.concurrent.atomic.AtomicBoolean(false);
    org.mockito.Mockito.when(
            members.addMemberToRoom(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              if (left.get()) joinedAfterLeaving.set(true);
              return true;
            });
    var creating = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId("!stop-before-leave:synthetic.oriso.test");
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(
            invocation -> {
              creating.countDown();
              if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("timeout");
              return org.springframework.http.ResponseEntity.ok(response);
            });
    org.mockito.Mockito.when(matrix.purgeRoom(oldRoom)).thenReturn(true);
    var workers = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var scheduled =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  staleService.deactivateStaleGroupChats();
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(creating.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var leave =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  joinLeave.leaveChat(chat.getId(), caller);
                } catch (
                    de.caritas.cob.userservice.api.exception.httpresponses.ConflictException
                        expected) {
                } finally {
                  TenantContext.clear();
                }
              });
      var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
      long waiting = 0;
      while (System.nanoTime() < deadline && waiting == 0) {
        waiting =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'",
                Long.class);
        if (waiting == 0) Thread.sleep(10);
      }
      assertThat(waiting).as("leaving member waits for scheduler owner lock").isPositive();
      release.countDown();
      scheduled.get(15, java.util.concurrent.TimeUnit.SECONDS);
      leave.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(chats.findById(chat.getId()).orElseThrow().getCurrentOccurrenceIndex())
          .isEqualTo(1);
      assertThat(joinedAfterLeaving).isFalse();
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.times(1)).purgeRoom(oldRoom);
    } finally {
      release.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void candidateScanDoesNotWaitForAnotherChatsWriteLock() throws Exception {
    var chat = recurringChat();
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                chats.findByIdForUpdate(chat.getId()).orElseThrow();
                var read =
                    workers.submit(
                        () -> {
                          TenantContext.setCurrentTenant(TENANT);
                          try {
                            return new TransactionTemplate(transactions)
                                .execute(
                                    inner ->
                                        chats.findActiveDeactivationCandidates().stream()
                                            .map(Chat::getId)
                                            .toList());
                          } finally {
                            TenantContext.clear();
                          }
                        });
                try {
                  assertThat(read.get(3, java.util.concurrent.TimeUnit.SECONDS))
                      .contains(chat.getId());
                } catch (Exception failure) {
                  throw new IllegalStateException("candidate scan blocked by row lock", failure);
                }
              });
    } finally {
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void leaveAndOwnershipTransferTakeOwnerAndChatBeforeParticipants() throws Exception {
    var chat = recurringChat();
    var successor = owner();
    var ownerParticipant =
        de.caritas.cob.userservice.api.model.GroupChatParticipant.builder()
            .chatId(chat.getId())
            .seriesId(chat.getId())
            .consultantId(chat.getChatOwner().getId())
            .role(de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole.OWNER)
            .build();
    var successorParticipant =
        de.caritas.cob.userservice.api.model.GroupChatParticipant.builder()
            .chatId(chat.getId())
            .seriesId(chat.getId())
            .consultantId(successor.getId())
            .role(
                de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole
                    .CO_MODERATOR)
            .build();
    participants.save(ownerParticipant);
    participants.save(successorParticipant);
    var service =
        new de.caritas.cob.userservice.api.service.chat.GroupChatRoleService(
            participants,
            chats,
            consultants,
            members,
            org.mockito.Mockito.mock(
                de.caritas.cob.userservice.api.service.chat.MembersOnlyRevocation.class),
            cleanup,
            entityManager);
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    var transfer =
        new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
    try {
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                cleanup.lockOwner(chat.getChatOwner());
                var locked = chats.findByIdForUpdate(chat.getId()).orElseThrow();
                transfer.set(
                    worker.submit(
                        () -> {
                          TenantContext.setCurrentTenant(TENANT);
                          try {
                            new TransactionTemplate(transactions)
                                .executeWithoutResult(
                                    inner ->
                                        service.transferPrimaryOwnership(
                                            chat.getId(),
                                            chat.getChatOwner().getId(),
                                            successor.getId()));
                          } finally {
                            TenantContext.clear();
                          }
                        }));
                try {
                  var deadline =
                      System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                  long waiting = 0;
                  while (System.nanoTime() < deadline && waiting == 0) {
                    waiting =
                        jdbc.queryForObject(
                            "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE 'select % from consultant % for update'",
                            Long.class);
                    if (waiting == 0) Thread.sleep(10);
                  }
                  assertThat(waiting)
                      .as("ownership transfer waits for owner before locking participants")
                      .isPositive();
                  service.leaveSeries(locked, successor);
                } catch (InterruptedException failure) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(failure);
                }
              });
      var refusal =
          org.junit.jupiter.api.Assertions.assertThrows(
              java.util.concurrent.ExecutionException.class,
              () -> transfer.get().get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertThat(refusal.getCause())
          .isInstanceOf(
              de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException.class);
      assertThat(participants.findBySeriesIdAndConsultantId(chat.getId(), successor.getId()))
          .isEmpty();
    } finally {
      worker.shutdownNow();
      assertThat(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void rescheduleAfterPreparationStopsTheStaleFinalization() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    chat.getChatOwner().getMatrixUserId(),
                    chat.getChatOwner().getId(),
                    "owner",
                    "Owner",
                    true,
                    null,
                    null)));
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId("!reschedule-next:synthetic.oriso.test");
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(response));
    var prepared = new java.util.concurrent.CountDownLatch(1);
    var continueStop = new java.util.concurrent.CountDownLatch(1);
    de.caritas.cob.userservice.api.actions.chat.ChatReCreator reCreatorTarget =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(reCreator);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              org.springframework.transaction.support.TransactionSynchronizationManager
                  .registerSynchronization(
                      new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                          prepared.countDown();
                          try {
                            if (!continueStop.await(10, java.util.concurrent.TimeUnit.SECONDS))
                              throw new IllegalStateException("timeout");
                          } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                          }
                        }
                      });
              return result;
            })
        .when(reCreatorTarget)
        .prepareLockedNextRoom(org.mockito.ArgumentMatchers.any(Chat.class));
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var stop =
          workers.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  staleService.deactivateStaleGroupChats();
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(prepared.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var newStart =
          LocalDateTime.now().plusDays(7).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
      new TransactionTemplate(transactions)
          .executeWithoutResult(
              status -> {
                var locked = chats.findByIdForUpdate(chat.getId()).orElseThrow();
                locked.setStartDate(newStart);
                locked.setInitialStartDate(newStart);
                chats.save(locked);
              });
      continueStop.countDown();
      stop.get(15, java.util.concurrent.TimeUnit.SECONDS);
      var current = chats.findById(chat.getId()).orElseThrow();
      assertThat(current.isActive()).isTrue();
      assertThat(current.getStartDate()).isEqualTo(newStart);
      assertThat(current.getMatrixRoomId()).isEqualTo(oldRoom);
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never()).purgeRoom(oldRoom);
    } finally {
      continueStop.countDown();
      workers.shutdownNow();
      assertThat(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void failedPreparationLeavesJournalForRetryUntilCandidatePurgeSucceeds() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    chat.setInitialStartDate(LocalDateTime.now().minusHours(4));
    chat.setStartDate(LocalDateTime.now().minusHours(4));
    chat.setUpdateDate(LocalDateTime.now().minusHours(4));
    chats.save(chat);
    var nextRoom = "!failed-prepare:synthetic.oriso.test";
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    org.mockito.Mockito.when(members.resolveHumanMembers(oldRoom))
        .thenReturn(
            java.util.List.of(
                new de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService
                    .ResolvedRoomMember(
                    chat.getChatOwner().getMatrixUserId(),
                    chat.getChatOwner().getId(),
                    "owner",
                    "Owner",
                    true,
                    null,
                    null)));
    var response =
        new de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO();
    response.setRoomId(nextRoom);
    org.mockito.Mockito.when(
            matrix.createRoomAsMatrixUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(org.springframework.http.ResponseEntity.ok(response));
    ChatMatrixRoomHistory target =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(history);
    org.mockito.Mockito.doThrow(new IllegalStateException("simulated provenance failure"))
        .when(target)
        .recordRotation(
            org.mockito.ArgumentMatchers.any(Chat.class),
            org.mockito.ArgumentMatchers.eq(nextRoom));
    staleService.deactivateStaleGroupChats();
    var pending =
        java.util.stream.StreamSupport.stream(tasks.findAll().spliterator(), false)
            .filter(task -> nextRoom.equals(task.getRoomId()))
            .findFirst()
            .orElseThrow();
    assertThat(chats.findById(chat.getId()).orElseThrow().isActive()).isTrue();
    org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never()).purgeRoom(oldRoom);
    org.mockito.Mockito.when(matrix.purgeRoomOrConfirmGone(nextRoom))
        .thenReturn(
            de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome
                .FAILED,
            de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService.RoomPurgeOutcome
                .PURGED);
    cleanup.retry(pending.getId());
    assertThat(tasks.findById(pending.getId())).isPresent();
    cleanup.retry(pending.getId());
    assertThat(tasks.findById(pending.getId())).isEmpty();
    chats.deleteById(chat.getId());
  }

  @Test
  void joiningAfterLastLeaveDecisionPreventsStopAndPurge() throws Exception {
    var chat = recurringChat();
    var oldRoom = chat.getMatrixRoomId();
    var leaver = new Consultant();
    leaver.setId("last-leaver");
    leaver.setMatrixUserId("@last-leaver:synthetic.oriso.test");
    var joiner = new Consultant();
    joiner.setId("new-joiner");
    joiner.setMatrixUserId("@new-joiner:synthetic.oriso.test");
    var leaveCaller =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class);
    var joinCaller =
        org.mockito.Mockito.mock(de.caritas.cob.userservice.api.helper.AuthenticatedUser.class);
    org.mockito.Mockito.when(consultantService.getConsultantViaAuthenticatedUser(leaveCaller))
        .thenReturn(java.util.Optional.of(leaver));
    org.mockito.Mockito.when(consultantService.getConsultantViaAuthenticatedUser(joinCaller))
        .thenReturn(java.util.Optional.of(joiner));
    org.mockito.Mockito.when(
            members.resolveMatrixRoomId(org.mockito.ArgumentMatchers.any(Chat.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Chat.class).getMatrixRoomId());
    var joined = new java.util.concurrent.atomic.AtomicBoolean();
    org.mockito.Mockito.when(
            members.hasRemainingHumanMembers(
                org.mockito.ArgumentMatchers.any(Chat.class),
                org.mockito.ArgumentMatchers.eq(leaver.getMatrixUserId())))
        .thenAnswer(invocation -> joined.get());
    org.mockito.Mockito.when(matrix.getRoomMembers(oldRoom))
        .thenReturn(java.util.Optional.of(java.util.List.of()));
    org.mockito.Mockito.when(
            members.addMemberToRoom(
                org.mockito.ArgumentMatchers.any(Chat.class),
                org.mockito.ArgumentMatchers.eq(joiner.getMatrixUserId())))
        .thenAnswer(
            invocation -> {
              joined.set(true);
              return true;
            });
    var leaveCommitted = new java.util.concurrent.CountDownLatch(1);
    var resumeLeave = new java.util.concurrent.CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              var result = invocation.callRealMethod();
              if (result != null) {
                leaveCommitted.countDown();
                if (!resumeLeave.await(10, java.util.concurrent.TimeUnit.SECONDS))
                  throw new IllegalStateException("timeout");
              }
              return result;
            })
        .when(coordinatorTarget())
        .withLockedOccurrence(
            org.mockito.ArgumentMatchers.any(Chat.class), org.mockito.ArgumentMatchers.any());
    var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      var leave =
          worker.submit(
              () -> {
                TenantContext.setCurrentTenant(TENANT);
                try {
                  joinLeave.leaveChat(chat.getId(), leaveCaller);
                } finally {
                  TenantContext.clear();
                }
              });
      assertThat(leaveCommitted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      joinLeave.joinChat(chat.getId(), joinCaller);
      resumeLeave.countDown();
      leave.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(joined).isTrue();
      assertThat(chats.findById(chat.getId()).orElseThrow().isActive()).isTrue();
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never())
          .createRoomAsMatrixUser(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyString());
      org.mockito.Mockito.verify(matrix, org.mockito.Mockito.never()).purgeRoom(oldRoom);
    } finally {
      resumeLeave.countDown();
      worker.shutdownNow();
      assertThat(worker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  private de.caritas.cob.userservice.api.service.chat.RecurringGroupStopCoordinator
      coordinatorTarget() {
    return org.springframework.test.util.AopTestUtils.getUltimateTargetObject(coordinator);
  }

  private Chat recurringChat() {
    var start = LocalDateTime.of(2999, 1, 1, 12, 0);
    return chats.save(
        Chat.builder()
            .topic("Synthetic recurring group")
            .createDate(LocalDateTime.now())
            .updateDate(LocalDateTime.now())
            .consultingTypeId(1)
            .chatOwner(owner())
            .initialStartDate(start)
            .startDate(start)
            .duration(60)
            .repetitive(true)
            .repeatCount(3)
            .chatInterval(Chat.ChatInterval.WEEKLY)
            .conversationType(ConversationType.SELF_HELP)
            .matrixRoomId("!stored-" + UUID.randomUUID() + ":synthetic.oriso.test")
            .active(true)
            .build());
  }

  private Consultant owner() {
    var id = UUID.randomUUID().toString();
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setTenantId(TENANT);
    consultant.setCreateDate(LocalDateTime.now());
    consultant.setUpdateDate(LocalDateTime.now());
    consultant.setUsername("synthetic-" + id.substring(0, 8));
    consultant.setFirstName("Synthetic");
    consultant.setLastName("C" + id.substring(0, 8));
    consultant.setEmail(id.substring(0, 8) + "@synthetic.oriso.test");
    consultant.setAppointments(null);
    consultant.setConsultantAgencies(new HashSet<>());
    consultant.setConsultantMobileTokens(new HashSet<>());
    consultant.setEncourage2fa(true);
    consultant.setNotifyEnquiriesRepeating(true);
    consultant.setNotifyNewChatMessageFromAdviceSeeker(true);
    consultant.setWalkThroughEnabled(true);
    consultant.setTeamConsultant(false);
    consultant.setMagicLinkLoginEnabled(false);
    consultant.setLanguageCode(LanguageCode.de);
    consultant.setMatrixUserId("@synthetic-" + id.substring(0, 8) + ":synthetic.oriso.test");
    return consultants.save(consultant);
  }
}
