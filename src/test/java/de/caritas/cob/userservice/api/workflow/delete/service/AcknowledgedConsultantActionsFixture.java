package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.actions.ActionCommand;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.session.SessionOwnershipService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import jakarta.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.mockito.Answers;
import org.mockito.stubbing.Answer;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Concrete nine legacy actions; child repositories/RPC are boundary doubles, final row/draft real. */
final class AcknowledgedConsultantActionsFixture implements AutoCloseable {
  final List<String> events=new ArrayList<>();
  final DeleteUserAccountService service;
  final DeleteDatabaseConsultantAction finalAction;
  final IdentityAccountRemover forbiddenNative=mock(IdentityAccountRemover.class);
  final AtomicInteger serviceEntries=new AtomicInteger();
  Consumer<ConsultantDeletionWorkflowDTO> beforeMatrixAction=target->{};
  Consumer<String> afterExternal=event->{};
  Runnable beforeFinalCommit=()->{};
  boolean failDrafts,failNotifications,failOwnership,failSupervision,failMobile;
  private final GenericApplicationContext context=new GenericApplicationContext();
  private final EntityManager entities;
  private final String account;

  AcknowledgedConsultantActionsFixture(String account,ConsultantRepository consultants,
      IdentityTombstoneRepository tombstones,DraftMessageRepository actualDrafts,
      PlatformTransactionManager transactions,EntityManager entities) {
    this.account=account;this.entities=entities;
    var matrix=mock(MatrixSynapseService.class,invocation->{
      if(Set.of("purgeRoom","deactivateUser").contains(invocation.getMethod().getName())) {
        external(invocation.getMethod().getName()+":"+invocation.getArgument(0));return true;
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var sessions=local(SessionRepository.class,"sessions",invocation->{
      if(invocation.getMethod().getName().equals("findByConsultantId")) {
        assertEquals(account,invocation.getArgument(0));
        var session=new Session();session.setId(11L);session.setMatrixRoomId("!owned-consultant-room:fixture.invalid");
        return new PageImpl<>(List.of(session));
      }
      if(invocation.getMethod().getName().equals("findIdsByConsultantAndStatusInOrderById")) {
        requireAccount(invocation.getArgument(0));
        assertEquals(List.of(Session.SessionStatus.NEW,Session.SessionStatus.INITIAL),invocation.getArgument(1));
        if(failOwnership)throw new IllegalStateException("fixture session owner cleanup refused");return List.of();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var agencies=local(ConsultantAgencyRepository.class,"agencies");
    var chats=local(ChatRepository.class,"chats",invocation->{
      if(invocation.getMethod().getName().equals("findByChatOwner")) {
        requireAccount(invocation.getArgument(0));var chat=new Chat();chat.setId(81L);chat.setMatrixRoomId("!owned-consultant-chat:fixture.invalid");return List.of(chat);
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var appointments=mock(AppointmentService.class,invocation->{
      if(invocation.getMethod().getName().equals("deleteConsultant")) {
        assertEquals(account,invocation.getArgument(0));external("appointment");
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var handovers=local(CaseHandoverRequestRepository.class,"handovers");
    var drafts=local(DraftMessageRepository.class,"drafts",invocation->{
      assertEquals(account,invocation.getArgument(0));if(failDrafts)throw new IllegalStateException("fixture draft refused");
      actualDrafts.deleteByUserId(account);return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var notifications=local(EventNotificationRepository.class,"notifications",invocation->{
      if(failNotifications)throw new IllegalStateException("fixture notification refused");return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var email=local(ReplyEmailDeliveryRepository.class,"email");
    var supervisors=local(SessionSupervisorRepository.class,"supervisors",invocation->{
      if(failSupervision)throw new IllegalStateException("fixture supervision refused");return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var mobile=local(ConsultantMobileTokenRepository.class,"mobile",invocation->{
      if(failMobile)throw new IllegalStateException("fixture mobile cleanup refused");return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    var actualOwnership=new SessionOwnershipService(sessions,entities,
        mock(de.caritas.cob.userservice.api.service.matrix.InquiryAcceptanceNoticeStore.class));
    var attributes=new AnnotationTransactionAttributeSource();var interceptor=new TransactionInterceptor();
    interceptor.setTransactionManager(transactions);interceptor.setTransactionAttributeSource(attributes);
    var proxy=new ProxyFactory(actualOwnership);proxy.setProxyTargetClass(true);proxy.addAdvice(interceptor);
    var tombstone=spy(new IdentityTombstoneService(tombstones));
    doAnswer(invocation->{localBoundary("finalConsultantDelete");requireAccount(invocation.getArgument(0));beforeFinalCommit.run();return invocation.callRealMethod();})
        .when(tombstone).recordDeletedConsultant(any());
    register(new DeleteKeycloakConsultantAction(forbiddenNative));
    var matrixAction=spy(new DeleteMatrixConsultantAction(matrix,sessions));
    doAnswer(invocation->{beforeMatrixAction.accept(invocation.getArgument(0));return invocation.callRealMethod();}).when(matrixAction).execute(any());register(matrixAction);
    register(new DeleteDatabaseConsultantAgencyAction(agencies));register(new DeleteChatAction(chats,matrix));
    register(new DeleteAppointmentServiceConsultantAction(appointments));register(new DeleteCaseHandoverRequestsForConsultantAction(handovers));
    register(new DeleteConsultantDraftMessagesAction(drafts));register(new DeleteConsultantEventNotificationsAction(notifications));
    register(new DeleteConsultantMessageEmailDeliveriesAction(email));
    finalAction=new DeleteDatabaseConsultantAction(consultants,(SessionOwnershipService)proxy.getProxy(),supervisors,mobile,tombstone);register(finalAction);
    context.refresh();
    service=spy(new DeleteUserAccountService(mock(UserRepository.class),consultants,new ActionsRegistry(context),
        mock(WorkflowErrorMailService.class),new DeletionLifecycleService(),mock(UserHardDeleteClaimService.class)));
    doAnswer(invocation->{serviceEntries.incrementAndGet();return invocation.callRealMethod();}).when(service).continueAcknowledgedConsultantDeletion(any());
  }

  private <T>T local(Class<T> type,String name) { return local(type,name,Answers.RETURNS_DEFAULTS); }
  private <T>T local(Class<T> type,String name,Answer<?> body) {
    return mock(type,invocation->{
      if(invocation.getMethod().getDeclaringClass()==Object.class)return Answers.RETURNS_DEFAULTS.answer(invocation);
      localBoundary(name+"."+invocation.getMethod().getName());return body.answer(invocation);
    });
  }
  private void localBoundary(String event) {
    assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),event);assertTrue(entities.isJoinedToTransaction(),event);
    assertEquals(7L,TenantContext.getCurrentTenant(),event);events.add(event);
  }
  private void external(String event) {
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),event);assertEquals(7L,TenantContext.getCurrentTenant(),event);events.add(event);afterExternal.accept(event);
  }
  private void requireAccount(Consultant consultant) {
    assertEquals(account,consultant.getId());assertEquals(7L,consultant.getTenantId());assertEquals("@owned-cleanup:fixture.invalid",consultant.getMatrixUserId());
  }
  private void register(ActionCommand<?> action) {context.getBeanFactory().registerSingleton(action.getClass().getSimpleName(),action);}
  void assertAllNine() {
    int prior=-1;
    for(String marker:List.of("purgeRoom:!owned-consultant-room:fixture.invalid","agencies.deleteAll","chats.deleteAll",
        "appointment","handovers.deleteAll","drafts.deleteByUserId","notifications.deleteByRecipientUserId",
        "email.deleteByRecipientKindAndRecipientUserId","finalConsultantDelete")) {
      int index=events.indexOf(marker);assertTrue(index>prior,"Missing/out-of-order "+marker+" in "+events);prior=index;
    }
    verifyNoInteractions(forbiddenNative);
  }
  @Override public void close() {context.close();}
}
