package de.caritas.cob.userservice.api.service.contactperson;

import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency.AdminAgencyBase;
import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.TenantContactPerson;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Who counts as contact person of a counselling centre (ORISO-Admin#1534).
 *
 * <p>Validity is checked whenever a pair is read, so an id that no longer names a fitting admin
 * counts as not set, even if a cleanup was missed (decision 10):
 *
 * <ul>
 *   <li>a centre's own contact person is an agency admin of that centre or a Träger admin of its
 *       Träger;
 *   <li>the Träger default is a Träger admin or agency admin of that Träger;
 *   <li>a deputy needs a valid contact person and differs from it.
 * </ul>
 *
 * Platform admins are never contact persons.
 */
@Component
@RequiredArgsConstructor
public class ContactPersonResolver {

  private static final Comparator<Admin> BY_NAME =
      Comparator.comparing((Admin a) -> nullToEmpty(a.getLastName()), String.CASE_INSENSITIVE_ORDER)
          .thenComparing(a -> nullToEmpty(a.getFirstName()), String.CASE_INSENSITIVE_ORDER)
          .thenComparing(Admin::getId);

  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AgencyContactPersonRepository agencyContactPersonRepository;
  private final @NonNull TenantContactPersonRepository tenantContactPersonRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;

  public CentreContact resolveCentre(Long agencyId, Long tenantId) {
    var request = new HashMap<Long, Long>();
    request.put(agencyId, tenantId);
    return resolveCentres(request).get(agencyId);
  }

  /**
   * @param tenantIdByAgencyId the Träger of each centre; a {@code null} Träger has no default
   * @return one entry per requested centre, with a fixed number of queries for all of them
   */
  public Map<Long, CentreContact> resolveCentres(Map<Long, Long> tenantIdByAgencyId) {
    if (tenantIdByAgencyId.isEmpty()) {
      return Map.of();
    }
    Map<Long, AgencyContactPerson> centreRows =
        agencyContactPersonRepository.findByAgencyIdIn(tenantIdByAgencyId.keySet()).stream()
            .collect(Collectors.toMap(AgencyContactPerson::getAgencyId, Function.identity()));
    Set<Long> tenantIds =
        tenantIdByAgencyId.values().stream().filter(Objects::nonNull).collect(Collectors.toSet());
    Map<Long, TenantContactPerson> traegerRows =
        tenantIds.isEmpty()
            ? Map.of()
            : tenantContactPersonRepository.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(TenantContactPerson::getTenantId, Function.identity()));

    Set<String> adminIds = new HashSet<>();
    centreRows.values().forEach(r -> addIds(adminIds, r.getContactAdminId(), r.getDeputyAdminId()));
    traegerRows
        .values()
        .forEach(r -> addIds(adminIds, r.getContactAdminId(), r.getDeputyAdminId()));
    Map<String, Admin> adminsById = loadAdmins(adminIds);
    Map<String, Set<Long>> agenciesByAdmin = agenciesOf(adminsById.values());

    var result = new LinkedHashMap<Long, CentreContact>();
    tenantIdByAgencyId.forEach(
        (agencyId, tenantId) -> {
          var centreRow = centreRows.get(agencyId);
          var traegerRow = tenantId == null ? null : traegerRows.get(tenantId);
          Predicate<Admin> centreRule =
              admin -> isCentreCandidate(admin, agencyId, tenantId, agenciesByAdmin);
          Predicate<Admin> traegerRule = admin -> isTraegerCandidate(admin, tenantId);
          result.put(
              agencyId,
              new CentreContact(
                  agencyId,
                  tenantId,
                  centreRow == null
                      ? ContactPair.NONE
                      : pair(
                          centreRow.getContactAdminId(),
                          centreRow.getDeputyAdminId(),
                          adminsById,
                          centreRule),
                  traegerRow == null
                      ? ContactPair.NONE
                      : pair(
                          traegerRow.getContactAdminId(),
                          traegerRow.getDeputyAdminId(),
                          adminsById,
                          traegerRule),
                  traegerRow == null ? null : traegerRow.getGeneralContactEmail()));
        });
    return result;
  }

  public TraegerContact resolveTraeger(Long tenantId) {
    var row =
        tenantId == null
            ? null
            : tenantContactPersonRepository.findAllById(List.of(tenantId)).stream()
                .findFirst()
                .orElse(null);
    if (row == null) {
      return new TraegerContact(tenantId, ContactPair.NONE, null);
    }
    var admins = loadAdmins(idsOf(row.getContactAdminId(), row.getDeputyAdminId()));
    return new TraegerContact(
        tenantId,
        pair(
            row.getContactAdminId(),
            row.getDeputyAdminId(),
            admins,
            admin -> isTraegerCandidate(admin, tenantId)),
        row.getGeneralContactEmail());
  }

  /** An agency admin of the centre or a Träger admin of its Träger. */
  public boolean isCentreCandidate(Admin admin, Long agencyId, Long tenantId) {
    return isCentreCandidate(admin, agencyId, tenantId, agenciesOf(List.of(admin)));
  }

  /** A Träger admin or agency admin of the Träger. */
  public boolean isTraegerCandidate(Admin admin, Long tenantId) {
    return admin != null
        && (admin.getType() == AdminType.TENANT || admin.getType() == AdminType.AGENCY)
        && Objects.equals(admin.getTenantId(), tenantId);
  }

  /** The agency admins of the centre, by name. */
  public List<Admin> agencyAdminsOf(Long agencyId) {
    var ids = Set.copyOf(adminAgencyRepository.findAgencyAdminIdsByAgencyId(agencyId));
    return ids.isEmpty() ? List.of() : sorted(adminRepository.findAllByIdIn(ids).stream());
  }

  /**
   * The Träger admins of the Träger, by name. Without a Träger (single-tenant deployment) every
   * Träger admin without a Träger belongs to the one Träger there is.
   */
  public List<Admin> traegerAdminsOf(Long tenantId) {
    var admins =
        tenantId == null
            ? adminRepository.findByType(AdminType.TENANT).stream()
                .filter(admin -> admin.getTenantId() == null)
            : adminRepository.findByTenantIdAndType(tenantId, AdminType.TENANT).stream();
    return sorted(admins);
  }

  /** Who may be the centre's own contact person: its agency admins and the Träger admins. */
  public List<Admin> centreCandidates(Long agencyId, Long tenantId) {
    return sorted(
        Stream.concat(agencyAdminsOf(agencyId).stream(), traegerAdminsOf(tenantId).stream())
            .distinct());
  }

  /** Who may be the Träger default: its Träger admins and agency admins. */
  public List<Admin> traegerCandidates(Long tenantId) {
    if (tenantId == null) {
      return List.of();
    }
    return sorted(
        Stream.concat(
            adminRepository.findByTenantIdAndType(tenantId, AdminType.TENANT).stream(),
            adminRepository.findByTenantIdAndType(tenantId, AdminType.AGENCY).stream()));
  }

  /**
   * The admin's counsellor identity, if it is not deleted and, when {@code agencyId} is given,
   * assigned to that centre.
   */
  public Optional<Consultant> activeCounsellor(String adminId, Long agencyId) {
    return consultantRepository
        .findByIdAndDeleteDateIsNull(adminId)
        .filter(
            consultant ->
                agencyId == null
                    || consultantAgencyRepository
                        .existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(adminId, agencyId));
  }

  /** The contact e-mail if set, else the login e-mail. */
  public static String displayEmail(Admin admin) {
    var contact = admin.getContactEmail();
    return contact == null || contact.isBlank() ? admin.getEmail() : contact.trim();
  }

  /** The full name; the user name as last resort. */
  public static String displayName(Admin admin) {
    var fullName =
        (nullToEmpty(admin.getFirstName()).trim() + " " + nullToEmpty(admin.getLastName()).trim())
            .trim();
    return fullName.isBlank() ? admin.getUsername() : fullName;
  }

  private boolean isCentreCandidate(
      Admin admin, Long agencyId, Long tenantId, Map<String, Set<Long>> agenciesByAdmin) {
    if (admin == null || admin.getType() == null) {
      return false;
    }
    return switch (admin.getType()) {
      case AGENCY -> agenciesByAdmin.getOrDefault(admin.getId(), Set.of()).contains(agencyId);
      case TENANT -> Objects.equals(admin.getTenantId(), tenantId);
      default -> false;
    };
  }

  private static ContactPair pair(
      String contactId, String deputyId, Map<String, Admin> admins, Predicate<Admin> rule) {
    var contact = contactId == null ? null : admins.get(contactId);
    if (contact == null || !rule.test(contact)) {
      return ContactPair.NONE;
    }
    var deputy = deputyId == null || deputyId.equals(contactId) ? null : admins.get(deputyId);
    return new ContactPair(contact, deputy != null && rule.test(deputy) ? deputy : null);
  }

  private Map<String, Admin> loadAdmins(Set<String> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    return adminRepository.findAllByIdIn(ids).stream()
        .collect(Collectors.toMap(Admin::getId, Function.identity(), (a, b) -> a));
  }

  private Map<String, Set<Long>> agenciesOf(java.util.Collection<Admin> admins) {
    Set<String> agencyAdminIds =
        admins.stream()
            .filter(admin -> admin.getType() == AdminType.AGENCY)
            .map(Admin::getId)
            .collect(Collectors.toSet());
    if (agencyAdminIds.isEmpty()) {
      return Map.of();
    }
    return adminAgencyRepository.findByAdminIdIn(agencyAdminIds).stream()
        .filter(link -> link.getAgencyId() != null)
        .collect(
            Collectors.groupingBy(
                AdminAgencyBase::getAdminId,
                Collectors.mapping(AdminAgencyBase::getAgencyId, Collectors.toSet())));
  }

  private static List<Admin> sorted(Stream<Admin> admins) {
    return admins.sorted(BY_NAME).toList();
  }

  private static Set<String> idsOf(String... ids) {
    var result = new HashSet<String>();
    addIds(result, ids);
    return result;
  }

  private static void addIds(Set<String> target, String... ids) {
    for (var id : ids) {
      if (id != null && !id.isBlank()) {
        target.add(id);
      }
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
