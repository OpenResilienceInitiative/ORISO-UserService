package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.GrantConsultantIdentityDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * An admin adds THEIR OWN account as counsellor of an agency, without an invite. Agency admin is
 * not offered: a Träger admin already holds every agency-admin right in their Träger.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSelfAssignmentService {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull AccountInviteAccessPolicy accessPolicy;
  private final @NonNull AgencyFacts agencyFacts;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull GrantConsultantIdentityService grantConsultantIdentityService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      topicAgencyCompatibilityValidator;

  private de.caritas.cob.userservice.api.port.out.IdentityClientConfig identityMode;
  private org.springframework.transaction.support.TransactionTemplate localWrites;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setIdentityMode(de.caritas.cob.userservice.api.port.out.IdentityClientConfig mode) {
    identityMode = Objects.requireNonNull(mode);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public void setLocalTransactionManager(
      org.springframework.transaction.PlatformTransactionManager manager) {
    localWrites = new org.springframework.transaction.support.TransactionTemplate(manager);
  }

  public SelfAssignmentResult assign(SelfAssignmentCommand command) {
    if (identityMode != null && identityMode.isFiveActorMode()) {
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var agency = checkedAgency(command);
      String userId = authenticatedUser.getUserId();
      var originalKind = grantConsultantIdentityService.originalSelfAssignmentKind(userId);
      boolean extend =
          originalKind == null
              ? consultantRepository.findByIdAndDeleteDateIsNull(userId).isPresent()
              : originalKind
                  == de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
                      .SelfAssignmentKind.EXTEND;
      if (extend) {
        try (var assignment = new ScopedAssignment(command, agency)) {
          grantConsultantIdentityService.assignExistingConsultant(assignment);
        }
        return new SelfAssignmentResult(command.role(), agency.id(), userId, false);
      }
      var grant = new GrantConsultantIdentityDTO();
      grant.setAgencyIds(List.of(agency.id()));
      grant.setTopicIds(resolveTopics(command.topicIds(), agency));
      grant.setFormalLanguage(true);
      grantConsultantIdentityService.grantConsultantIdentityToAdmin(userId, grant);
      return new SelfAssignmentResult(command.role(), agency.id(), userId, true);
    }
    return localWrites == null
        ? assignLegacy(command)
        : localWrites.execute(ignored -> assignLegacy(command));
  }

  @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;

  /** Only this public self-assignment flow can construct the held existing-identity callback. */
  public final class ScopedAssignment implements AutoCloseable {
    private final SelfAssignmentCommand command;
    private final AgencyFacts.Agency agency;
    private final String account = authenticatedUser.getUserId();
    private final Thread thread = Thread.currentThread();
    private boolean active = true;

    private ScopedAssignment(SelfAssignmentCommand requested, AgencyFacts.Agency agency) {
      this.command =
          new SelfAssignmentCommand(
              requested.role(),
              requested.agencyId(),
              List.copyOf(resolveTopics(requested.topicIds(), agency)));
      this.agency = agency;
    }

    public void requireActive() {
      if (!active
          || thread != Thread.currentThread()
          || !account.equals(authenticatedUser.getUserId()))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public String accountId() {
      requireActive();
      return account;
    }

    public GrantConsultantIdentityDTO grantInput() {
      requireActive();
      var input = new GrantConsultantIdentityDTO();
      input.setAgencyIds(List.of(agency.id()));
      input.setTopicIds(command.topicIds());
      return input;
    }

    public java.util.Map<String, Object> sourceFacts() {
      requireActive();
      return java.util.Map.of(
          "agencyId", agency.id(),
          "tenantId", agency.tenantId(),
          "topicIds",
              agency.topicIds() == null ? List.of() : agency.topicIds().stream().sorted().toList());
    }

    public void requireJpaOwner(jakarta.persistence.EntityManagerFactory factory) {
      requireActive();
      if (entities == null || factory == null || entities.getEntityManagerFactory() != factory)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    }

    public void validateOutsideSql() {
      requireActive();
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isActualTransactionActive())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      if (!agency.equals(checkedAgency(command)))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
            "Self-assignment agency changed");
      topicAgencyCompatibilityValidator.validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
          account, List.of(agency.id()), agency.tenantId());
    }

    public void validate() {
      requireActive();
      var current =
          consultantRepository
              .findByIdAndDeleteDateIsNull(account)
              .orElseThrow(
                  () ->
                      new de.caritas.cob.userservice.api.exception.httpresponses.ConflictException(
                          "Self-assignment consultant changed"));
      if (!Objects.equals(agency.tenantId(), current.getTenantId()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
            "Self-assignment tenant changed");
      if (consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
          account, agency.id())) throw alreadyAssigned();
    }

    public void apply(GrantConsultantIdentityService.ScopedGrant grant) {
      requireActive();
      grant.requireAssignmentCallback(this);
      if (!entities.isJoinedToTransaction())
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      validate();
      var current = consultantRepository.findById(account).orElseThrow();
      if (entities.contains(current)) entities.refresh(current);
      current.setStatus(de.caritas.cob.userservice.api.model.ConsultantStatus.IN_PROGRESS);
      current.addTopicsForAgency(agency.id(), grant.topicsByAgency().get(agency.id()));
      current.getConsultantTopics().stream()
          .filter(topic -> topic.getId() == null && agency.id().equals(topic.getAgencyId()))
          .forEach(
              topic -> {
                topic.setCreateDate(grant.creationDate());
                topic.setUpdateDate(grant.creationDate());
              });
      if (grant.agencies().stream()
          .anyMatch(ConsultantAgencyRelationCreatorService.GrantAgency::team))
        current.setTeamConsultant(true);
      consultantRepository.save(current);
    }

    @Override
    public void close() {
      active = false;
    }

    @Override
    public String toString() {
      return "ScopedAssignment[source=redacted]";
    }
  }

  private SelfAssignmentResult assignLegacy(SelfAssignmentCommand command) {
    var agency = checkedAgency(command);
    String userId = authenticatedUser.getUserId();
    boolean identityCreated = assignAsCounsellor(userId, agency, command.topicIds());
    return new SelfAssignmentResult(command.role(), agency.id(), userId, identityCreated);
  }

  private AgencyFacts.Agency checkedAgency(SelfAssignmentCommand command) {
    if (command == null || command.role() == null) {
      throw new BadRequestException("role is required");
    }
    if (command.agencyId() == null) {
      throw new BadRequestException("agencyId is required");
    }
    AgencyFacts.Agency agency =
        agencyFacts
            .find(command.agencyId())
            .filter(found -> !found.deleted())
            .orElseThrow(
                () -> new NotFoundException("agencyId " + command.agencyId() + " does not exist"));
    accessPolicy.authorizeSelfAssignment(agency.id(), agency.tenantId());

    return agency;
  }

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

  /** Returns true when a new consultant identity was created. */
  private boolean assignAsCounsellor(
      String userId, AgencyFacts.Agency agency, List<Long> topicIds) {
    // A first grant has no consultant row to lock; the admin row (platform admins sit in tenant 0)
    // makes a double click wait and answer 409 instead of creating a second identity.
    TenantContext.supplyAcrossTenants(() -> adminRepository.findByIdForUpdate(userId));
    Optional<Consultant> counsellor = consultantRepository.findActiveByIdForUpdate(userId);
    if (counsellor.isPresent()) {
      if (consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
          userId, agency.id())) {
        throw alreadyAssigned();
      }
      List<Long> topics = resolveTopics(topicIds, agency);
      // Same uncached coverage check as a new identity gets in GrantConsultantIdentityService.
      var topicsByAgency =
          topicAgencyCompatibilityValidator.validateGrantTopicsAgainstSelectedAgencies(
              topics, List.of(agency.id()), agency.tenantId());
      consultantAgencyRelationCreatorService.createNewConsultantAgency(
          userId, new CreateConsultantAgencyDTO().agencyId(agency.id()));
      addTopics(counsellor.get(), agency.id(), topicsByAgency.get(agency.id()));
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

  /** The new centre's topics join the existing rows; a topic held elsewhere gets its own row. */
  private void addTopics(Consultant consultant, Long agencyId, Collection<Long> topicIds) {
    consultant.addTopicsForAgency(agencyId, topicIds);
    consultantRepository.save(consultant);
  }

  /** A counsellor needs at least one topic: the agency's only one, or a pick among several. */
  private static List<Long> resolveTopics(List<Long> requested, AgencyFacts.Agency agency) {
    if (requested != null && !requested.isEmpty()) {
      if (requested.stream().anyMatch(Objects::isNull)) {
        throw new BadRequestException("topicIds must not contain null");
      }
      return List.copyOf(requested);
    }
    List<Long> offered = agency.topicIds() == null ? List.of() : agency.topicIds();
    if (offered.size() != 1) {
      throw new BadRequestException(
          offered.isEmpty()
              ? "agency " + agency.id() + " offers no topic to counsel in"
              : "topicIds is required: agency " + agency.id() + " offers several topics");
    }
    return List.copyOf(offered);
  }

  private static CustomValidationHttpStatusException alreadyAssigned() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.SELF_ASSIGNMENT_ALREADY_EXISTS, HttpStatus.CONFLICT);
  }

  public enum SelfAssignmentRole {
    COUNSELLOR
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
