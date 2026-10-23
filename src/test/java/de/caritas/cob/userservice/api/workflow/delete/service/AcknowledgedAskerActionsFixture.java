package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.conversation.service.user.anonymous.AnonymousUsernameRegistry;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service.TeamDiscussionPurgeWriter;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.function.Consumer;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual nine action implementations; child repositories and remote services are boundary doubles. */
final class AcknowledgedAskerActionsFixture implements AutoCloseable {
  final List<String> events = new ArrayList<>();
  final DeleteUserAccountService service;
  final IdentityAccountRemover forbiddenNative = mock(IdentityAccountRemover.class);
  final WorkflowErrorMailService errorMail = mock(WorkflowErrorMailService.class);
  Consumer<String> afterExternal = event -> {};
  boolean failDrafts;
  boolean failMemberships;
  private final GenericApplicationContext context = new GenericApplicationContext();
  private final EntityManager entities;
  private final String accountId;
  private final List<Session> sessions = new ArrayList<>();
  private boolean teamPresent = true;

  AcknowledgedAskerActionsFixture(
      String accountId,
      UserRepository users,
      IdentityTombstoneRepository tombstones,
      DraftMessageRepository actualDrafts,
      PlatformTransactionManager transactions,
      EntityManager entities) {
    this.entities = entities;
    this.accountId = accountId;
    for (long id : List.of(11L, 12L)) {
      var session = new Session();
      session.setId(id);
      session.setMatrixRoomId("!owned-room-" + id + ":fixture.invalid");
      sessions.add(session);
    }
    var matrix = mock(MatrixSynapseService.class, invocation -> {
      String method = invocation.getMethod().getName();
      if (!Set.of("purgeRoom", "deactivateUser").contains(method))
        return Answers.RETURNS_DEFAULTS.answer(invocation);
      String id = invocation.getArgument(0);
      external(method + ":" + id);
      return true;
    });
    var sessionRepository = local(SessionRepository.class, "sessions", invocation -> {
      if ("findByUser".equals(invocation.getMethod().getName())) {
        requireAccount(invocation.getArgument(0));
        return List.copyOf(sessions);
      }
      if ("delete".equals(invocation.getMethod().getName())) {
        Session deleted = invocation.getArgument(0);
        sessions.removeIf(session -> session.getId().equals(deleted.getId()));
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var sessionData = local(SessionDataRepository.class, "sessionData", invocation ->
        "findBySessionId".equals(invocation.getMethod().getName())
            ? List.of() : Answers.RETURNS_DEFAULTS.answer(invocation));
    var handovers = local(CaseHandoverRequestRepository.class, "handovers");
    var supervisors = local(SessionSupervisorRepository.class, "supervisors");
    var topics = local(SessionTopicRepository.class, "topics");
    var teams = local(TeamDiscussionRepository.class, "teams", invocation -> {
      if ("findBySessionId".equals(invocation.getMethod().getName())) {
        Long id = invocation.getArgument(0);
        return teamPresent && id == 11L
            ? Optional.of(TeamDiscussion.builder().id(21L).sessionId(id).tenantId(7L)
                .matrixRoomId("!owned-team:fixture.invalid").build())
            : Optional.empty();
      }
      if ("deleteById".equals(invocation.getMethod().getName())) teamPresent = false;
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var teamMembers = local(TeamDiscussionParticipantRepository.class, "teamMembers");
    var teamWriter = transactionalWriter(new TeamDiscussionPurgeWriter(teams, teamMembers), transactions);
    var teamPurge = new TeamDiscussionPurgeService(teamWriter, matrix);
    var agencies = local(UserAgencyRepository.class, "agencies", invocation -> {
      if ("findByUser".equals(invocation.getMethod().getName())) {
        requireAccount(invocation.getArgument(0));
        return List.of();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var anonymous = local(AnonymousUsernameRegistry.class, "anonymous");
    var appointments = mock(AppointmentService.class, invocation -> {
      if ("deleteAsker".equals(invocation.getMethod().getName())) {
        assertEquals(accountId, invocation.getArgument(0));
        external("appointment");
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var drafts = local(DraftMessageRepository.class, "drafts", invocation -> {
      assertEquals(accountId, invocation.getArgument(0));
      if (failDrafts) throw new IllegalStateException("fixture draft cleanup refused");
      actualDrafts.deleteByUserId(accountId);
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var notifications = local(EventNotificationRepository.class, "notifications");
    var replies = local(ReplyEmailDeliveryRepository.class, "replies");
    var chats = local(UserChatRepository.class, "memberships", invocation -> {
      if (failMemberships) throw new IllegalStateException("fixture membership cleanup refused");
      if ("findByUser".equals(invocation.getMethod().getName())) {
        requireAccount(invocation.getArgument(0));
        return List.of();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var tokens = local(UserMobileTokenRepository.class, "mobileTokens", invocation ->
        "findByUser".equals(invocation.getMethod().getName())
            ? List.of() : Answers.RETURNS_DEFAULTS.answer(invocation));
    var tombstoneService = spy(new IdentityTombstoneService(tombstones));
    doAnswer(invocation -> {
      localBoundary("finalUserDelete");
      requireAccount(invocation.getArgument(0));
      return invocation.callRealMethod();
    }).when(tombstoneService).recordDeletedUser(any());
    register(new DeleteKeycloakAskerAction(forbiddenNative));
    register(new DeleteMatrixAskerAction(matrix, sessionRepository));
    register(new DeleteAskerRoomsAndSessionsAction(sessionRepository, sessionData, handovers,
        supervisors, topics, teams, teamPurge));
    register(new DeleteDatabaseAskerAgencyAction(agencies));
    register(new DeleteAnonymousRegistryIdAction(anonymous));
    register(new DeleteAppointmentServiceAskerAction(appointments));
    register(new DeleteAskerDraftMessagesAction(drafts));
    register(new DeleteAskerEventNotificationsAction(notifications));
    register(new DeleteAskerReplyEmailDeliveriesAction(replies));
    register(new DeleteDatabaseAskerAction(users, chats, tokens, tombstoneService));
    context.refresh();
    service = new DeleteUserAccountService(users, mock(ConsultantRepository.class),
        new ActionsRegistry(context), errorMail, new DeletionLifecycleService(),
        mock(UserHardDeleteClaimService.class));
  }

  private void register(ActionCommand<?> action) {
    context.getBeanFactory().registerSingleton(action.getClass().getSimpleName(), action);
  }

  private TeamDiscussionPurgeWriter transactionalWriter(
      TeamDiscussionPurgeWriter writer, PlatformTransactionManager transactions) {
    var attributes = new AnnotationTransactionAttributeSource();
    var interceptor = new TransactionInterceptor();
    interceptor.setTransactionManager(transactions);
    interceptor.setTransactionAttributeSource(attributes);
    var proxy = new ProxyFactory(writer);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
      var attribute = attributes.getTransactionAttribute(invocation.getMethod(), writer.getClass());
      if (attribute != null && attribute.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW)
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
            "Cleanup must not suspend its owner's SQL transaction for a separate commit");
      return invocation.proceed();
    });
    proxy.addAdvice(interceptor);
    return (TeamDiscussionPurgeWriter) proxy.getProxy();
  }

  private <T> T local(Class<T> type, String name) {
    return local(type, name, Answers.RETURNS_DEFAULTS);
  }

  private <T> T local(Class<T> type, String name, Answer<Object> answer) {
    return mock(type, invocation -> {
      if (invocation.getMethod().getDeclaringClass() == Object.class)
        return Answers.RETURNS_DEFAULTS.answer(invocation);
      localBoundary(name + "." + invocation.getMethod().getName());
      return answer.answer(invocation);
    });
  }

  private void localBoundary(String event) {
    assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), event);
    assertTrue(entities.isJoinedToTransaction(), event);
    assertEquals(7L, TenantContext.getCurrentTenant(), event);
    events.add(event);
  }

  private void external(String event) {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), event);
    assertEquals(7L, TenantContext.getCurrentTenant(), event);
    events.add(event);
    afterExternal.accept(event);
  }

  private void requireAccount(User user) {
    assertEquals(accountId, user.getUserId());
    assertEquals(7L, user.getTenantId());
    assertEquals("@owned-cleanup:fixture.invalid", user.getMatrixUserId());
  }

  void assertAllNineActionBoundaries() {
    var markers = List.of("purgeRoom:!owned-room-11:fixture.invalid", "sessions.delete",
        "agencies.deleteAll", "anonymous.removeRegistryIdByUsername", "appointment",
        "drafts.deleteByUserId", "notifications.deleteByRecipientUserId",
        "replies.deleteByRecipientKindAndRecipientUserId", "finalUserDelete");
    int previous = -1;
    for (String marker : markers) {
      int index = events.indexOf(marker);
      assertTrue(index > previous, "Missing or out-of-order action " + marker + " in " + events);
      previous = index;
    }
    assertTrue(events.contains("purgeRoom:!owned-team:fixture.invalid"));
    assertTrue(events.contains("teamMembers.deleteByTeamDiscussionId"));
    assertTrue(events.contains("teams.deleteById"));
    verifyNoInteractions(forbiddenNative);
  }

  @Override public void close() { context.close(); }
}
