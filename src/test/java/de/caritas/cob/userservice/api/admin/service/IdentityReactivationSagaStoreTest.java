package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityReactivator;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityReactivationSagaStoreTest {

  @InjectMocks private IdentityReactivationSagaStore sagaStore;
  @Mock private UserRepository userRepository;
  @Mock private UsernameTranscoder usernameTranscoder;
  @Mock private IdentityReactivator identityReactivator;
  @Mock private DeletionLifecycleService deletionLifecycleService;

  @BeforeEach
  void encodeUsername() {
    lenient()
        .when(usernameTranscoder.encodeUsername("marge.simpson@dreambau.de"))
        .thenReturn("marge.simpson_at_dreambau.de");
    lenient()
        .when(usernameTranscoder.decodeUsername("marge.simpson@dreambau.de"))
        .thenReturn("marge.simpson@dreambau.de");
    lenient()
        .when(usernameTranscoder.decodeUsername("marge.simpson_at_dreambau.de"))
        .thenReturn("marge.simpson@dreambau.de");
  }

  @Test
  void beginPersistsGenerationClaimBeforeAnyExternalMutation() {
    User user = deletedUser();
    when(userRepository.findAllByUsernameInOrderByCreateDateAsc(any())).thenReturn(List.of(user));

    IdentityReactivationOperation operation =
        sagaStore.begin("marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);

    assertThat(operation.userId()).isEqualTo("user-1");
    assertThat(operation.operationId()).isNotBlank();
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    assertThat(user.getReactivationOperationId()).isEqualTo(operation.operationId());
    assertThat(user.getReactivationOperationStartedAt()).isNotNull();
    verify(userRepository).save(user);
    verifyNoInteractions(identityReactivator);
  }

  @Test
  void beginRejectsASecondOperationWhileDurableClaimExists() {
    User user = deletedUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    user.setReactivationOperationId("operation-1");
    when(userRepository.findAllByUsernameInOrderByCreateDateAsc(any())).thenReturn(List.of(user));

    assertThrows(
        ConflictException.class,
        () -> sagaStore.begin("marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L));

    verify(userRepository, never()).save(any());
    verifyNoInteractions(identityReactivator);
  }

  @Test
  void reactivateAndCommitUsesOnlyCurrentGenerationAndClearsClaimAtomically() {
    User user = claimedUser("operation-1");
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));
    IdentityReactivationOperation operation = operation("operation-1");

    sagaStore.reactivateAndCommit(operation, "NewPassw0rd!");

    verify(identityReactivator)
        .reactivateUser(
            "user-1",
            "marge.simpson@dreambau.de",
            "marge.simpson@dreambau.de",
            40L,
            "NewPassw0rd!");
    verify(deletionLifecycleService).cancelUserDeletion(user);
    assertThat(user.getReactivationOperationId()).isNull();
    assertThat(user.getReactivationOperationStartedAt()).isNull();
    verify(userRepository).save(user);
  }

  @Test
  void staleOperationCannotReachKeycloakAfterNewGenerationExists() {
    User user = claimedUser("operation-2");
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    IdentityReactivationUnmutatedException failure =
        assertThrows(
            IdentityReactivationUnmutatedException.class,
            () -> sagaStore.reactivateAndCommit(operation("operation-1"), "OldPassw0rd!"));

    assertThat(failure.originalFailure()).isInstanceOf(ConflictException.class);
    verifyNoInteractions(identityReactivator, deletionLifecycleService);
    verify(userRepository, never()).save(any());
  }

  @Test
  void keycloakTupleMismatchReleasesClaimInSameNoRollbackTransaction() {
    User user = claimedUser("operation-1");
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));
    org.mockito.Mockito.doThrow(new ConflictException("identity mismatch"))
        .when(identityReactivator)
        .reactivateUser(any(), any(), any(), any(), any());

    IdentityReactivationUnmutatedException failure =
        assertThrows(
            IdentityReactivationUnmutatedException.class,
            () -> sagaStore.reactivateAndCommit(operation("operation-1"), "NewPassw0rd!"));

    assertThat(failure.originalFailure()).isInstanceOf(ConflictException.class);
    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    assertThat(user.getReactivationOperationId()).isNull();
    verify(userRepository).save(user);
  }

  @Test
  void abortUnmutatedDoesNotReleaseAnotherGeneration() {
    User user = claimedUser("operation-2");
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(sagaStore.abortUnmutated(operation("operation-1"))).isFalse();

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    verify(userRepository, never()).save(any());
  }

  private User deletedUser() {
    var user = new User();
    user.setUserId("user-1");
    user.setUsername("marge.simpson_at_dreambau.de");
    user.setEmail("marge.simpson@dreambau.de");
    user.setTenantId(40L);
    user.setDeleteDate(LocalDateTime.now());
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return user;
  }

  private User claimedUser(String operationId) {
    User user = deletedUser();
    user.setDeletionLifecycleState(DeletionLifecycleState.REACTIVATION_IN_PROGRESS);
    user.setReactivationOperationId(operationId);
    user.setReactivationOperationStartedAt(LocalDateTime.now());
    return user;
  }

  private IdentityReactivationOperation operation(String operationId) {
    return new IdentityReactivationOperation(
        "user-1", operationId, "marge.simpson@dreambau.de", "marge.simpson@dreambau.de", 40L);
  }
}
