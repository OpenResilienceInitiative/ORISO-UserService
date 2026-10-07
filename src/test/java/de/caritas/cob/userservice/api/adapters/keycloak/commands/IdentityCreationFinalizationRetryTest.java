package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.model.IdentityCreationAttempt;
import java.util.*;
import org.junit.jupiter.api.Test;

class IdentityCreationFinalizationRetryTest {
  @Test
  void OnlyCapturedIntentWithOwnedReceiptMayRetryAndFailuresDoNotStopOtherRows() {
    var journal = mock(IdentityCreationJournalWriter.class);
    var provisioning = mock(IdentityAccountProvisioning.class);
    var open = row("OPEN");
    var missing = row("COMPENSATION_REQUESTED");
    var commit = row("COMMIT_REQUESTED");
    var compensate = row("COMPENSATION_REQUESTED");
    missing.setCreationProof(null);
    when(journal.pending()).thenReturn(List.of(open, missing, commit, compensate));
    doThrow(new IllegalStateException("provider unavailable"))
        .when(provisioning)
        .commit(any(), any());
    new IdentityCreationFinalizationRetry(
            journal,
            provisioning,
            org.mockito.Mockito.mock(IdentityCreationLocalCleanup.class),
            org.mockito.Mockito.mock(IdentityAnonymousBootstrapFailure.class))
        .retry();
    verify(provisioning)
        .commit(argThat(r -> r.attemptId().toString().equals(commit.getId())), any());
    verify(provisioning)
        .compensate(argThat(r -> r.attemptId().toString().equals(compensate.getId())), any());
    verifyNoMoreInteractions(provisioning);
  }

  private static IdentityCreationAttempt row(String status) {
    var row = new IdentityCreationAttempt();
    row.setId(UUID.randomUUID().toString());
    row.setAccountId("new-account");
    row.setCreationProof("own-opaque-proof");
    row.setStatus(status);
    row.setOriginKind("INVITATION");
    row.setRegistrationKind("CONSULTANT");
    row.setTenantId(7L);
    row.setInitialRoles("consultant");
    row.setProvenance("invite:41");
    return row;
  }
}
