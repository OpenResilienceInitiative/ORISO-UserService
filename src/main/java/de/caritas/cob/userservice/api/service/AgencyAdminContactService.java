package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminContactDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lets a counsellor see whom to contact at their own counselling centre (ORISO-Frontend, OBP 2.1
 * #117). Read-only and deliberately narrow: only centres the caller belongs to, only the people
 * {@code POST /users/chat/internal-subset} can actually invite (agency admin who is also an active
 * counsellor of the centre with a chat identity), and only id plus display name.
 */
@Service
@RequiredArgsConstructor
public class AgencyAdminContactService {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull ConsultantRepository consultantRepository;

  public List<AgencyAdminContactDTO> contactsFor(String callerId, Long agencyId) {
    if (callerId == null
        || agencyId == null
        || !consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            callerId, agencyId)) {
      // The same answer for a foreign and for a non-existing centre.
      throw new ForbiddenException("The caller is not a counsellor of this counselling centre");
    }
    return adminAgencyRepository.findAgencyAdminIdsByAgencyId(agencyId).stream()
        .filter(adminId -> !adminId.equals(callerId))
        .map(consultantRepository::findByIdAndDeleteDateIsNull)
        .flatMap(java.util.Optional::stream)
        .filter(consultant -> consultant.isInAgency(agencyId))
        .filter(consultant -> MatrixIds.isUserId(consultant.getMatrixUserId()))
        .map(AgencyAdminContactService::toContact)
        .toList();
  }

  private static AgencyAdminContactDTO toContact(Consultant consultant) {
    var internal = consultant.getInternalDisplayNameOrFallback();
    return new AgencyAdminContactDTO()
        .consultantId(consultant.getId())
        .displayName(internal != null && !internal.isBlank() ? internal : consultant.getFullName());
  }
}
