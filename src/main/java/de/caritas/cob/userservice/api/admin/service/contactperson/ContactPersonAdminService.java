package de.caritas.cob.userservice.api.admin.service.contactperson;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactCoverageDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactCoverageRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactPersonAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactPersonAdminDTO.EffectiveSourceEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ContactAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TenantContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TraegerDefaultContactDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantContactPersonDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import de.caritas.cob.userservice.api.model.TenantContactPerson;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.contactperson.CentreContact;
import de.caritas.cob.userservice.api.service.contactperson.ContactEmail;
import de.caritas.cob.userservice.api.service.contactperson.ContactPair;
import de.caritas.cob.userservice.api.service.contactperson.ContactPersonResolver;
import de.caritas.cob.userservice.api.service.contactperson.ContactSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and sets the contact person of a counselling centre and the Träger default
 * (ORISO-Admin#1534).
 *
 * <p>Scope comes from {@link AdminScope}: platform admins everywhere, Träger admins within their
 * Träger, agency admins only for their own centres and never for the Träger default. A foreign
 * target answers 403, the same as a non-existing one. The Träger of a centre is the caller's own;
 * only a platform admin names it (query parameter), else it is looked up in AgencyService.
 */
@Service
@RequiredArgsConstructor
public class ContactPersonAdminService {

  private final @NonNull AdminScope adminScope;
  private final @NonNull ContactPersonResolver resolver;
  private final @NonNull AgencyService agencyService;
  private final @NonNull AgencyContactPersonRepository agencyContactPersonRepository;
  private final @NonNull TenantContactPersonRepository tenantContactPersonRepository;
  private final @NonNull AdminRepository adminRepository;

  @Transactional(readOnly = true)
  public AgencyContactPersonAdminDTO getAgencyContactPerson(Long agencyId, Long tenantId) {
    var centreTenantId = centreTenant(agencyId, tenantId);
    return toDto(resolver.resolveCentre(agencyId, centreTenantId));
  }

  @Transactional
  public AgencyContactPersonAdminDTO updateAgencyContactPerson(
      Long agencyId, Long tenantId, UpdateContactPersonDTO update) {
    var centreTenantId = centreTenant(agencyId, tenantId);
    var contactId = blankToNull(update.getContactAdminId());
    var deputyId = blankToNull(update.getDeputyAdminId());
    assertValidPair(
        contactId, deputyId, admin -> resolver.isCentreCandidate(admin, agencyId, centreTenantId));
    if (contactId == null) {
      if (agencyContactPersonRepository.existsById(agencyId)) {
        agencyContactPersonRepository.deleteById(agencyId);
      }
    } else {
      var row =
          agencyContactPersonRepository
              .findById(agencyId)
              .orElseGet(() -> AgencyContactPerson.builder().agencyId(agencyId).build());
      row.setTenantId(centreTenantId);
      row.setContactAdminId(contactId);
      row.setDeputyAdminId(deputyId);
      agencyContactPersonRepository.save(row);
    }
    return toDto(resolver.resolveCentre(agencyId, centreTenantId));
  }

  @Transactional(readOnly = true)
  public List<ContactAdminDTO> getAgencyContactPersonCandidates(Long agencyId, Long tenantId) {
    var centreTenantId = centreTenant(agencyId, tenantId);
    return resolver.centreCandidates(agencyId, centreTenantId).stream()
        .map(admin -> toDto(admin, agencyId))
        .toList();
  }

  @Transactional(readOnly = true)
  public TenantContactPersonDTO getTenantContactPerson(Long tenantId) {
    adminScope.assertMay(Target.tenant(tenantId));
    return toTenantDto(tenantId);
  }

  @Transactional
  public TenantContactPersonDTO updateTenantContactPerson(
      Long tenantId, UpdateTenantContactPersonDTO update) {
    adminScope.assertMay(Target.tenant(tenantId));
    var contactId = blankToNull(update.getContactAdminId());
    var deputyId = blankToNull(update.getDeputyAdminId());
    var email = ContactEmail.normalize(update.getGeneralContactEmail());
    assertValidPair(contactId, deputyId, admin -> resolver.isTraegerCandidate(admin, tenantId));
    if (contactId == null && email == null) {
      if (tenantContactPersonRepository.existsById(tenantId)) {
        tenantContactPersonRepository.deleteById(tenantId);
      }
    } else {
      var row =
          tenantContactPersonRepository
              .findById(tenantId)
              .orElseGet(() -> TenantContactPerson.builder().tenantId(tenantId).build());
      row.setContactAdminId(contactId);
      row.setDeputyAdminId(deputyId);
      row.setGeneralContactEmail(email);
      tenantContactPersonRepository.save(row);
    }
    return toTenantDto(tenantId);
  }

  @Transactional(readOnly = true)
  public List<ContactAdminDTO> getTenantContactPersonCandidates(Long tenantId) {
    adminScope.assertMay(Target.tenant(tenantId));
    return resolver.traegerCandidates(tenantId).stream().map(admin -> toDto(admin, null)).toList();
  }

  /** Covered means a valid own contact person or Träger default; the admin fallback is not. */
  @Transactional(readOnly = true)
  public List<AgencyContactCoverageDTO> getAgencyContactCoverage(
      AgencyContactCoverageRequestDTO request) {
    var agencyIds =
        request.getAgencyIds() == null
            ? List.<Long>of()
            : request.getAgencyIds().stream().filter(Objects::nonNull).distinct().toList();
    if (agencyIds.isEmpty()) {
      return List.of();
    }
    adminScope.assertMay(Target.agencies(agencyIds));
    var reach = adminScope.current();
    Map<Long, Long> tenantIdByAgencyId = new LinkedHashMap<>();
    if (reach instanceof Platform) {
      var hints =
          request.getTenantIdsByAgency() == null
              ? Map.<String, Long>of()
              : request.getTenantIdsByAgency();
      agencyIds.forEach(id -> tenantIdByAgencyId.put(id, hints.get(String.valueOf(id))));
      var unknown = agencyIds.stream().filter(id -> tenantIdByAgencyId.get(id) == null).toList();
      if (!unknown.isEmpty()) {
        agencyService.getAgenciesWithoutCaching(unknown).stream()
            .filter(agency -> agency.getId() != null)
            .forEach(agency -> tenantIdByAgencyId.put(agency.getId(), agency.getTenantId()));
      }
    } else {
      agencyIds.forEach(id -> tenantIdByAgencyId.put(id, reach.tenantId()));
    }
    var resolved = resolver.resolveCentres(tenantIdByAgencyId);
    return agencyIds.stream()
        .map(
            id -> {
              var source =
                  resolved.containsKey(id) ? resolved.get(id).source() : ContactSource.NONE;
              return new AgencyContactCoverageDTO()
                  .agencyId(id)
                  .covered(source != ContactSource.NONE)
                  .source(AgencyContactCoverageDTO.SourceEnum.valueOf(source.name()));
            })
        .toList();
  }

  /**
   * @throws de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException for a centre
   *     outside the caller's reach
   */
  private Long centreTenant(Long agencyId, Long requestedTenantId) {
    adminScope.assertMay(Target.agencies(List.of(agencyId)));
    var reach = adminScope.current();
    if (!(reach instanceof Platform)) {
      return reach.tenantId();
    }
    if (requestedTenantId != null) {
      return requestedTenantId;
    }
    AgencyDTO agency = agencyService.getAgencyWithoutCaching(agencyId);
    return agency == null ? null : agency.getTenantId();
  }

  private void assertValidPair(String contactId, String deputyId, Predicate<Admin> candidate) {
    if (deputyId != null && contactId == null) {
      throw new BadRequestException("A deputy needs a contact person");
    }
    if (deputyId != null && deputyId.equals(contactId)) {
      throw new BadRequestException("The deputy must differ from the contact person");
    }
    for (var id : new String[] {contactId, deputyId}) {
      if (id != null && adminRepository.findById(id).filter(candidate).isEmpty()) {
        throw new BadRequestException("The admin is no candidate for this contact person");
      }
    }
  }

  private TenantContactPersonDTO toTenantDto(Long tenantId) {
    var traeger = resolver.resolveTraeger(tenantId);
    return new TenantContactPersonDTO()
        .tenantId(tenantId)
        .contact(toDto(traeger.pair().contact(), null))
        .deputy(toDto(traeger.pair().deputy(), null))
        .generalContactEmail(traeger.generalContactEmail());
  }

  private AgencyContactPersonAdminDTO toDto(CentreContact centre) {
    return new AgencyContactPersonAdminDTO()
        .agencyId(centre.agencyId())
        .tenantId(centre.tenantId())
        .contact(toDto(centre.own().contact(), centre.agencyId()))
        .deputy(toDto(centre.own().deputy(), centre.agencyId()))
        .traegerDefault(toDto(centre.traegerDefault(), centre.agencyId()))
        .effectiveSource(EffectiveSourceEnum.valueOf(centre.source().name()));
  }

  private TraegerDefaultContactDTO toDto(ContactPair pair, Long agencyId) {
    return new TraegerDefaultContactDTO()
        .contact(toDto(pair.contact(), agencyId))
        .deputy(toDto(pair.deputy(), agencyId));
  }

  private ContactAdminDTO toDto(Admin admin, Long agencyId) {
    if (admin == null) {
      return null;
    }
    return new ContactAdminDTO()
        .adminId(admin.getId())
        .firstName(admin.getFirstName())
        .lastName(admin.getLastName())
        .loginEmail(admin.getEmail())
        .contactEmail(admin.getContactEmail())
        .displayEmail(ContactPersonResolver.displayEmail(admin))
        .type(typeOf(admin))
        .isConsultant(resolver.activeCounsellor(admin.getId(), agencyId).isPresent());
  }

  private static ContactAdminDTO.TypeEnum typeOf(Admin admin) {
    if (admin.getType() == AdminType.AGENCY) {
      return ContactAdminDTO.TypeEnum.AGENCY;
    }
    return admin.getType() == AdminType.TENANT
        ? ContactAdminDTO.TypeEnum.TENANT
        : ContactAdminDTO.TypeEnum.SUPER;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
