package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantAgencyRelationFinalizer {

  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;

  /** Finalizes a relation resolved by consultant and agency. */
  @Transactional
  public void finalizeConsultantAgencyRelation(Consultant consultant, AgencyDTO agency) {
    updateConsultantStatus(consultant, agency);
  }

  /** Finalizes a relation that the caller has just persisted without an immediate re-query. */
  @Transactional
  public void finalizeConsultantAgencyRelation(
      Consultant consultant, ConsultantAgency persistedRelation) {
    updateConsultantStatus(consultant, persistedRelation);
  }

  private void updateConsultantStatus(Consultant consultant, AgencyDTO agencyDTO) {
    ConsultantAgency consultantAgency =
        consultantAgencyRepository.findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), agencyDTO.getId(), ConsultantAgencyStatus.IN_PROGRESS);

    if (consultantAgency == null) {
      log.warn(
          "No IN_PROGRESS consultant_agency relation visible for consultant {} and agency {};"
              + " skipping status finalization",
          consultant.getId(),
          agencyDTO.getId());
      return;
    }

    updateConsultantStatus(consultant, consultantAgency);
  }

  private void updateConsultantStatus(Consultant consultant, ConsultantAgency consultantAgency) {
    consultantAgency.setStatus(ConsultantAgencyStatus.CREATED);
    consultantAgencyRepository.save(consultantAgency);
    List<ConsultantAgency> consultantAgencies =
        consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS);
    if (consultantAgencies.size() == 0) {
      consultant.setStatus(ConsultantStatus.CREATED);
      consultantRepository.save(consultant);
    }
  }
}
