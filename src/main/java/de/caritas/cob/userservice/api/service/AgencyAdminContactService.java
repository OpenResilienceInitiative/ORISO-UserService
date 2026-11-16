package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminContactDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactDTO.SourceEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactPersonDTO.RoleEnum;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.MatrixIds;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.contactperson.ContactPair;
import de.caritas.cob.userservice.api.service.contactperson.ContactPersonResolver;
import de.caritas.cob.userservice.api.service.contactperson.ContactSource;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Lets a counsellor see whom to contact at their own counselling centre (ORISO-Frontend, OBP 2.1
 * #117). Read-only and deliberately narrow: only centres the caller belongs to, only the people
 * {@code POST /users/chat/internal-subset} can actually invite (agency admin who is also an active
 * counsellor of the centre with a chat identity), and only id plus display name.
 *
 * <p>{@link #contactPersonFor} answers the contact person of the centre (ORISO-Admin#1534).
 */
@Service
@RequiredArgsConstructor
public class AgencyAdminContactService {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ContactPersonResolver contactPersonResolver;

  public List<AgencyAdminContactDTO> contactsFor(String callerId, Long agencyId) {
    assertCallerBelongsTo(callerId, agencyId);
    return adminAgencyRepository.findAgencyAdminIdsByAgencyId(agencyId).stream()
        .filter(adminId -> !adminId.equals(callerId))
        .map(consultantRepository::findByIdAndDeleteDateIsNull)
        .flatMap(java.util.Optional::stream)
        .filter(consultant -> consultant.isInAgency(agencyId))
        .filter(consultant -> MatrixIds.isUserId(consultant.getMatrixUserId()))
        .map(AgencyAdminContactService::toContact)
        .toList();
  }

  /**
   * Whom the counsellor contacts at their centre: the centre's own pair, else the Träger default,
   * else every agency admin of the centre, else every Träger admin; with nobody left the frontend
   * shows the platform support e-mail.
   */
  public ConsultantAgencyContactDTO contactPersonFor(String callerId, Long agencyId) {
    assertCallerBelongsTo(callerId, agencyId);
    // A counsellor's centres all belong to the counsellor's own Träger.
    Long tenantId =
        consultantRepository
            .findByIdAndDeleteDateIsNull(callerId)
            .map(Consultant::getTenantId)
            .orElse(null);
    var centre = contactPersonResolver.resolveCentre(agencyId, tenantId);
    var result = new ConsultantAgencyContactDTO().traegerContactEmail(centre.traegerContactEmail());
    var people = new ArrayList<ConsultantAgencyContactPersonDTO>();
    switch (centre.source()) {
      case OWN, TRAEGER_DEFAULT -> {
        result.source(
            centre.source() == ContactSource.OWN ? SourceEnum.OWN : SourceEnum.TRAEGER_DEFAULT);
        addPair(people, centre.effective(), callerId, agencyId);
      }
      case NONE -> {
        var agencyAdmins = contactPersonResolver.agencyAdminsOf(agencyId);
        var admins =
            agencyAdmins.isEmpty() ? contactPersonResolver.traegerAdminsOf(tenantId) : agencyAdmins;
        result.source(
            !agencyAdmins.isEmpty()
                ? SourceEnum.FALLBACK_AGENCY_ADMINS
                : admins.isEmpty() ? SourceEnum.SUPPORT_EMAIL : SourceEnum.FALLBACK_TENANT_ADMINS);
        admins.forEach(admin -> people.add(person(admin, RoleEnum.ADMIN, callerId, agencyId)));
      }
    }
    return result.people(people);
  }

  private void addPair(
      List<ConsultantAgencyContactPersonDTO> people,
      ContactPair pair,
      String callerId,
      Long agencyId) {
    people.add(person(pair.contact(), RoleEnum.CONTACT, callerId, agencyId));
    if (pair.deputy() != null) {
      people.add(person(pair.deputy(), RoleEnum.DEPUTY, callerId, agencyId));
    }
  }

  private ConsultantAgencyContactPersonDTO person(
      Admin admin, RoleEnum role, String callerId, Long agencyId) {
    var counsellor = contactPersonResolver.activeCounsellor(admin.getId(), agencyId);
    // The caller stays visible as contact, but never as the one to open a chat with.
    var chatConsultantId =
        counsellor
            .filter(consultant -> MatrixIds.isUserId(consultant.getMatrixUserId()))
            .map(Consultant::getId)
            .filter(id -> !id.equals(callerId))
            .orElse(null);
    return new ConsultantAgencyContactPersonDTO()
        .role(role)
        .displayName(ContactPersonResolver.displayName(admin))
        .email(ContactPersonResolver.displayEmail(admin))
        .isConsultant(counsellor.isPresent())
        .chatConsultantId(chatConsultantId);
  }

  private void assertCallerBelongsTo(String callerId, Long agencyId) {
    if (callerId == null
        || agencyId == null
        || !consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            callerId, agencyId)) {
      // The same answer for a foreign and for a non-existing centre.
      throw new ForbiddenException("The caller is not a counsellor of this counselling centre");
    }
  }

  private static AgencyAdminContactDTO toContact(Consultant consultant) {
    var internal = consultant.getInternalDisplayNameOrFallback();
    return new AgencyAdminContactDTO()
        .consultantId(consultant.getId())
        .displayName(internal != null && !internal.isBlank() ? internal : consultant.getFullName());
  }
}
