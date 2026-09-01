package de.caritas.cob.userservice.api.workflow.delete.service;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.ActionCommandMockProvider;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteMatrixAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteMatrixConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionWorkflowError;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class DeleteUserAccountServiceTest {

  @InjectMocks private DeleteUserAccountService deleteUserAccountService;

  @Mock private UserRepository userRepository;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private ActionsRegistry actionsRegistry;

  @Mock private WorkflowErrorMailService workflowErrorMailService;

  @Mock private DeletionLifecycleService deletionLifecycleService;

  @Mock private UserHardDeleteClaimService userHardDeleteClaimService;

  private final ActionCommandMockProvider commandMockProvider = new ActionCommandMockProvider();

  @BeforeEach
  public void setupLifecycleMocks() {
    lenient()
        .when(deletionLifecycleService.normalizeConsultantLifecycle(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    // Echo the requested id. The blanket stub used to hand back a blank User, so a test that left
    // userId null could claim and delete an account other than the one it set up.
    lenient()
        .when(userHardDeleteClaimService.claim(anyString()))
        .thenAnswer(
            invocation -> {
              var claimed = new User();
              claimed.setUserId(invocation.getArgument(0));
              return Optional.of(claimed);
            });
    lenient()
        .when(deletionLifecycleService.isReadyForHardDelete(any(Consultant.class)))
        .thenReturn(true);
  }

  @Test
  public void deleteUserAccounts_Should_notPerformAnyDeletion_When_noUserAccountIsMarkedDeleted() {
    this.deleteUserAccountService.deleteUserAccounts();

    verifyNoMoreInteractions(this.workflowErrorMailService);
    verifyNoMoreInteractions(this.actionsRegistry);
  }

  @Test
  public void deleteUserAccounts_Should_performAskerDeletion_When_userIsMarkedAsDeleted() {
    User user = new User();
    user.setUserId("deleted-user");
    when(this.userRepository.findAllByDeleteDateNotNull()).thenReturn(singletonList(user));
    when(userHardDeleteClaimService.claim("deleted-user")).thenReturn(Optional.of(user));
    when(this.actionsRegistry.buildContainerForType(AskerDeletionWorkflowDTO.class))
        .thenReturn(this.commandMockProvider.getActionContainer(AskerDeletionWorkflowDTO.class));

    this.deleteUserAccountService.deleteUserAccounts();

    verify(this.actionsRegistry, times(1)).buildContainerForType(AskerDeletionWorkflowDTO.class);
    verify(userHardDeleteClaimService).claim(user.getUserId());
    verify(userHardDeleteClaimService).release(user.getUserId());
    verify(this.commandMockProvider.getActionMock(DeleteMatrixAskerAction.class), times(1))
        .execute(new AskerDeletionWorkflowDTO(user, emptyList()));
    verify(this.commandMockProvider.getActionMock(DeleteDatabaseAskerAction.class), times(1))
        .execute(new AskerDeletionWorkflowDTO(user, emptyList()));
    // #983 / #1010: drafts hold the only unencrypted counselling content server-side and must
    // not survive account deletion.
    verify(this.commandMockProvider.getActionMock(DeleteAskerDraftMessagesAction.class), times(1))
        .execute(new AskerDeletionWorkflowDTO(user, emptyList()));
    // #1010 task 2b: notification rows carry identity, session references and read timestamps.
    verify(
            this.commandMockProvider.getActionMock(DeleteAskerEventNotificationsAction.class),
            times(1))
        .execute(new AskerDeletionWorkflowDTO(user, emptyList()));
    verifyNoMoreInteractions(this.workflowErrorMailService);
  }

  @Test
  void deleteUserAccounts_Should_skipDestructiveActions_When_hardDeleteClaimIsLost() {
    User user = new User();
    user.setUserId("racing-user");
    when(userRepository.findAllByDeleteDateNotNull()).thenReturn(singletonList(user));
    when(userHardDeleteClaimService.claim("racing-user")).thenReturn(Optional.empty());

    deleteUserAccountService.deleteUserAccounts();

    verify(userHardDeleteClaimService).claim("racing-user");
    verifyNoMoreInteractions(actionsRegistry, userHardDeleteClaimService);
  }

  @Test
  public void
      deleteUserAccounts_Should_performConsultantDeletion_When_consultantIsMarkedAsDeleted() {
    Consultant consultant = new Consultant();
    when(this.consultantRepository.findAllByDeleteDateNotNull())
        .thenReturn(singletonList(consultant));
    when(this.deletionLifecycleService.normalizeConsultantLifecycle(consultant))
        .thenReturn(consultant);
    when(this.deletionLifecycleService.isReadyForHardDelete(consultant)).thenReturn(true);
    when(this.actionsRegistry.buildContainerForType(ConsultantDeletionWorkflowDTO.class))
        .thenReturn(
            this.commandMockProvider.getActionContainer(ConsultantDeletionWorkflowDTO.class));

    this.deleteUserAccountService.deleteUserAccounts();

    verify(this.actionsRegistry, times(1))
        .buildContainerForType(ConsultantDeletionWorkflowDTO.class);
    verify(this.commandMockProvider.getActionMock(DeleteMatrixConsultantAction.class), times(1))
        .execute(new ConsultantDeletionWorkflowDTO(consultant, emptyList()));
    verify(this.commandMockProvider.getActionMock(DeleteDatabaseConsultantAction.class), times(1))
        .execute(new ConsultantDeletionWorkflowDTO(consultant, emptyList()));
    verify(
            this.commandMockProvider.getActionMock(DeleteConsultantDraftMessagesAction.class),
            times(1))
        .execute(new ConsultantDeletionWorkflowDTO(consultant, emptyList()));
    verify(
            this.commandMockProvider.getActionMock(DeleteConsultantEventNotificationsAction.class),
            times(1))
        .execute(new ConsultantDeletionWorkflowDTO(consultant, emptyList()));
    verifyNoMoreInteractions(this.workflowErrorMailService);
  }

  @Test
  public void deleteUserAccounts_Should_sendErrorMails_When_someActionsFail() {
    User user = new User();
    user.setUserId("user-id");
    when(this.userRepository.findAllByDeleteDateNotNull()).thenReturn(singletonList(user));
    when(userHardDeleteClaimService.claim("user-id")).thenReturn(Optional.of(user));
    when(this.actionsRegistry.buildContainerForType(AskerDeletionWorkflowDTO.class))
        .thenReturn(this.commandMockProvider.getActionContainer(AskerDeletionWorkflowDTO.class));
    doAnswer(
            invocation -> {
              AskerDeletionWorkflowDTO workflow = invocation.getArgument(0);
              workflow.getDeletionWorkflowErrors().add(DeletionWorkflowError.builder().build());
              return null;
            })
        .when(this.commandMockProvider.getActionMock(DeleteMatrixAskerAction.class))
        .execute(any());

    this.deleteUserAccountService.deleteUserAccounts();

    verify(this.workflowErrorMailService, times(1)).buildAndSendErrorMail(anyList());
  }
}
