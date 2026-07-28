package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.List;
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
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of());

    finalizer.finalizeConsultantAgencyRelation(consultant, relation);

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
    when(consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS))
        .thenReturn(List.of(givenInProgressRelation()));

    finalizer.finalizeConsultantAgencyRelation(consultant, relation);

    assertThat(relation.getStatus()).isEqualTo(ConsultantAgencyStatus.CREATED);
    verify(consultantAgencyRepository).save(relation);
    verify(consultantRepository, never()).save(any());
  }

  private Consultant givenConsultant() {
    var consultant = new Consultant();
    consultant.setId("consultantId");
    consultant.setStatus(ConsultantStatus.IN_PROGRESS);
    return consultant;
  }

  private ConsultantAgency givenInProgressRelation() {
    var relation = new ConsultantAgency();
    relation.setStatus(ConsultantAgencyStatus.IN_PROGRESS);
    return relation;
  }
}
