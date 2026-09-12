package de.caritas.cob.userservice.api.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserMobileTokenRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.user.UserService;
import jakarta.persistence.Column;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.auditing.AuditingHandler;

class ChatRecoveryAccountCreationTest {
  private final UserRepository repository = mock(UserRepository.class);
  private final UserService users =
      new UserService(
          repository, mock(UserMobileTokenRepository.class), mock(AuditingHandler.class));

  @Test
  void snapshotIsPresentOnFirstInsertAndRetryRetainsIt() {
    when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
    var user =
        users.createUser(
            "id",
            null,
            "name",
            "email",
            false,
            null,
            new RecoveryPolicySnapshot("LOGIN_PASSWORD", 3));
    assertEquals("LOGIN_PASSWORD", user.getChatRecoveryMode());
    assertEquals(3L, user.getChatRecoveryPolicyRevision());
    when(repository.findById("id")).thenReturn(Optional.of(user));
    assertSame(
        user,
        users.createUser(
            "id",
            null,
            "changed",
            "changed",
            false,
            null,
            new RecoveryPolicySnapshot("RECOVERY_KEY", 4)));
    assertEquals("LOGIN_PASSWORD", user.getChatRecoveryMode());
    assertEquals(3L, user.getChatRecoveryPolicyRevision());
    verify(repository, times(1)).save(any());
  }

  @Test
  void legacyRetryRemainsLegacyAndGenericProfileSaveDoesNotEnroll() {
    var old = new User();
    when(repository.findById("id")).thenReturn(Optional.of(old));
    assertSame(
        old,
        users.createUser(
            "id", null, "n", "e", false, null, new RecoveryPolicySnapshot("LOGIN_PASSWORD", 4)));
    users.saveUser(old);
    assertNull(old.getChatRecoveryMode());
    assertEquals("RECOVERY_KEY", old.getEffectiveChatRecoveryMode());
    assertEquals(0, old.getEffectiveChatRecoveryPolicyRevision());
  }

  @Test
  void bothAccountSnapshotsAreImmutableInJpa() throws Exception {
    for (var type : new Class<?>[] {User.class, Consultant.class}) {
      for (var field : new String[] {"chatRecoveryMode", "chatRecoveryPolicyRevision"}) {
        var column = type.getDeclaredField(field).getAnnotation(Column.class);
        assertFalse(column.updatable());
        assertTrue(column.nullable());
      }
    }
  }
}
