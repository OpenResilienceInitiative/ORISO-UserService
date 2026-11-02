package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantHardDeleteClaimServiceTest {

  @Mock private ConsultantRepository consultantRepository;
  @Mock private DeletionLifecycleService deletionLifecycleService;

  private ConsultantHardDeleteClaimService claimService;

  @BeforeEach
  void setUp() {
    claimService =
        new ConsultantHardDeleteClaimService(consultantRepository, deletionLifecycleService);
  }

  @Test
  void claimLocksNormalizesAndTransitionsOneReadyRowBeforeReturningIt() {
    Consultant consultant = deletedConsultant();
    when(consultantRepository.findPictureOwnerForUpdate("consultant-1"))
        .thenReturn(Optional.of(consultant));
    when(deletionLifecycleService.normalizeConsultantLifecycle(consultant)).thenReturn(consultant);
    when(deletionLifecycleService.isReadyForHardDelete(consultant)).thenReturn(true);
    when(consultantRepository.save(consultant)).thenReturn(consultant);

    Consultant claimed = claimService.claim("consultant-1").orElseThrow();

    assertSame(consultant, claimed);
    assertEquals(
        DeletionLifecycleState.HARD_DELETE_IN_PROGRESS, claimed.getDeletionLifecycleState());
  }

  @Test
  void claimReturnsEmptyWithoutLifecycleMutationWhenCancellationAlreadyClearedDeleteDate() {
    Consultant active = deletedConsultant();
    active.setDeleteDate(null);
    when(consultantRepository.findPictureOwnerForUpdate("consultant-1"))
        .thenReturn(Optional.of(active));

    assertEquals(Optional.empty(), claimService.claim("consultant-1"));
    verifyNoInteractions(deletionLifecycleService);
  }

  @Test
  void claimPersistsNormalizationButDoesNotClaimAnUnreadyRow() {
    Consultant consultant = deletedConsultant();
    when(consultantRepository.findPictureOwnerForUpdate("consultant-1"))
        .thenReturn(Optional.of(consultant));
    when(deletionLifecycleService.normalizeConsultantLifecycle(consultant)).thenReturn(consultant);
    when(deletionLifecycleService.isReadyForHardDelete(consultant)).thenReturn(false);

    assertEquals(Optional.empty(), claimService.claim("consultant-1"));
    verify(consultantRepository).save(consultant);
  }

  @Test
  void releaseMovesAnInProgressRetainedRowToNonReactivatablePartialFailureState() {
    claimService.release("consultant-1");

    verify(consultantRepository)
        .releaseConsultantHardDeleteClaim(
            "consultant-1",
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
  }

  @Test
  void claimRetriesPartialHardDeleteWithoutMakingItReactivationEligible() {
    Consultant consultant = deletedConsultant();
    consultant.setDeletionLifecycleState(DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE);
    when(consultantRepository.findPictureOwnerForUpdate("consultant-1"))
        .thenReturn(Optional.of(consultant));
    when(deletionLifecycleService.normalizeConsultantLifecycle(consultant)).thenReturn(consultant);
    when(consultantRepository.save(consultant)).thenReturn(consultant);

    Consultant claimed = claimService.claim("consultant-1").orElseThrow();

    assertEquals(
        DeletionLifecycleState.HARD_DELETE_IN_PROGRESS, claimed.getDeletionLifecycleState());
    verify(consultantRepository).save(consultant);
  }

  @Test
  void releaseInterruptedClaimsRecoversOnlyAfterSchedulerOwnsGlobalLease() {
    when(consultantRepository.releaseInterruptedConsultantHardDeleteClaims(
            DeletionLifecycleState.HARD_DELETE_IN_PROGRESS,
            DeletionLifecycleState.HARD_DELETE_PARTIAL_FAILURE))
        .thenReturn(2);

    assertEquals(2, claimService.releaseInterruptedClaims());
  }

  private Consultant deletedConsultant() {
    var consultant = new Consultant();
    consultant.setId("consultant-1");
    consultant.setDeleteDate(LocalDateTime.now());
    consultant.setDeletionLifecycleState(DeletionLifecycleState.READ_ONLY_SAFEGUARD);
    return consultant;
  }
}
