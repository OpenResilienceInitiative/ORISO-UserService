package de.caritas.cob.userservice.api.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdentityReactivationRepairWriterTest {

  @InjectMocks private IdentityReactivationRepairWriter writer;
  @Mock private UserRepository userRepository;

  @Test
  void markRepairRequiredPersistsBlockedLifecycleInIndependentWriter() {
    User user = deletedUser(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.markRepairRequired("user-1")).isTrue();

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
    verify(userRepository).save(user);
  }

  @Test
  void markRepairRequiredNeverMakesPartialHardDeleteReactivationEligible() {
    User user = deletedUser(DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.markRepairRequired("user-1")).isFalse();

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    verify(userRepository).findByUserIdForUpdate("user-1");
    verifyNoMoreInteractions(userRepository);
  }

  @Test
  void resolveRepairReturnsOnlyTheDurableRepairStateToReadOnlyDeletion() {
    User user = deletedUser(DeletionLifecycleState.REACTIVATION_REPAIR_REQUIRED);
    when(userRepository.findByUserIdForUpdate("user-1")).thenReturn(Optional.of(user));

    assertThat(writer.resolveRepair("user-1")).isTrue();

    assertThat(user.getDeletionLifecycleState())
        .isEqualTo(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    verify(userRepository).save(user);
  }

  private User deletedUser(DeletionLifecycleState state) {
    var user = new User();
    user.setUserId("user-1");
    user.setDeleteDate(LocalDateTime.now());
    user.setDeletionLifecycleState(state);
    return user;
  }
}
