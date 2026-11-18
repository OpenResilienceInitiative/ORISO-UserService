package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnonymousUserDeletionUnitTest {

  @Mock private UserRepository userRepository;
  @Mock private DeleteUserAccountService deleteUserAccountService;
  @InjectMocks private AnonymousUserDeletionUnit unit;

  @Test
  void deletesTheUserUnderItsRowLock() {
    var user = new User();
    user.setUserId("temp");
    when(userRepository.findByUserIdForUpdate("temp")).thenReturn(Optional.of(user));
    when(deleteUserAccountService.performUserDeletion(user)).thenReturn(List.of());

    assertThat(unit.deleteUser("temp")).isEmpty();

    verify(deleteUserAccountService).performUserDeletion(user);
  }

  @Test
  void userAlreadyGoneIsTreatedAsDeleted() {
    when(userRepository.findByUserIdForUpdate("gone")).thenReturn(Optional.empty());

    assertThat(unit.deleteUser("gone")).isEmpty();

    verify(deleteUserAccountService, never()).performUserDeletion(any());
  }
}
