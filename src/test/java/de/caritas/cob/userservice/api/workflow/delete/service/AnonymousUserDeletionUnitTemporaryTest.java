package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * #1662: the 24 h job for Gesprächskreis guests reads the account under the row lock that "Konto
 * behalten" takes, so an account kept in the meantime is not deleted.
 */
class AnonymousUserDeletionUnitTemporaryTest {

  private final UserRepository users = mock(UserRepository.class);
  private final DeleteUserAccountService deletion = mock(DeleteUserAccountService.class);
  private final AnonymousUserDeletionUnit unit = new AnonymousUserDeletionUnit(users, deletion);

  @Test
  void deletesAnAccountThatIsStillTemporary() {
    var guest = new User();
    guest.setTemporaryAccount(true);
    when(users.findByUserIdForUpdate("g")).thenReturn(Optional.of(guest));
    when(deletion.performUserDeletion(guest)).thenReturn(List.of());

    assertThat(unit.deleteTemporaryUser("g")).isEmpty();
    verify(deletion).performUserDeletion(guest);
  }

  @Test
  void leavesAnAccountTheGuestKept() {
    var kept = new User();
    kept.setTemporaryAccount(false);
    when(users.findByUserIdForUpdate("g")).thenReturn(Optional.of(kept));

    assertThat(unit.deleteTemporaryUser("g")).isEmpty();
    verify(deletion, never()).performUserDeletion(any());
  }
}
