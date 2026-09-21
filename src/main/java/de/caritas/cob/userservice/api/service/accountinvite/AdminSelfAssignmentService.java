package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.ExistingAgencyClient.ExistingAgency;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Self-assignment (ORISO-Admin#1026, slice 3): an admin assigns THEIR OWN existing account to a
 * lower role of an agency — no e-mail, no invite, no new login.
 *
 * <ul>
 *   <li>{@code COUNSELLOR}: an admin without a consultant identity gets one through the same path
 *       as the Users area's "Auch als Beraterin anlegen" ({@link GrantConsultantIdentityService});
 *       an admin who already counsels elsewhere only gets the agency added. A missing topic
 *       selection defaults to the agency's topic when it offers exactly one.
 *   <li>{@code AGENCY_ADMIN} (Träger and platform admins only): the caller's admin account is bound
 *       to the agency ({@code admin_agency}). Roles are not touched — a Träger admin already holds
 *       every agency-admin right in their Träger; the binding records that they administer this
 *       agency.
 * </ul>
 *
 * <p>Who may assign themselves where is {@link AccountInviteAccessPolicy#authorizeSelfAssignment}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSelfAssignmentService {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull ExistingAgencyClient existingAgencyClient;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull GrantConsultantIdentityService grantConsultantIdentityService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;

  public SelfAssignmentResult assign(SelfAssignmentCommand command) {
    if (command == null || command.role() == null) {
      throw new BadRequestException("role is required");
    }
    if (command.agencyId() == null) {
      throw new BadRequestException("agencyId is required");
    }
    ExistingAgency agency =
        existingAgencyClient
            .find(command.agencyId())
            .filter(found -> !found.deleted())
            .orElseThrow(
                () -> new NotFoundException("agencyId " + command.agencyId() + " does not exist"));
    boolean asAgencyAdmin = command.role() == SelfAssignmentRole.AGENCY_ADMIN;
    accessPolicy.authorizeSelfAssignment(asAgencyAdmin, agency.id(), agency.tenantId());

    String userId = authenticatedUser.getUserId();
    if (asAgencyAdmin) {
      assignAsAgencyAdmin(userId, agency.id());
      return new SelfAssignmentResult(command.role(), agency.id(), userId, false);
    }
    boolean identityCreated = assignAsCounsellor(userId, agency, command.topicIds());
    return new SelfAssignmentResult(command.role(), agency.id(), userId, identityCreated);
  }

  /** What the caller is assigned to today — the state the Admin shows next to the switches. */
  public SelfAssignments current() {
    String userId = authenticatedUser.getUserId();
    List<Long> adminAgencies =
        adminAgencyRepository.findByAdminId(userId).stream()
            .map(AdminAgency::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    List<Long> counsellorAgencies =
        consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(userId).stream()
            .map(ConsultantAgency::getAgencyId)
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    return new SelfAssignments(adminAgencies, counsellorAgencies);
  }

  private void assignAsAgencyAdmin(String userId, Long agencyId) {
    var admin =
        adminRepository
            .findById(userId)
            .orElseThrow(() -> new BadRequestException("The caller has no admin account"));
    if (!adminAgencyRepository.findByAdminIdAndAgencyId(userId, agencyId).isEmpty()) {
      throw alreadyAssigned();
    }
    adminAgencyRepository.save(
        AdminAgency.builder()
            .admin(admin)
            .agencyId(agencyId)
            .createDate(nowInUtc())
            .updateDate(nowInUtc())
            .build());
    log.info("Admin {} assigned themselves as agency admin of agency {}", userId, agencyId);
  }

  /**
   * @return whether a new consultant identity was created (false: an existing one got the agency)
   */
  private boolean assignAsCounsellor(String userId, ExistingAgency agency, List<Long> topicIds) {
    if (consultantRepository.findByIdAndDeleteDateIsNull(userId).isPresent()) {
      if (consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
          userId, agency.id())) {
        throw alreadyAssigned();
      }
      consultantAgencyRelationCreatorService.createNewConsultantAgency(
          userId, new CreateConsultantAgencyDTO().agencyId(agency.id()));
      log.info("Admin {} assigned themselves as counsellor of agency {}", userId, agency.id());
      return false;
    }
    GrantConsultantIdentityDTO grant = new GrantConsultantIdentityDTO();
    grant.setAgencyIds(List.of(agency.id()));
    grant.setTopicIds(resolveTopics(topicIds, agency));
    grant.setFormalLanguage(true);
    grantConsultantIdentityService.grantConsultantIdentityToAdmin(userId, grant);
    log.info(
        "Admin {} assigned themselves as counsellor of agency {} (new consultant identity)",
        userId,
        agency.id());
    return true;
  }

  /** At least one topic when the agency offers any: the only one is taken, several need a pick. */
  private static List<Long> resolveTopics(List<Long> requested, ExistingAgency agency) {
    if (requested != null && !requested.isEmpty()) {
      return List.copyOf(requested);
    }
    List<Long> offered = agency.topicIds() == null ? List.of() : agency.topicIds();
    if (offered.size() > 1) {
      throw new BadRequestException(
          "topicIds is required: agency " + agency.id() + " offers several topics");
    }
    return List.copyOf(offered);
  }

  private static CustomValidationHttpStatusException alreadyAssigned() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.SELF_ASSIGNMENT_ALREADY_EXISTS, HttpStatus.CONFLICT);
  }

  public enum SelfAssignmentRole {
    COUNSELLOR,
    AGENCY_ADMIN
  }

  public record SelfAssignmentCommand(SelfAssignmentRole role, Long agencyId, List<Long> topicIds) {

    public SelfAssignmentCommand(SelfAssignmentRole role, Long agencyId) {
      this(role, agencyId, null);
    }
  }

  public record SelfAssignmentResult(
      SelfAssignmentRole role, Long agencyId, String userId, boolean consultantIdentityCreated) {}

  public record SelfAssignments(List<Long> agencyAdminAgencyIds, List<Long> counsellorAgencyIds) {}
}
