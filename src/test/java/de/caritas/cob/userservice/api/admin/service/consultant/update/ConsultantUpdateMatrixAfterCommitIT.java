package de.caritas.cob.userservice.api.admin.service.consultant.update;

import static de.caritas.cob.userservice.api.helper.MatrixRealNameGuard.assertNoRealNameReachedMatrix;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.UserServiceApplication;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The Matrix display-name push must survive the transaction boundary honestly (CodeRabbit on PR
 * #1202).
 *
 * <p>{@code updateConsultant} is {@code @Transactional}, and the rename-notification work that runs
 * after the database write is not contained: its session lookup and its {@code save} can throw,
 * rolling the consultant update back. A push issued inside the transaction would then leave Synapse
 * holding a name the database never kept — not a real-name exposure (both values are pseudonyms)
 * but a persistent drift that nothing reconciles.
 *
 * <p>These run against the real transactional proxy, so the commit boundary is the real one rather
 * than a mocked stand-in. The unit tests in {@link ConsultantUpdateServiceTest} cover the value
 * that is pushed; this covers <em>when</em> it is pushed.
 */
@SpringBootTest(classes = UserServiceApplication.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class ConsultantUpdateMatrixAfterCommitIT extends ConsultantUpdateServiceBase {

  private static final String CONSULTANT_ID = "5674839f-d0a3-47e2-8f9c-bb49fc2ddbbe";
  private static final String MATRIX_USER_ID = "@eTNtviLNaJnfHE7CZ:matrix.example";
  private static final String STORED_FIRST_NAME = "Multiple";

  @MockitoBean private MatrixSynapseService matrixSynapseService;

  /**
   * Stands in for the rename-notification path. The session lookup is the first uncontained call
   * after the database write, so making it throw reproduces exactly the rollback CodeRabbit named.
   */
  @MockitoBean private SessionRepository sessionRepository;

  @Autowired private ConsultantRepository consultantRepository;

  @BeforeEach
  void restoreStoredName() {
    // Earlier tests in this class commit a rename; put the row back so each case starts equal.
    consultantRepository
        .findById(CONSULTANT_ID)
        .ifPresent(
            consultant -> {
              consultant.setFirstName(STORED_FIRST_NAME);
              consultantRepository.save(consultant);
            });
  }

  @Test
  @DisplayName("a rolled-back update never reaches Matrix")
  void updateConsultant_Should_NotTouchMatrix_When_TheTransactionRollsBack() {
    when(sessionRepository.findByConsultantAndStatusIn(any(Consultant.class), any()))
        .thenThrow(new IllegalStateException("notification lookup exploded"));

    assertThatThrownBy(() -> consultantUpdateService.updateConsultant(CONSULTANT_ID, rename()))
        .isInstanceOf(IllegalStateException.class);

    verify(matrixSynapseService, never()).updateUserDisplayName(anyString(), anyString());
    assertThat(storedFirstName())
        .as("the consultant update must have been rolled back")
        .isEqualTo(STORED_FIRST_NAME);
  }

  @Test
  @DisplayName("a committed update does reach Matrix, with the resolved pseudonym")
  void updateConsultant_Should_PushAfterCommit_When_TheTransactionSucceeds() {
    consultantUpdateService.updateConsultant(CONSULTANT_ID, rename());

    // No public display name on this row, so the resolver falls back to the decoded username —
    // never "Angela Musterfrau" (ADR-002 §2, #1200).
    verify(matrixSynapseService).updateUserDisplayName(eq(MATRIX_USER_ID), anyString());
    assertNoRealNameReachedMatrix(matrixSynapseService, "Angela", "Musterfrau");
    assertThat(storedFirstName()).isEqualTo("Angela");
  }

  @Test
  @DisplayName("a Synapse outage after the commit does not fail or undo the update")
  void updateConsultant_Should_StayNonBlocking_When_MatrixFailsAfterCommit() {
    when(matrixSynapseService.updateUserDisplayName(anyString(), anyString()))
        .thenThrow(new IllegalStateException("synapse down"));

    assertThatCode(() -> consultantUpdateService.updateConsultant(CONSULTANT_ID, rename()))
        .doesNotThrowAnyException();

    assertThat(storedFirstName()).isEqualTo("Angela");
  }

  private String storedFirstName() {
    return consultantRepository.findById(CONSULTANT_ID).map(Consultant::getFirstName).orElseThrow();
  }

  private UpdateAdminConsultantDTO rename() {
    var update = new UpdateAdminConsultantDTO();
    update.setAbsent(false);
    update.setFirstname("Angela");
    update.setLastname("Musterfrau");
    update.setEmail("multiple@consultant.de");
    update.formalLanguage(true);
    return update;
  }
}
