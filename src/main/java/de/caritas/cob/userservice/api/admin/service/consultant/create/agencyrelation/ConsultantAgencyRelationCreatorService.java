package de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang3.BooleanUtils.isTrue;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.admin.service.consultant.validation.ConsultantTopicAgencyCompatibilityValidator;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.manager.consultingtype.ConsultingTypeManager;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantAgencyStatus;
import de.caritas.cob.userservice.api.model.ConsultantStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.IdentityRoleLookup;
import de.caritas.cob.userservice.api.port.out.IdentityRoleUpdater;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.session.ConsultantJoinedAgencyEvent;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Creator class to generate new {@link ConsultantAgency} instances. */
@Service
@RequiredArgsConstructor
public class ConsultantAgencyRelationCreatorService {

  private final @NonNull ConsultantAgencyService consultantAgencyService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull IdentityClient identityClient;
  private final @NonNull IdentityRoleLookup identityRoleLookup;
  private final @NonNull IdentityRoleUpdater identityRoleUpdater;
  private final @NonNull ConsultingTypeManager consultingTypeManager;
  private final @NonNull ConsultantAgencyRelationFinalizer consultantAgencyRelationFinalizer;
  private final @NonNull ConsultantTopicAgencyCompatibilityValidator
      consultantTopicAgencyCompatibilityValidator;
  private final @NonNull ApplicationEventPublisher eventPublisher;

  /** Resolved source data only; this record cannot issue a local callback or native authority. */
  public record GrantAgency(
      Long id,
      Long tenant,
      int consultingType,
      boolean team,
      boolean bounded,
      Set<String> requiredRoles) {
    public GrantAgency {
      requiredRoles = Set.copyOf(requiredRoles);
    }
  }

  public java.util.List<GrantAgency> resolveGrantAgencies(
      de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService
              .ScopedGrant
          grant) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    var result = new java.util.ArrayList<GrantAgency>();
    for (Long id : grant.agencyIds().stream().sorted().toList()) {
      var agency = retrieveAgency(id);
      if (!java.util.Objects.equals(id, agency.getId())
          || !java.util.Objects.equals(grant.tenantId(), agency.getTenantId()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
            "Consultant grant agency tenant changed");
      var settings = consultingTypeManager.getConsultingTypeSettings(agency.getConsultingType());
      if (settings == null)
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var roles = new java.util.TreeSet<String>();
      // This actual grant route sends no roleSetKey. Preserve its exact existing selector,
      // including a configured null-key entry, rather than unioning unrelated named role sets.
      if (settings.getRoles() != null
          && settings.getRoles().getConsultant() != null
          && settings.getRoles().getConsultant().getRoleSets() != null) {
        var sets = settings.getRoles().getConsultant().getRoleSets();
        String key = new CreateConsultantAgencyDTO().agencyId(id).getRoleSetKey();
        for (var entry : sets.entrySet())
          if (java.util.Objects.equals(key, entry.getKey())) roles.addAll(entry.getValue());
      }
      if (!Set.of("consultant", "group-chat-consultant", "restricted-agency-admin", "user-admin")
          .containsAll(roles))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      result.add(
          new GrantAgency(
              id,
              agency.getTenantId(),
              agency.getConsultingType(),
              isTrue(agency.getTeamAgency()),
              consultingTypeManager.isConsultantBoundedToAgency(agency.getConsultingType()),
              roles));
    }
    if (result.stream().anyMatch(GrantAgency::bounded)
        && result.stream().map(GrantAgency::consultingType).distinct().count() != 1)
      throw new BadRequestException("Consultant grant agencies have incompatible consulting types");
    return java.util.List.copyOf(result);
  }

  public void validateExistingGrantAgencies(
      de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService
              .ScopedGrant
          grant) {
    if (grant.identityCreation()
        || org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    var assigned =
        consultantAgencyService.getConsultantAgenciesByConsultantIds(
            java.util.List.of(grant.accountId()));
    for (var relation : assigned) {
      var current = retrieveAgency(relation.getAgencyId());
      if (!java.util.Objects.equals(current.getTenantId(), grant.tenantId()))
        throw new de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException(
            "Self-assignment current agency tenant changed");
      for (var agency : grant.agencies())
        if (agency.bounded() && current.getConsultingType() != agency.consultingType())
          throw new BadRequestException(
              "Self-assignment agencies have incompatible consulting types");
    }
  }

  public void validateExistingImportSources(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source) {
    source.requireLive();
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
    consultantTopicAgencyCompatibilityValidator
        .validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
            source.accountId(),
            source.agencies().stream()
                .map(
                    de.caritas.cob.userservice.api.service.ConsultantImportService.ExistingAgency
                        ::id)
                .toList(),
            source.tenantId());
    for (var relation : source.currentRelations()) {
      var agency = retrieveAgency(relation.getAgencyId());
      if (!java.util.Objects.equals(agency.getTenantId(), source.tenantId()))
        throw new BadRequestException("Existing import agency tenant changed");
      for (var selected : source.agencies())
        if (selected.bounded() && agency.getConsultingType() != selected.consultingType())
          throw new BadRequestException(
              "Existing import agencies have incompatible consulting types");
    }
  }

