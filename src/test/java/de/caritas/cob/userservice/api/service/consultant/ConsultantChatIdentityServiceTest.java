package de.caritas.cob.userservice.api.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.DistributedTransactionException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.matrix.MatrixCreateUserException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.MatrixUserClient;
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
 * The repair path for a consultant that {@code CreateConsultantSaga} persisted without a chat
 * (Matrix) identity because the chat server was unreachable (#1194).
 */
@ExtendWith(MockitoExtension.class)
class ConsultantChatIdentityServiceTest {

  private static final String CONSULTANT_ID = "3b0b5d59-1a19-4e5e-9f0e-9a2f1a2b3c4d";

  @InjectMocks private ConsultantChatIdentityService consultantChatIdentityService;

  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantChatIdentityWriter consultantChatIdentityWriter;
  @Mock private MatrixUserClient matrixUserClient;
  @Mock private UserHelper userHelper;

  private Consultant incompleteConsultant;

  @BeforeEach
  void setUp() {
    incompleteConsultant = new Consultant();
    incompleteConsultant.setId(CONSULTANT_ID);
    incompleteConsultant.setUsername(new UsernameTranscoder().encodeUsername("anna.beispiel"));
    incompleteConsultant.setFirstName("Anna");
    incompleteConsultant.setLastName("Beispiel");
    incompleteConsultant.setMatrixUserId(null);
  }

  @Test
  void hasChatIdentity_Should_beFalse_When_matrixUserIdIsNullOrBlank() {
    assertThat(ConsultantChatIdentityService.hasChatIdentity(incompleteConsultant)).isFalse();

    incompleteConsultant.setMatrixUserId("   ");
    assertThat(ConsultantChatIdentityService.hasChatIdentity(incompleteConsultant)).isFalse();
  }

  @Test
  void hasChatIdentity_Should_beTrue_When_matrixUserIdIsPresent() {
    incompleteConsultant.setMatrixUserId("@anna.beispiel:matrix.local");

    assertThat(ConsultantChatIdentityService.hasChatIdentity(incompleteConsultant)).isTrue();
  }

  @Test
  void missingChatIdentityMessage_Should_nameTheCauseTheRecordAndTheRepair() {
    var message =
        ConsultantChatIdentityService.missingChatIdentityMessage("Consultant", CONSULTANT_ID);

    assertThat(message).contains("Consultant");
    assertThat(message).contains(CONSULTANT_ID);
    assertThat(message).contains("no chat identity");
    assertThat(message).contains("/useradmin/consultants/" + CONSULTANT_ID + "/chat-identity");
  }

  @Test
  void findConsultantsWithoutChatIdentity_Should_returnWhatTheRepositoryReports() {
    when(consultantRepository.findWithoutChatIdentity()).thenReturn(List.of(incompleteConsultant));

    assertThat(consultantChatIdentityService.findConsultantsWithoutChatIdentity())
        .containsExactly(incompleteConsultant);
  }

  @Test
  void provisionMissingChatIdentity_Should_createAndPersistTheMatrixAccount() throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    when(consultantChatIdentityWriter.attachChatIdentity(eq(CONSULTANT_ID), anyString()))
        .thenAnswer(
            invocation -> {
              incompleteConsultant.setMatrixUserId(invocation.getArgument(1));
              return incompleteConsultant;
            });

    var repaired = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(repaired.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verify(matrixUserClient)
        .createUserId(eq("anna.beispiel"), eq("s3cret-Pass!"), eq("Anna Beispiel"));
    var saved = ArgumentCaptor.forClass(String.class);
    verify(consultantChatIdentityWriter).attachChatIdentity(eq(CONSULTANT_ID), saved.capture());
    assertThat(saved.getValue()).isEqualTo("@anna.beispiel:matrix.local");
  }

  @Test
  void provisionMissingChatIdentity_Should_doNothing_When_theConsultantAlreadyHasOne() {
    incompleteConsultant.setMatrixUserId("@anna.beispiel:matrix.local");
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));

    var result = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(result.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verifyNoInteractions(matrixUserClient);
    verify(consultantChatIdentityWriter, never()).attachChatIdentity(anyString(), anyString());
  }

  @Test
  void provisionMissingChatIdentity_Should_beIdempotent_When_calledTwice() throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    when(consultantChatIdentityWriter.attachChatIdentity(eq(CONSULTANT_ID), anyString()))
        .thenAnswer(
            invocation -> {
              incompleteConsultant.setMatrixUserId(invocation.getArgument(1));
              return incompleteConsultant;
            });

    consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);
    var second = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(second.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verify(matrixUserClient, times(1)).createUserId(anyString(), anyString(), any());
    verify(consultantChatIdentityWriter, times(1)).attachChatIdentity(anyString(), anyString());
  }

  @Test
  void provisionMissingChatIdentity_Should_failWithoutTouchingTheRecord_When_matrixThrows()
      throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenThrow(new MatrixCreateUserException("Synapse is down"));

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class);

    assertThat(incompleteConsultant.getMatrixUserId()).isNull();
    verify(consultantChatIdentityWriter, never()).attachChatIdentity(anyString(), anyString());
  }

  @Test
  void provisionMissingChatIdentity_Should_fail_When_matrixAnswersWithoutAUserId()
      throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any())).thenReturn(null);

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class);

    verify(consultantChatIdentityWriter, never()).attachChatIdentity(anyString(), anyString());
  }

  /**
   * The interleaving the repair was written to cure, and must not reproduce: Matrix succeeds, the
   * database write then fails. The chat account now exists, so a plain retry can only ever get
   * M_USER_IN_USE back — unless the retry adopts what is already there. If it cannot, the repair
   * tool produces records that no repair can finish.
   */
  @Test
  void
      provisionMissingChatIdentity_Should_stillFinishTheJob_When_aPriorAttemptFailedAfterMatrixSucceeded()
          throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");

    // first attempt: Matrix provisions the account, the database write then fails
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    doThrow(new RuntimeException("commit failed"))
        .when(consultantChatIdentityWriter)
        .attachChatIdentity(eq(CONSULTANT_ID), anyString());

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class)
        .extracting(
            failure ->
                ((DistributedTransactionException) failure)
                    .getCustomHttpHeaders()
                    .getFirst("X-Reason"))
        .isEqualTo("DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_SAVE_CONSULTANT_IN_MARIADB");
    assertThat(incompleteConsultant.getMatrixUserId()).isNull();

    // second attempt: Matrix now refuses to mint the same user, the existing one is adopted
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenThrow(new MatrixCreateUserException("Matrix user (anna.beispiel) is already active"));
    when(matrixUserClient.findUserId("anna.beispiel")).thenReturn("@anna.beispiel:matrix.local");
    doAnswer(
            invocation -> {
              incompleteConsultant.setMatrixUserId(invocation.getArgument(1));
              return incompleteConsultant;
            })
        .when(consultantChatIdentityWriter)
        .attachChatIdentity(CONSULTANT_ID, "@anna.beispiel:matrix.local");

    var repaired = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(repaired.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
  }

  @Test
  void provisionMissingChatIdentity_Should_reportTheDatabaseStep_When_theWriteFails()
      throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    doThrow(new RuntimeException("commit failed"))
        .when(consultantChatIdentityWriter)
        .attachChatIdentity(eq(CONSULTANT_ID), anyString());

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class)
        .extracting(
            failure ->
                ((DistributedTransactionException) failure)
                    .getCustomHttpHeaders()
                    .getFirst("X-Reason"))
        .isEqualTo("DISTRIBUTED_TRANSACTION_FAILED_ON_STEP_SAVE_CONSULTANT_IN_MARIADB");
  }

  @Test
  void provisionMissingChatIdentity_Should_stillFail_When_matrixThrowsAndNoAccountExists()
      throws Exception {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenThrow(new MatrixCreateUserException("Synapse is down"));
    when(matrixUserClient.findUserId("anna.beispiel")).thenReturn(null);

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class);

    verify(consultantChatIdentityWriter, never()).attachChatIdentity(anyString(), anyString());
  }

  @Test
  void provisionMissingChatIdentity_Should_neverCallMatrixInsideADatabaseTransaction() {
    // The repair must not be @Transactional: a Synapse call inside a database transaction is
    // exactly the defect this service exists to avoid (#1194 / CodeRabbit).
    var method =
        java.util.Arrays.stream(ConsultantChatIdentityService.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("provisionMissingChatIdentity"))
            .findFirst()
            .orElseThrow();

    assertThat(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
        .isNull();
    assertThat(
            ConsultantChatIdentityService.class.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class))
        .isNull();
  }

  @Test
  void provisionMissingChatIdentity_Should_answerNotFound_When_theConsultantDoesNotExist() {
    when(consultantChatIdentityWriter.find(CONSULTANT_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(NotFoundException.class);
  }
}
