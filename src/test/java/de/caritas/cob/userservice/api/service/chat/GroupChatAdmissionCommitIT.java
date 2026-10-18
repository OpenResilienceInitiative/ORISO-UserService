package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.CustomLocalDateTime;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.RecipientRole;
import de.caritas.cob.userservice.api.model.GroupChatAdmissionMatrixRepairTask;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest;
import de.caritas.cob.userservice.api.model.GroupChatJoinRequest.Status;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.GroupChatParticipant.ParticipantRole;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentOccurrenceStateRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatAdmissionMatrixRepairTaskRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatJoinRequestRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.service.ChatService;
import de.caritas.cob.userservice.api.service.dpa.TenantDpaGateReadClient;
import de.caritas.cob.userservice.api.service.matrix.GroupChatMembershipService;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentMailQueue;
import de.caritas.cob.userservice.api.service.notification.GroupAppointmentSeriesEventProducer;
import de.caritas.cob.userservice.tenantservice.generated.web.model.DpaGateStatusDTO;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("testing")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class GroupChatAdmissionCommitIT {

  @Autowired private GroupChatJoinRequestService service;
  @Autowired private GroupChatAdmissionProcessor processor;
  @Autowired private GroupChatJoinRequestRepository requests;
  @Autowired private GroupChatAdmissionMatrixRepairTaskRepository repairTasks;
  @Autowired private GroupChatAdmissionMatrixRepairService repairService;
  @MockitoSpyBean private GroupChatParticipantRepository participants;
  @Autowired private ChatRepository chats;
  @Autowired private ConsultantRepository consultants;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private ChatService chatService;
  @Autowired private GroupAppointmentMailQueue appointmentQueue;
  @Autowired private ChatOccurrenceExceptionRepository occurrenceExceptions;
  @Autowired private GroupAppointmentOccurrenceStateRepository occurrenceStates;
  @Autowired private GroupAppointmentMailOutboxRepository appointmentOutbox;

  @MockitoBean private GroupChatPermissionService permissions;
  @MockitoBean private GroupChatMembershipService membership;
  @MockitoBean private GroupAppointmentSeriesEventProducer appointmentEvents;
  @MockitoBean private TenantDpaGateReadClient dpaOwner;

  private Chat series;
  private Consultant owner;
  private Long oldOwnerTenantId;
  private Consultant requester;
  private String oldMatrixUserId;

  @BeforeEach
  void setUp() {
    // External owner boundary only; admission and the shared policy remain real.
    when(dpaOwner.read(any()))
        .thenReturn(new DpaGateStatusDTO().dpaPublished(true).dpaSigned(true));
    when(membership.isMemberInRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(Optional.of(false));
    when(membership.resolveMatrixRoomId(any(Chat.class))).thenReturn("!admission-test:matrix.test");
    var users =
        StreamSupport.stream(consultants.findAll().spliterator(), false)
            .filter(user -> user.getDeleteDate() == null)
            .limit(2)
            .toList();
    assertThat(users).hasSize(2);
    owner = users.get(0);
    oldOwnerTenantId = owner.getTenantId();
    owner.setTenantId(41L);
    consultants.save(owner);
    requester = users.get(1);
    oldMatrixUserId = requester.getMatrixUserId();
    requester.setMatrixUserId("@admission-test:matrix.test");
    consultants.save(requester);

    var now = LocalDateTime.now();
    series =
        chats.save(
            Chat.builder()
                .topic("Commit boundary group")
                .consultingTypeId(1)
                .initialStartDate(now)
                .startDate(now)
                .duration(60)
                .conversationType(ConversationType.SELF_HELP)
                .matrixRoomId("!admission-test:matrix.test")
                .chatOwner(owner)
                .createDate(now)
                .updateDate(now)
                .build());
    participants.save(
        GroupChatParticipant.builder()
            .chatId(991L)
            .seriesId(series.getId())
            .consultantId(owner.getId())
            .role(ParticipantRole.OWNER)
            .build());
  }

  @AfterEach
  void cleanUp() {
    repairTasks.deleteAll();
    if (series != null) {
      StreamSupport.stream(appointmentOutbox.findAll().spliterator(), false)
          .filter(row -> series.getId().equals(row.getSeriesId()))
          .forEach(appointmentOutbox::delete);
      occurrenceStates.findBySeriesId(series.getId()).forEach(occurrenceStates::delete);
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.PENDING)
          .forEach(requests::delete);
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.ADMITTING)
          .forEach(requests::delete);
      requests
          .findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
              java.util.Set.of(series.getId()), Status.ADMITTED)
          .forEach(requests::delete);
      participants.findBySeriesId(series.getId()).forEach(participants::delete);
      chats.delete(series);
    }
    if (requester != null) {
      requester.setMatrixUserId(oldMatrixUserId);
      consultants.save(requester);
    }
    if (owner != null) {
      owner.setTenantId(oldOwnerTenantId);
      consultants.save(owner);
    }
  }

  @Test
  void twoSimultaneousKnocksReturnTheSameRequest() throws Exception {
    var locked = new CountDownLatch(1);
    var releaseLock = new CountDownLatch(1);
    var callersReady = new CountDownLatch(2);
    var startKnocks = new CountDownLatch(1);
    var completed = new CountDownLatch(2);

    try (var executor = Executors.newFixedThreadPool(3)) {
      var lockHolder =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .executeWithoutResult(
                          ignored -> {
                            chats.findByIdForUpdate(series.getId()).orElseThrow();
                            locked.countDown();
                            await(releaseLock);
                          }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

      var first = executor.submit(() -> knockAfterSignal(callersReady, startKnocks, completed));
      var second = executor.submit(() -> knockAfterSignal(callersReady, startKnocks, completed));
      assertThat(callersReady.await(5, TimeUnit.SECONDS)).isTrue();
      startKnocks.countDown();
      try {
        assertThat(completed.await(250, TimeUnit.MILLISECONDS)).isFalse();
      } finally {
        releaseLock.countDown();
      }

      lockHolder.get(5, TimeUnit.SECONDS);
      var firstResult = first.get(5, TimeUnit.SECONDS);
      var secondResult = second.get(5, TimeUnit.SECONDS);
      assertThat(firstResult.request().getId()).isEqualTo(secondResult.request().getId());
      assertThat(firstResult.created()).isNotEqualTo(secondResult.created());
      assertThat(
              requests.findBySeriesIdInAndStatusOrderByRequestedAtAscIdAsc(
                  java.util.Set.of(series.getId()), Status.PENDING))
          .hasSize(1);
    }
  }

  @Test
  void admissionAndScheduleEditBothCommitWithoutOpposingRowLocks() throws Exception {
    var futureStart = LocalDateTime.now().plusDays(2).withNano(0);
    series.setStartDate(futureStart);
    series.setInitialStartDate(futureStart);
    series.setRepeatCount(1);
    series.setTimezone("UTC");
    series = chats.save(series);
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.ADMITTING)
                .admittedRole(ParticipantRole.PARTICIPANT)
                .requestedAt(LocalDateTime.now())
                .admissionRequestedAt(LocalDateTime.now())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    var realEvents =
        new GroupAppointmentSeriesEventProducer(appointmentQueue, occurrenceExceptions);
    var editHoldsSeries = new CountDownLatch(1);
    var admissionStarted = new CountDownLatch(1);
    var admissionHoldsParticipants = new CountDownLatch(1);

    // Run the actual edit's baseline queue, then let admission enter its own transaction.
    // Before the fix admission takes participant rows and then waits on this edit's series row.
    doAnswer(
            invocation -> {
              int count = realEvents.seedBeforeEdit(invocation.getArgument(0));
              editHoldsSeries.countDown();
              await(admissionStarted);
              admissionHoldsParticipants.await(1, TimeUnit.SECONDS);
              return count;
            })
        .when(appointmentEvents)
        .seedBeforeEdit(any(Chat.class));
    when(membership.isMemberInRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenAnswer(
            invocation -> {
              admissionHoldsParticipants.countDown();
              return Optional.of(false);
            });
    doAnswer(
            invocation -> {
              realEvents.recordMemberJoined(
                  invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
              return null;
            })
        .when(appointmentEvents)
        .recordMemberJoined(any(), any(), any());

    var owner =
        new AuthenticatedUser(
            series.getChatOwner().getId(),
            "owner",
            java.util.Set.of(),
            "unused-local-test",
            java.util.Set.of());
    var editedSchedule =
        ChatDTO.builder()
            .topic("Updated group schedule")
            .startDate(futureStart.toLocalDate())
            .startTime(futureStart.toLocalTime())
            .duration(60)
            .repeatCount(1)
            .consultantIds(java.util.List.of(owner.getUserId()))
            .build();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var edit =
          executor.submit(() -> chatService.updateChat(series.getId(), editedSchedule, owner));
      assertThat(editHoldsSeries.await(5, TimeUnit.SECONDS)).isTrue();
      var admission =
          executor.submit(
              () -> {
                admissionStarted.countDown();
                processor.process(request.getId());
              });
      edit.get(10, TimeUnit.SECONDS);
      admission.get(10, TimeUnit.SECONDS);
    }
    assertThat(chats.findById(series.getId()).orElseThrow().getTopic())
        .isEqualTo("Updated group schedule");
    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTED);
    assertThat(
            participants.findBySeriesId(series.getId()).stream()
                .filter(member -> requester.getId().equals(member.getConsultantId())))
        .hasSize(1);
    assertThat(
            StreamSupport.stream(appointmentOutbox.findAll().spliterator(), false)
                .filter(
                    row ->
                        series.getId().equals(row.getSeriesId())
                            && requester.getId().equals(row.getRecipientId())
                            && row.getEventType()
                                == de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox
                                    .EventType.CONFIRMED))
        .hasSize(1);
  }

  private GroupChatJoinRequestService.KnockResult knockAfterSignal(
      CountDownLatch callersReady, CountDownLatch startKnocks, CountDownLatch completed) {
    callersReady.countDown();
    await(startKnocks);
    try {
      return service.knock(series.getId(), series.getInviteToken(), requester.getId());
    } finally {
      completed.countDown();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void queuedGraceAdmissionRefusesExpiredOwnerAndResumesAfterCurrentConfirmation() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    var grace =
        new DpaGateStatusDTO()
            .dpaPublished(true)
            .dpaSigned(false)
            .dpaStatus("OUTDATED")
            .currentDpaVersion("v2")
            .signingDeadlineAt("2999-01-01T00:00:00Z")
            .renewalGraceActive(true)
            .newCounsellingAllowed(true);
    var expired =
        new DpaGateStatusDTO()
            .dpaPublished(true)
            .dpaSigned(false)
            .dpaStatus("OUTDATED")
            .currentDpaVersion("v2")
            .signingDeadlineAt("2026-01-01T00:00:00Z")
            .renewalGraceActive(false)
            .newCounsellingAllowed(false);
    when(dpaOwner.read(any())).thenReturn(grace, expired);
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var queued = requests.findById(request.getId()).orElseThrow();
    assertThat(queued.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(queued.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
    verify(membership, never()).addMemberToRoom(any(Chat.class), any());
    assertThat(repairTasks.findByRequestId(request.getId())).isEmpty();

    when(dpaOwner.read(any()))
        .thenReturn(new DpaGateStatusDTO().dpaPublished(true).dpaSigned(true));
    processor.process(request.getId());

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTED);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isPresent();
    verify(membership).addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test"));
  }

  @Test
  void failedImmediateJoinRemainsDurableAndLaterRetryGrantsAccess() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(false, true);

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var waiting = requests.findById(request.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(waiting.getAdmissionRequestedAt()).isNotNull();
    assertThat(repairTasks.findByRequestId(request.getId())).isPresent();
    assertThat(waiting.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
    verify(appointmentEvents, never()).recordMemberJoined(any(), any(), any());
    assertThat(processor.pendingIds()).doesNotContain(request.getId());

    waiting.setAdmissionLastAttemptAt(CustomLocalDateTime.nowInUtc().minusMinutes(2));
    requests.save(waiting);
    assertThat(processor.pendingIds()).contains(request.getId());

    processor.process(request.getId());

    assertThat(repairTasks.findByRequestId(request.getId())).isEmpty();

    var admitted = requests.findById(request.getId()).orElseThrow();
    assertThat(admitted.getStatus()).isEqualTo(Status.ADMITTED);
    assertThat(admitted.getDecidedAt()).isNotNull();
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .map(GroupChatParticipant::getRole)
        .contains(ParticipantRole.PARTICIPANT);
    verify(appointmentEvents)
        .recordMemberJoined(any(Chat.class), eq(RecipientRole.COUNSELOR), eq(requester.getId()));
    verify(membership, org.mockito.Mockito.times(2))
        .addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test"));
  }

  @Test
  void failedAppointmentQueueRollsBackAdmissionAndRetriesAfterMatrixJoined() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(LocalDateTime.now())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    doThrow(new IllegalStateException("mail queue unavailable"))
        .when(appointmentEvents)
        .recordMemberJoined(any(), any(), any());

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTING);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();

    reset(appointmentEvents);
    processor.process(request.getId());

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTED);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isPresent();
    verify(appointmentEvents)
        .recordMemberJoined(any(Chat.class), eq(RecipientRole.COUNSELOR), eq(requester.getId()));
  }

  @Test
  void matrixSuccessFollowedByParticipantWriteFailureRemainsRetryable() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    doThrow(new DataIntegrityViolationException("simulated participant write failure"))
        .when(participants)
        .save(any(GroupChatParticipant.class));

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var waiting = requests.findById(request.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(waiting.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
    verify(membership).addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test"));
    verify(appointmentEvents, never()).recordMemberJoined(any(), any(), any());
    verify(membership)
        .removeMemberFromRoom("!admission-test:matrix.test", "@admission-test:matrix.test");
    assertThat(repairTasks.findByRequestId(request.getId())).isPresent();

    reset(participants);
    waiting.setAdmissionLastAttemptAt(CustomLocalDateTime.nowInUtc().minusMinutes(2));
    requests.save(waiting);
    assertThat(processor.pendingIds()).contains(request.getId());
    processor.process(request.getId());

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTED);
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isPresent();
    assertThat(repairTasks.findByRequestId(request.getId())).isEmpty();
    verify(membership, org.mockito.Mockito.times(2))
        .addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test"));
    verify(appointmentEvents)
        .recordMemberJoined(any(Chat.class), eq(RecipientRole.COUNSELOR), eq(requester.getId()));
  }

  @Test
  void failedMatrixRemovalRemainsDurableUntilRoomIsConfirmedClear() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    doThrow(new DataIntegrityViolationException("simulated participant write failure"))
        .when(participants)
        .save(any(GroupChatParticipant.class));

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var task = repairTasks.findByRequestId(request.getId()).orElseThrow();
    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTING);
    when(membership.isMemberInRoom("!admission-test:matrix.test", "@admission-test:matrix.test"))
        .thenReturn(java.util.Optional.of(true), java.util.Optional.of(true));
    repairService.reconcile(task.getId());
    assertThat(repairTasks.findById(task.getId())).isPresent();
    assertThat(repairTasks.findById(task.getId()).orElseThrow().getAttemptCount()).isEqualTo(1);

    when(membership.isMemberInRoom("!admission-test:matrix.test", "@admission-test:matrix.test"))
        .thenReturn(java.util.Optional.of(false));
    repairService.reconcile(task.getId());
    assertThat(repairTasks.findById(task.getId())).isEmpty();
    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTING);
  }

  @Test
  void staleRepairNeverRemovesMemberAfterAdmissionCommits() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.ADMITTED)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    var task =
        repairTasks.save(
            GroupChatAdmissionMatrixRepairTask.builder()
                .requestId(request.getId())
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .roomId("!admission-test:matrix.test")
                .memberId("@admission-test:matrix.test")
                .createdAt(CustomLocalDateTime.nowInUtc())
                .build());

    repairService.reconcile(task.getId());

    assertThat(repairTasks.findById(task.getId())).isEmpty();
    verify(membership, never()).removeMemberFromRoom(any(), any());
  }

  @Test
  void repairClearsTheMatrixJoinOfAnAdmissionHandedBackToModerators() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    var task =
        repairTasks.save(
            GroupChatAdmissionMatrixRepairTask.builder()
                .requestId(request.getId())
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .roomId("!admission-test:matrix.test")
                .memberId("@admission-test:matrix.test")
                .createdAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.isMemberInRoom("!admission-test:matrix.test", "@admission-test:matrix.test"))
        .thenReturn(Optional.of(true), Optional.of(false));

    repairService.reconcile(task.getId());

    verify(membership)
        .removeMemberFromRoom("!admission-test:matrix.test", "@admission-test:matrix.test");
    assertThat(repairTasks.findById(task.getId())).isEmpty();
  }

  @Test
  void rollbackDoesNotRemoveAnExistingMatrixMember() {
    when(membership.isMemberInRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(Optional.of(true));
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenReturn(true);
    doThrow(new DataIntegrityViolationException("simulated participant write failure"))
        .when(participants)
        .save(any(GroupChatParticipant.class));

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    assertThat(requests.findById(request.getId()).orElseThrow().getStatus())
        .isEqualTo(Status.ADMITTING);
    verify(membership, never()).removeMemberFromRoom(any(), any());
    assertThat(repairTasks.findByRequestId(request.getId())).isEmpty();
  }

  @Test
  void freshAdmissionIsNotStarvedByTwentyRepeatedFailures() {
    var oldAttempt = CustomLocalDateTime.nowInUtc().minusMinutes(2);
    for (int index = 0; index < 20; index++) {
      requests.save(
          GroupChatJoinRequest.builder()
              .seriesId(series.getId())
              .consultantId(requester.getId())
              .status(Status.ADMITTING)
              .admittedRole(ParticipantRole.PARTICIPANT)
              .admissionRequestedAt(oldAttempt.minusDays(1))
              .admissionLastAttemptAt(oldAttempt)
              .admissionAttemptCount(1)
              .requestedAt(oldAttempt.minusDays(1))
              .build());
    }
    var fresh =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.ADMITTING)
                .admittedRole(ParticipantRole.PARTICIPANT)
                .admissionRequestedAt(CustomLocalDateTime.nowInUtc())
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());

    assertThat(processor.pendingIds()).hasSize(20).contains(fresh.getId());
  }

  @Test
  void unexpectedWorkerFailureRecordsRetryAfterOriginalDecisionCommitted() {
    var request =
        requests.save(
            GroupChatJoinRequest.builder()
                .seriesId(series.getId())
                .consultantId(requester.getId())
                .status(Status.PENDING)
                .requestedAt(CustomLocalDateTime.nowInUtc())
                .build());
    when(membership.addMemberToRoom(any(Chat.class), eq("@admission-test:matrix.test")))
        .thenThrow(new IllegalStateException("remote call failed"));

    service.admit(series.getId(), request.getId(), series.getChatOwner().getId(), null);

    var waiting = requests.findById(request.getId()).orElseThrow();
    assertThat(waiting.getStatus()).isEqualTo(Status.ADMITTING);
    assertThat(waiting.getAdmissionAttemptCount()).isEqualTo(1);
    assertThat(waiting.getAdmissionLastAttemptAt()).isNotNull();
    assertThat(participants.findBySeriesIdAndConsultantId(series.getId(), requester.getId()))
        .isEmpty();
  }
}
