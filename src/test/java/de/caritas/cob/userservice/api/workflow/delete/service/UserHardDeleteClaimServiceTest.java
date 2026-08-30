package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserHardDeleteClaimServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private DeletionLifecycleService deletionLifecycleService;

  private UserHardDeleteClaimService claimService;

  @BeforeEach
  void setUp() {
    claimService = new UserHardDeleteClaimService(userRepository, deletionLifecycleService);
  }

  @Test
  void claimLocksNormalizesAndTransitionsOneReadyRowBeforeReturningIt() {
    User user = deletedUser();
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));
    when(deletionLifecycleService.normalizeUserLifecycle(user)).thenReturn(user);
    when(deletionLifecycleService.isReadyForHardDelete(user)).thenReturn(true);
    when(userRepository.save(user)).thenReturn(user);

    User claimed = claimService.claim("user-1").orElseThrow();

    assertSame(user, claimed);
    assertEquals(
        DeletionLifecycleState.HARD_DELETE_IN_PROGRESS, claimed.getDeletionLifecycleState());
  }

  @Test
  void claimReturnsEmptyWithoutLifecycleMutationWhenCancellationAlreadyClearedDeleteDate() {
    User active = deletedUser();
    active.setDeleteDate(null);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(active));

    assertEquals(Optional.empty(), claimService.claim("user-1"));
    verifyNoInteractions(deletionLifecycleService);
  }

  @Test
  void claimPersistsNormalizationButDoesNotClaimAnUnreadyRow() {
    User user = deletedUser();
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));
    when(deletionLifecycleService.normalizeUserLifecycle(user)).thenReturn(user);
    when(deletionLifecycleService.isReadyForHardDelete(user)).thenReturn(false);

    assertEquals(Optional.empty(), claimService.claim("user-1"));
    verify(userRepository).save(user);
  }

  @Test
  void releaseOnlyReturnsAnInProgressRetainedRowToRetryableReadOnlyState() {
    claimService.release("user-1");

    verify(userRepository)
        .releaseUserHardDeleteClaim(
            "user-1",
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.READ_ONLY_SAFEGUARD);
  }

  @Test
  void releaseInterruptedClaimsRecoversOnlyAfterSchedulerOwnsGlobalLease() {
    when(userRepository.releaseInterruptedUserHardDeleteClaims(
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.READ_ONLY_SAFEGUARD))
        .thenReturn(2);

    assertEquals(2, claimService.releaseInterruptedClaims());
  }

  private User deletedUser() {
    var user = new User();
    user.setUserId("user-1");
    user.setDeleteDate(LocalDateTime.now());
    user.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return user;
  }
}
