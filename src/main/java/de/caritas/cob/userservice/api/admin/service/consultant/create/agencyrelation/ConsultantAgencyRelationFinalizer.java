package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
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
  public boolean finalizeConsultantAgencyRelation(Consultant consultant, AgencyDTO agency) {
    return consultantRepository
        .findFreshActiveByIdForUpdate(consultant.getId())
        .map(
            current ->
                updateConsultantStatus(
                    current,
                    agency,
                    consultantAgencyRepository
                        .findByConsultantIdAndAgencyIdAndStatusAndDeleteDateIsNull(
                            consultant.getId(),
                            agency.getId(),
                            ConsultantAgencyStatus.IN_PROGRESS)))
        .orElse(false);
  }

  /** Finalizes the exact persisted relation if it is still active. */
  @Transactional
  public boolean finalizeConsultantAgencyRelation(
      Consultant consultant, AgencyDTO agency, ConsultantAgency persistedRelation) {
    return consultantRepository
        .findFreshActiveByIdForUpdate(consultant.getId())
        .flatMap(
            current ->
                consultantAgencyRepository
                    .findById(persistedRelation.getId())
                    .filter(relation -> relation.getDeleteDate() == null)
                    .filter(relation -> relation.getStatus() == ConsultantAgencyStatus.IN_PROGRESS)
                    .map(relation -> updateConsultantStatus(current, agency, relation)))
        .orElse(false);
  }

  private boolean updateConsultantStatus(
      Consultant consultant, AgencyDTO agency, ConsultantAgency relation) {
    if (relation == null) {
      log.warn(
          "No active relation to finalize for consultant {} and agency {}",
          consultant.getId(),
          agency.getId());
      return false;
    }
    relation.setStatus(ConsultantAgencyStatus.CREATED);
    consultantAgencyRepository.save(relation);

    var pending =
        consultantAgencyRepository.findByConsultantIdAndStatusAndDeleteDateIsNull(
            consultant.getId(), ConsultantAgencyStatus.IN_PROGRESS);
    var status = pending.isEmpty() ? ConsultantStatus.CREATED : ConsultantStatus.IN_PROGRESS;
    var teamConsultant =
        consultant.isTeamConsultant() || Boolean.TRUE.equals(agency.getTeamAgency());
    if (consultant.getStatus() != status || consultant.isTeamConsultant() != teamConsultant) {
      consultant.setStatus(status);
      consultant.setTeamConsultant(teamConsultant);
      consultantRepository.save(consultant);
    }
    return true;
  }
}
