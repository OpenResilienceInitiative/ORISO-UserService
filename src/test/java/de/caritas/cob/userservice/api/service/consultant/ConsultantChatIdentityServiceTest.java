package de.caritas.cob.userservice.api.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    when(consultantRepository.save(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var repaired = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(repaired.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verify(matrixUserClient)
        .createUserId(eq("anna.beispiel"), eq("s3cret-Pass!"), eq("Anna Beispiel"));
    var saved = ArgumentCaptor.forClass(Consultant.class);
    verify(consultantRepository).save(saved.capture());
    assertThat(saved.getValue().getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
  }

  @Test
  void provisionMissingChatIdentity_Should_doNothing_When_theConsultantAlreadyHasOne() {
    incompleteConsultant.setMatrixUserId("@anna.beispiel:matrix.local");
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));

    var result = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(result.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verifyNoInteractions(matrixUserClient);
    verify(consultantRepository, never()).save(any(Consultant.class));
  }

  @Test
  void provisionMissingChatIdentity_Should_beIdempotent_When_calledTwice() throws Exception {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenReturn("@anna.beispiel:matrix.local");
    when(consultantRepository.save(any(Consultant.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);
    var second = consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID);

    assertThat(second.getMatrixUserId()).isEqualTo("@anna.beispiel:matrix.local");
    verify(matrixUserClient, times(1)).createUserId(anyString(), anyString(), any());
    verify(consultantRepository, times(1)).save(any(Consultant.class));
  }

  @Test
  void provisionMissingChatIdentity_Should_failWithoutTouchingTheRecord_When_matrixThrows()
      throws Exception {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any()))
        .thenThrow(new MatrixCreateUserException("Synapse is down"));

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class);

    assertThat(incompleteConsultant.getMatrixUserId()).isNull();
    verify(consultantRepository, never()).save(any(Consultant.class));
  }

  @Test
  void provisionMissingChatIdentity_Should_fail_When_matrixAnswersWithoutAUserId()
      throws Exception {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(incompleteConsultant));
    when(userHelper.getRandomPassword()).thenReturn("s3cret-Pass!");
    when(matrixUserClient.createUserId(anyString(), anyString(), any())).thenReturn(null);

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(DistributedTransactionException.class);

    verify(consultantRepository, never()).save(any(Consultant.class));
  }

  @Test
  void provisionMissingChatIdentity_Should_answerNotFound_When_theConsultantDoesNotExist() {
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> consultantChatIdentityService.provisionMissingChatIdentity(CONSULTANT_ID))
        .isInstanceOf(NotFoundException.class);
  }
}
