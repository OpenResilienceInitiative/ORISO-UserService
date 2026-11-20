package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantAgencyRelationFinalizerTest {

  @InjectMocks private ConsultantAgencyRelationFinalizer finalizer;

  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;

  @Test
  void finalizePersistedRelationMarksRelationAndConsultantCreated() {
    var consultant = givenConsultant();
    var relation = givenInProgressRelation();
    when(consultantAgencyRepository.findById(relation.getId())).thenReturn(Optional.of(relation));
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of());

    finalizer.finalizeConsultantAgencyRelation(consultant, new AgencyDTO().id(1L), relation);

    assertThat(relation.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    verify(consultantAgencyRepository).save(relation);
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
    verify(consultantRepository).save(consultant);
  }

  @Test
  void finalizeByAgencyResolvesAndFinalizesRelation() {
    var consultant = givenConsultant();
    var agency = new AgencyDTO().id(1L);
    var relation = givenInProgressRelation();
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), agency.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(relation);
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of());

    finalizer.finalizeConsultantAgencyRelation(consultant, agency);

    assertThat(relation.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    verify(consultantAgencyRepository).save(relation);
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
  }

  @Test
  void finalizeByAgencySkipsWhenNoInProgressRelationExists() {
    var consultant = givenConsultant();
    var agency = new AgencyDTO().id(1L);
    when(consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), agency.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(null);

    finalizer.finalizeConsultantAgencyRelation(consultant, agency);

    verify(consultantAgencyRepository, never()).save(any());
    verify(consultantRepository, never()).save(any());
  }

  @Test
  void finalizeRelationKeepsConsultantInProgressWhileAnotherRelationIsInProgress() {
    var consultant = givenConsultant();
    var relation = givenInProgressRelation();
    when(consultantAgencyRepository.findById(relation.getId())).thenReturn(Optional.of(relation));
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of(givenInProgressRelation()));

    finalizer.finalizeConsultantAgencyRelation(consultant, new AgencyDTO().id(1L), relation);

    assertThat(relation.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    verify(consultantAgencyRepository).save(relation);
    verify(consultantRepository, never()).save(any());
  }

  @Test
  void finalizingATeamAgencyUpdatesTeamFlagWithTheLiveRelation() {
    var consultant = givenConsultant();
    var agency = new AgencyDTO().id(1L).teamAgency(true);
    var relation = givenInProgressRelation();
    when(consultantAgencyRepository.findById(relation.getId())).thenReturn(Optional.of(relation));

    assertThat(finalizer.finalizeConsultantAgencyRelation(consultant, agency, relation)).isTrue();

    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.CREATED);
    assertThat(consultant.isTeamConsultant()).isTrue();
    verify(consultantRepository).save(consultant);
  }

  @Test
  void staleFinalizationCannotRestoreASoftDeletedRelation() {
    var consultant = givenConsultant();
    var earlier = givenInProgressRelation();
    var deleted = givenInProgressRelation();
    deleted.setDeleteDate(LocalDateTime.of(2026, 11, 18, 12, 0));
    when(consultantAgencyRepository.findById(earlier.getId())).thenReturn(Optional.of(deleted));

    var completed =
        finalizer.finalizeConsultantAgencyRelation(
            consultant, new AgencyDTO().id(1L).teamAgency(true), earlier);

    assertThat(completed).isFalse();
    assertThat(consultant.getStatus()).isEqualTo(ConsultantStatus.IN_PROGRESS);
    assertThat(consultant.isTeamConsultant()).isFalse();

    var order = inOrder(consultantRepository, consultantAgencyRepository);
    order.verify(consultantRepository).findFreshActiveByIdForUpdate(consultant.getId());
    order.verify(consultantAgencyRepository).findById(earlier.getId());
    verify(consultantAgencyRepository, never()).save(any());
    verify(consultantRepository, never()).save(any());
  }

  private Consultant givenConsultant() {
    var consultant = new Consultant();
    consultant.setId("consultantId");
    consultant.setStatus(ConsultantStatus.IN_PROGRESS);
    when(consultantRepository.findFreshActiveByIdForUpdate(consultant.getId()))
        .thenReturn(Optional.of(consultant));
    return consultant;
  }

  private ConsultantAgency givenInProgressRelation() {
    var relation = new ConsultantAgency();
    relation.setId(41L);
    relation.setStatus(ConsultantAgencyStatus.IN_PROGRESS);
    return relation;
  }
}
