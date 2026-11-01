package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.AccountCreationAttempt;
import de.caritas.cob.userservice.api.port.out.AccountCreationAttemptRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountCreationAttemptJournalTest {
  private static final UUID OPERATION_KEY = UUID.fromString("c17c20da-1f9e-46bf-9353-375d86eb8c75");
  private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

  @Mock private AccountCreationAttemptRepository repository;

  private AccountCreationAttemptJournal journal;

  @BeforeEach
  void setUp() {
    journal = new AccountCreationAttemptJournal(repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void startsOneAttemptForANewOperationKey() {
    when(repository.findByOperationKey(OPERATION_KEY)).thenReturn(Optional.empty());
    when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    AccountCreationAttempt attempt = journal.begin(OPERATION_KEY, "owner-id", 2L);

    assertThat(attempt.getOperationKey()).isEqualTo(OPERATION_KEY);
    assertThat(attempt.getOwnerId()).isEqualTo("owner-id");
    assertThat(attempt.getTenantId()).isEqualTo(2L);
    assertThat(attempt.getStatus()).isEqualTo(AccountCreationAttempt.Status.PENDING);
    assertThat(attempt.getCreatedAt()).isEqualTo("2026-10-23T12:00");
  }

  @Test
  void returnsExistingAttemptWithoutCreatingAnotherOne() {
    AccountCreationAttempt existing =
        AccountCreationAttempt.builder().operationKey(OPERATION_KEY).build();
    when(repository.findByOperationKey(OPERATION_KEY)).thenReturn(Optional.of(existing));

    assertThat(journal.begin(OPERATION_KEY, "owner-id", 2L)).isSameAs(existing);

    verify(repository, never()).saveAndFlush(any());
  }

  @Test
  void recordsNativeIdentityOnlyOnce() {
    AccountCreationAttempt attempt =
        AccountCreationAttempt.builder()
            .operationKey(OPERATION_KEY)
            .status(AccountCreationAttempt.Status.PENDING)
            .build();
    when(repository.findByOperationKeyForUpdate(OPERATION_KEY)).thenReturn(Optional.of(attempt));

    AccountCreationAttempt result = journal.recordNativeIdentity(OPERATION_KEY, "native-id");

    assertThat(result.getNativeIdentityId()).isEqualTo("native-id");
    assertThat(result.getStatus()).isEqualTo(AccountCreationAttempt.Status.NATIVE_ID_KNOWN);
    assertThat(result.getUpdatedAt()).isEqualTo("2026-10-23T12:00");
  }

  @Test
  void refusesToReplaceAKnownNativeIdentity() {
    AccountCreationAttempt attempt =
        AccountCreationAttempt.builder()
            .operationKey(OPERATION_KEY)
            .nativeIdentityId("first-id")
            .status(AccountCreationAttempt.Status.NATIVE_ID_KNOWN)
            .build();
    when(repository.findByOperationKeyForUpdate(OPERATION_KEY)).thenReturn(Optional.of(attempt));

    assertThatThrownBy(() -> journal.recordNativeIdentity(OPERATION_KEY, "second-id"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("creation attempt already has a different native identity");
  }
}