  public void createCheckedExistingImportAgencies(
      de.caritas.cob.userservice.api.service.ConsultantImportService.ScopedExistingImport source,
      de.caritas.cob.userservice.api.service.identity.ExistingConsultantImportMaintenance
              .CheckedImport
          checked) {
    checked.requireLocalCallback(source);
    var consultant = retrieveConsultant(source.accountId());
    for (Long id : checked.agenciesToCreate()) {
      var relation =
          ConsultantAgency.builder()
              .consultant(consultant)
              .agencyId(id)
              .tenantId(source.tenantId())
              .createDate(checked.creationDate())
              .updateDate(checked.creationDate())
              .status(ConsultantAgencyStatus.IN_PROGRESS)
              .build();
      relation = consultantAgencyService.saveConsultantAgency(relation);
      consultantAgencyRelationFinalizer.finalizeConsultantAgencyRelation(consultant, relation);
      eventPublisher.publishEvent(new ConsultantJoinedAgencyEvent(consultant.getId(), id));
    }
  }

  /** SQL only, admitted by the same actual held Grant callback after native/Matrix ACK. */
  public void createCheckedGrantAgencies(
      de.caritas.cob.userservice.api.admin.service.consultant.create.GrantConsultantIdentityService
              .ScopedGrant
          grant) {
    grant.requireLocalCurrent();
    var consultant = retrieveConsultant(grant.accountId());
    for (var agency : grant.agencies()) {
      if (!java.util.Objects.equals(consultant.getTenantId(), agency.tenant())
          || !grant.requiredRoles().containsAll(agency.requiredRoles()))
        throw new de.caritas.cob.userservice.api.port.out.ScopedIdentityCommandRequiredException();
      var relation =
          ConsultantAgency.builder()
              .consultant(consultant)
              .agencyId(agency.id())
              .tenantId(agency.tenant())
              .createDate(grant.creationDate())
              .updateDate(grant.creationDate())
              .status(ConsultantAgencyStatus.IN_PROGRESS)
              .build();
      relation = consultantAgencyService.saveConsultantAgency(relation);
      consultantAgencyRelationFinalizer.finalizeConsultantAgencyRelation(consultant, relation);
      eventPublisher.publishEvent(new ConsultantJoinedAgencyEvent(consultant.getId(), agency.id()));
    }
  }

  /**
   * Creates a new {@link ConsultantAgency} based on the {@link ImportRecord} and agency ids.
   *
   * @param consultantId the consultant id
   * @param agencyIds the agency ids to be added
   * @param roles the roles
   * @param logMethod the methode used for logging
   */
  public void createConsultantAgencyRelations(
      String consultantId, Set<Long> agencyIds, Set<String> roles, Consumer<String> logMethod) {
    checkConsultantHasRoleSet(roles, consultantId);
    var additionalAgencyIds = Set.copyOf(agencyIds);
    agencyIds.stream()
        .map(
            agencyId ->
                new ImportRecordAgencyCreationInputAdapter(consultantId, agencyId, roles) {
                  @Override
                  public Set<Long> getAdditionalAgencyIds() {
                    return additionalAgencyIds;
                  }
                })
        .forEach(input -> createNewConsultantAgency(input, logMethod));
  }

  /**
   * Creates a new {@link ConsultantAgency} based on the consultantId and {@link
   * CreateConsultantAgencyDTO} input.
   *
   * @param consultantId the consultant to use
   * @param createConsultantAgencyDTO the agencyId and role ConsultantAgencyAdminResultDTO}
   */
  public void createNewConsultantAgency(
      String consultantId, CreateConsultantAgencyDTO createConsultantAgencyDTO) {
    var adapter =
        new CreateConsultantAgencyDTOInputAdapter(consultantId, createConsultantAgencyDTO);
    createNewConsultantAgency(adapter, LogService::logInfo);
  }

  private void createNewConsultantAgency(
      ConsultantAgencyCreationInput input, Consumer<String> logMethod) {
    var persistedRelation = prepareConsultantAgencyRelation(input);
    completeConsultantAgencyAssigment(input, logMethod, persistedRelation);
  }

  public ConsultantAgency prepareConsultantAgencyRelation(ConsultantAgencyCreationInput input) {
    var consultant = this.retrieveConsultant(input.getConsultantId());

    var agency = retrieveAgency(input.getAgencyId());
    if (consultingTypeManager.isConsultantBoundedToAgency(agency.getConsultingType())) {
      this.verifyAllAssignedAgenciesHaveSameConsultingType(agency.getConsultingType(), consultant);
    }

    consultantTopicAgencyCompatibilityValidator
        .validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
            consultant.getId(), input.getAdditionalAgencyIds(), consultant.getTenantId());

