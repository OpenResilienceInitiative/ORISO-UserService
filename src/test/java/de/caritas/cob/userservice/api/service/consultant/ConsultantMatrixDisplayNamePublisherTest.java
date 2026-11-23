package de.caritas.cob.userservice.api.service.consultant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.helper.ConsultantDisplayNameResolver;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** ORISO-UserService#1541: the self-service PATCH rename reaches Matrix like the PUT path. */
@ExtendWith(MockitoExtension.class)
class ConsultantMatrixDisplayNamePublisherTest {

  private static final String MATRIX_USER_ID = "@beraterin1:matrix.example.org";

  @InjectMocks private ConsultantMatrixDisplayNamePublisher publisher;

  @Mock private MatrixUserClient matrixUserClient;

  // The real rule: it alone decides which name may reach Matrix (ADR-002 §2).
  @Spy
  private ConsultantDisplayNameResolver consultantDisplayNameResolver =
      new ConsultantDisplayNameResolver();

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void publishAfterCommit_Should_pushThePublicDisplayName_When_noTransactionIsActive() {
    publisher.publishAfterCommit(consultant("Frau Neu."));

    verify(matrixUserClient).updateUserDisplayName(MATRIX_USER_ID, "Frau Neu.");
  }

  @Test
  void publishAfterCommit_Should_fallBackToTheUsername_neverTheRealName() {
    publisher.publishAfterCommit(consultant(null));

    verify(matrixUserClient).updateUserDisplayName(MATRIX_USER_ID, "beraterin1");
  }

  @Test
  void publishAfterCommit_Should_waitForTheCommit_When_aTransactionIsActive() {
    TransactionSynchronizationManager.initSynchronization();
    var consultant = consultant("Frau Neu.");

    publisher.publishAfterCommit(consultant);
    // Resolved at call time: a later change to the entity must not leak into the push.
    consultant.setDisplayName("Something else");

    verifyNoInteractions(matrixUserClient);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(matrixUserClient).updateUserDisplayName(MATRIX_USER_ID, "Frau Neu.");
  }

  @Test
  void publishAfterCommit_Should_sendNothing_When_theConsultantHasNoMatrixAccount() {
    var consultant = consultant("Frau Neu.");
    consultant.setMatrixUserId(null);

    publisher.publishAfterCommit(consultant);
    publisher.publishAfterCommit(null);

    verify(matrixUserClient, never()).updateUserDisplayName(anyString(), any());
  }

  @Test
  void publishAfterCommit_Should_notFail_When_matrixRefusesOrIsDown() {
    when(matrixUserClient.updateUserDisplayName(anyString(), any()))
        .thenReturn(false)
        .thenThrow(new IllegalStateException("matrix down"));

    assertThatCode(
            () -> {
              publisher.publishAfterCommit(consultant("Frau Neu."));
              publisher.publishAfterCommit(consultant("Frau Neu."));
            })
        .doesNotThrowAnyException();
  }

  private static Consultant consultant(String displayName) {
    var consultant = new Consultant();
    consultant.setId("counsellor-1");
    consultant.setUsername("beraterin1");
    consultant.setFirstName("Angela");
    consultant.setLastName("Musterfrau");
    consultant.setDisplayName(displayName);
    consultant.setMatrixUserId(MATRIX_USER_ID);
    return consultant;
  }
}
