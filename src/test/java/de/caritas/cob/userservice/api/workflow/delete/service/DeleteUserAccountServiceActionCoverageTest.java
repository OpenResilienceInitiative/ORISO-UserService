package de.caritas.cob.userservice.api.workflow.delete.service;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.actions.registry.ActionContainer;
import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAnonymousRegistryIdAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAppointmentServiceAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteAskerRoomsAndSessionsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteDatabaseAskerAgencyAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteKeycloakAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.DeleteMatrixAskerAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteAppointmentServiceConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteCaseHandoverRequestsForConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteChatAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantDraftMessagesAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteConsultantEventNotificationsAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAgencyAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteKeycloakConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteMatrixConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.model.AskerDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pins the complete deletion chain for both account kinds (#1010, task 5b).
 *
 * <p>Account deletion had quietly grown incomplete: it erased Keycloak, Matrix and the account row
 * but left the user's drafts and notification feed behind. Verifying individual actions cannot
 * catch that class of gap — a table nobody remembers simply never appears in any assertion. This
 * test asserts the chain as a whole, in order, so adding a user-keyed table without deciding what
 * account deletion does about it fails here instead of silently opting out.
 *
 * <p>Order is part of the contract: everything that reads the account must run before the action
 * that deletes the account row itself.
 */
@ExtendWith(MockitoExtension.class)
class DeleteUserAccountServiceActionCoverageTest {

  private static final List<Class<?>> EXPECTED_ASKER_CHAIN =
      List.of(
          DeleteKeycloakAskerAction.class,
          DeleteMatrixAskerAction.class,
          DeleteAskerRoomsAndSessionsAction.class,
          DeleteDatabaseAskerAgencyAction.class,
          DeleteAnonymousRegistryIdAction.class,
          DeleteAppointmentServiceAskerAction.class,
          DeleteAskerDraftMessagesAction.class,
          DeleteAskerEventNotificationsAction.class,
          DeleteDatabaseAskerAction.class);

  private static final List<Class<?>> EXPECTED_CONSULTANT_CHAIN =
      List.of(
          DeleteKeycloakConsultantAction.class,
          DeleteMatrixConsultantAction.class,
          DeleteDatabaseConsultantAgencyAction.class,
          DeleteChatAction.class,
          DeleteAppointmentServiceConsultantAction.class,
          DeleteCaseHandoverRequestsForConsultantAction.class,
          DeleteConsultantDraftMessagesAction.class,
          DeleteConsultantEventNotificationsAction.class,
          DeleteDatabaseConsultantAction.class);

  @InjectMocks private DeleteUserAccountService deleteUserAccountService;

  @Mock private UserRepository userRepository;

  @Mock private ConsultantRepository consultantRepository;

  @Mock private ActionsRegistry actionsRegistry;

  @Mock private WorkflowErrorMailService workflowErrorMailService;

  @Mock private DeletionLifecycleService deletionLifecycleService;

  @Mock private UserHardDeleteClaimService userHardDeleteClaimService;

  @Mock private ActionContainer<AskerDeletionWorkflowDTO> askerContainer;

  @Mock private ActionContainer<ConsultantDeletionWorkflowDTO> consultantContainer;

  @BeforeEach
  void setupLifecycleMocks() {
    lenient()
        .when(deletionLifecycleService.normalizeConsultantLifecycle(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    lenient()
        .when(userHardDeleteClaimService.claim(any()))
        .thenAnswer(invocation -> Optional.of(new User()));
    lenient()
        .when(deletionLifecycleService.isReadyForHardDelete(any(Consultant.class)))
        .thenReturn(true);
  }

  @Test
  void askerDeletion_registersEveryActionOfTheChain_inOrder() {
    when(this.userRepository.findAllByDeleteDateNotNull()).thenReturn(singletonList(new User()));
    when(this.actionsRegistry.buildContainerForType(AskerDeletionWorkflowDTO.class))
        .thenReturn(this.askerContainer);
    when(this.askerContainer.addActionToExecute(any())).thenReturn(this.askerContainer);

    this.deleteUserAccountService.deleteUserAccounts();

    assertThat(registeredActions(this.askerContainer))
        .as("asker deletion chain — a new user-keyed table must be decided on, not forgotten")
        .containsExactlyElementsOf(EXPECTED_ASKER_CHAIN);
  }

  @Test
  void consultantDeletion_registersEveryActionOfTheChain_inOrder() {
    when(this.consultantRepository.findAllByDeleteDateNotNull())
        .thenReturn(singletonList(new Consultant()));
    when(this.actionsRegistry.buildContainerForType(ConsultantDeletionWorkflowDTO.class))
        .thenReturn(this.consultantContainer);
    when(this.consultantContainer.addActionToExecute(any())).thenReturn(this.consultantContainer);

    this.deleteUserAccountService.deleteUserAccounts();

    assertThat(registeredActions(this.consultantContainer))
        .as("consultant deletion chain — a new user-keyed table must be decided on, not forgotten")
        .containsExactlyElementsOf(EXPECTED_CONSULTANT_CHAIN);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static List<Class> registeredActions(ActionContainer<?> container) {
    ArgumentCaptor<Class> captor = ArgumentCaptor.forClass(Class.class);
    verify(container, atLeastOnce()).addActionToExecute(captor.capture());
    return captor.getAllValues();
  }
}