    ensureConsultingTypeRoles(input, agency);
    return consultantAgencyService.saveConsultantAgency(
        buildConsultantAgency(consultant, agency.getId()));
  }

  public void completeConsultantAgencyAssigment(
      ConsultantAgencyCreationInput input, Consumer<String> logMethod) {
    completeConsultantAgencyAssigment(input, logMethod, null);
  }

  private void completeConsultantAgencyAssigment(
      ConsultantAgencyCreationInput input,
      Consumer<String> logMethod,
      ConsultantAgency persistedRelation) {

    var consultant = this.retrieveConsultant(input.getConsultantId());
    var agency = retrieveAgency(input.getAgencyId());

    if (!ConsultantStatus.IN_PROGRESS.equals(consultant.getStatus())) {
      consultant.setStatus(ConsultantStatus.IN_PROGRESS);
      consultantRepository.save(consultant);
    }

    if (persistedRelation == null) {
      // Legacy two-step callers do not carry the prepared relation across requests.
      consultantAgencyRelationFinalizer.finalizeConsultantAgencyRelation(consultant, agency);
    } else {
      // The atomic create path already owns the persisted row. Carry it forward instead of
      // relying on an immediate read-after-write query that may not see the row yet.
      consultantAgencyRelationFinalizer.finalizeConsultantAgencyRelation(
          consultant, persistedRelation);
    }

    if (isTeamAgencyButNotTeamConsultant(agency, consultant)) {
      consultant.setTeamConsultant(true);
      consultantRepository.save(consultant);
    }

    // US#1060: an agency assignment that stops at the database row leaves the counsellor locked
    // out of every enquiry that arrived before them — Matrix membership is what makes an enquiry
    // visible at all (FE#811). Announced rather than performed here: the membership fan-out has to
    // wait for the commit, or a later rejected agency would roll the row back around a Matrix join
    // that already happened. See AgencyMembershipSyncListener.
    eventPublisher.publishEvent(
        new ConsultantJoinedAgencyEvent(consultant.getId(), agency.getId()));
  }

  private void ensureConsultingTypeRoles(ConsultantAgencyCreationInput input, AgencyDTO agency) {
    var roles =
        consultingTypeManager.getConsultingTypeSettings(agency.getConsultingType()).getRoles();
    if (nonNull(roles) && nonNull(roles.getConsultant())) {
      var roleSets = roles.getConsultant().getRoleSets();
      var requestedRoles = new LinkedHashSet<String>();
      for (var roleSetName : input.getRoleSetNames()) {
        requestedRoles.addAll(roleSets.getOrDefault(roleSetName, Collections.emptyList()));
      }
      if (de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.PendingCreationRoles
          .active()) {
        var consultant = retrieveConsultant(input.getConsultantId());
        de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.PendingCreationRoles.authorize(
            input.getConsultantId(), consultant.getTenantId(), requestedRoles);
      } else identityRoleUpdater.ensureRoles(input.getConsultantId(), requestedRoles);
    }
  }

  private Consultant retrieveConsultant(String consultantId) {
    return this.consultantRepository
        .findByIdAndDeleteDateIsNull(consultantId)
        .orElseThrow(
            () ->
                new BadRequestException(
                    String.format("Consultant with id %s does not exist", consultantId)));
  }

  private void checkConsultantHasRoleSet(Set<String> roles, String consultantId) {
    boolean pending = false;
    if (de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.PendingCreationRoles.active()) {
      var consultant = retrieveConsultant(consultantId);
      pending =
          de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.PendingCreationRoles.authorize(
              consultantId, consultant.getTenantId(), roles);
    }
    var hasRequestedRole =
        !roles.isEmpty()
            && (pending
                || identityRoleLookup.findAllByUserId(consultantId).stream()
                    .anyMatch(roles::contains));
    if (!hasRequestedRole) {
      throw new BadRequestException(
          String.format(
              "Consultant with id %s does not have the role set %s", consultantId, roles));
    }
  }

  private AgencyDTO retrieveAgency(Long agencyId) {
    var agencyDto = this.agencyService.getAgency(agencyId);
    return Optional.ofNullable(agencyDto)
        .orElseThrow(
            () ->
                new BadRequestException(
                    String.format("AgencyId %s is not a valid agency", agencyId)));
  }

  private void verifyAllAssignedAgenciesHaveSameConsultingType(
      int consultingTypeId, Consultant consultant) {
    if (nonNull(consultant.getConsultantAgencies())) {
      consultant.getConsultantAgencies().stream()
          .map(ConsultantAgency::getAgencyId)
          .map(this::retrieveAgency)
          .filter(agency -> agency.getConsultingType() != consultingTypeId)
          .findFirst()
          .ifPresent(
              agency -> {
                throw new BadRequestException(
                    String.format(
                        "ERROR: different consulting types found than %d for consultant with id %s",
                        consultingTypeId, consultant.getId()));
              });
    }
  }

  private boolean isTeamAgencyButNotTeamConsultant(AgencyDTO agency, Consultant consultant) {
    return isTrue(agency.getTeamAgency()) && !consultant.isTeamConsultant();
  }

  private ConsultantAgency buildConsultantAgency(Consultant consultant, Long agencyId) {
    return ConsultantAgency.builder()
        .consultant(consultant)
        .agencyId(agencyId)
        .createDate(nowInUtc())
        .updateDate(nowInUtc())
        .tenantId(consultant.getTenantId())
        .status(ConsultantAgencyStatus.IN_PROGRESS)
        .build();
  }
}
