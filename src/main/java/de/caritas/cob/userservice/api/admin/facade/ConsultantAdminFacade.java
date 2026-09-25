package de.caritas.cob.userservice.api.admin.facade;

import static de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO.AgencyTypeEnum.DEFAULT_AGENCY;
import static de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO.AgencyTypeEnum.TEAM_AGENCY;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.FieldEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.OrderEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateAdminConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateConsultantDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminCallerScope;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminFilterService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.CreateConsultantAgencyDTOInputAdapter;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.service.LogService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Facade to encapsulate admin functions for consultants. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantAdminFacade {

  private final @NonNull ConsultantAdminService consultantAdminService;
  private final @NonNull ConsultantAdminFilterService consultantAdminFilterService;
  private final @NonNull ConsultantAgencyAdminService consultantAgencyAdminService;
  private final @NonNull ConsultantAgencyRelationCreatorService
      consultantAgencyRelationCreatorService;

  private final @NonNull AuthenticatedUser authenticatedUser;

  private final @NonNull AgencyService agencyService;

  private final @NonNull ConsultantChatIdentityService consultantChatIdentityService;

  private final @NonNull AdminCallerScope adminCallerScope;

  @Value("${multitenancy.enabled}")
  private boolean multiTenancyEnabled;

  /**
   * Finds a consultant by given consultant id.
   *
   * @param consultantId the id of the consultant to search for
   * @return the generated {@link ConsultantResponseDTO}
   */
  public ConsultantAdminResponseDTO findConsultant(String consultantId) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    return this.consultantAdminService.findConsultantById(consultantId);
  }

  /**
   * Searches for consultants by given {@link ConsultantFilter}, limits the result by perPage and
   * generates a {@link ConsultantSearchResultDTO} containing hal links.
   *
   * @param consultantFilter the filter object containing filter values
   * @param page the current requested page
   * @param perPage the amount of items in one page
   * @return the result list
   */
  public ConsultantSearchResultDTO findFilteredConsultants(
      Integer page, Integer perPage, ConsultantFilter consultantFilter, Sort sort) {
    sort = getValidSorter(sort);
    var filteredConsultants =
        this.consultantAdminFilterService.findFilteredConsultants(
            page, perPage, consultantFilter, sort);
    retrieveAndMergeAgenciesToConsultants(filteredConsultants);

    return filteredConsultants;
  }

  private Sort getValidSorter(Sort sort) {
    if (sort == null
        || Stream.of(FieldEnum.values()).noneMatch(providedSortFieldIgnoringCase(sort))) {
      sort = new Sort();
      sort.setField(FieldEnum.LAST_NAME);
      sort.setOrder(OrderEnum.ASC);
    }
    return sort;
  }

  private Predicate<FieldEnum> providedSortFieldIgnoringCase(Sort sort) {
    return field -> {
      if (nonNull(sort.getField())) {
        return field.getValue().equalsIgnoreCase(sort.getField().getValue());
      }
      return false;
    };
  }

  private void retrieveAndMergeAgenciesToConsultants(
      ConsultantSearchResultDTO filteredConsultants) {
    if (nonNull(filteredConsultants)) {
      var consultants =
          filteredConsultants.getEmbedded().stream()
              .map(ConsultantAdminResponseDTO::getEmbedded)
              .collect(Collectors.toSet());
      consultantAgencyAdminService.appendAgenciesForConsultants(consultants);
    }
  }

  /**
   * Creates a new {@link Consultant} based on the {@link CreateConsultantDTO} input.
   *
   * @param createConsultantDTO the input data used for {@link Consultant} creation
   * @return the generated and persisted {@link Consultant} representation as {@link
   *     ConsultantAdminResponseDTO}
   */
  public ConsultantAdminResponseDTO createNewConsultant(CreateConsultantDTO createConsultantDTO) {
    if (createConsultantDTO != null) {
      adminCallerScope.assertMayUseAgencies(createConsultantDTO.getAgencyIds());
    }
    return this.consultantAdminService.createNewConsultant(createConsultantDTO);
  }

  /**
   * Updates a {@link Consultant} based on the {@link UpdateConsultantDTO} input.
   *
   * @param consultantId the id of the consultant to update
   * @param updateConsultantDTO the input data used for {@link Consultant} update
   * @return the generated and persisted {@link Consultant} representation as {@link
   *     ConsultantAdminResponseDTO}
   */
  public ConsultantAdminResponseDTO updateConsultant(
      String consultantId, UpdateAdminConsultantDTO updateConsultantDTO) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    return this.consultantAdminService.updateConsultant(consultantId, updateConsultantDTO);
  }

  /**
   * Completes the chat (Matrix) provisioning of a consultant created while the chat server was
   * unreachable. Idempotent: one that already owns a chat identity is returned untouched.
   *
   * @param consultantId the id of the consultant to repair
   * @return the consultant as it now stands, including its {@code chatIdentityStatus}
   */
  public ConsultantAdminResponseDTO repairConsultantChatIdentity(String consultantId) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    this.consultantChatIdentityService.provisionMissingChatIdentity(consultantId);
    return this.consultantAdminService.findConsultantById(consultantId);
  }

  /**
   * Returns all Agencies for the given consultantId.
   *
   * @param consultantId id of the consultant
   * @return the generated {@link ConsultantAgencyResponseDTO}
   */
  public ConsultantAgencyResponseDTO findConsultantAgencies(String consultantId) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    return this.consultantAgencyAdminService.findConsultantAgencies(consultantId);
  }

  /**
   * Creates a new {@link ConsultantAgency} based on the consultantId and {@link
   * CreateConsultantAgencyDTO} input.
   *
   * @param consultantId the consultant to use
   * @param createConsultantAgencyDTO the agencyId and role {@link CreateConsultantAgencyDTO}
   */
  public void createNewConsultantAgency(
      String consultantId, CreateConsultantAgencyDTO createConsultantAgencyDTO) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    adminCallerScope.assertMayUseAgencies(List.of(createConsultantAgencyDTO.getAgencyId()));
    consultantAgencyRelationCreatorService.createNewConsultantAgency(
        consultantId, createConsultantAgencyDTO);
  }

  /**
   * Sets the complete set of agency relations for a consultant: relations no longer present in the
   * passed list are marked for deletion, missing relations are created, already-existing relations
   * are left untouched (idempotency). Validation failures (e.g. topic/agency mismatch) are
   * propagated to the caller instead of being swallowed, so the admin UI can surface them.
   *
   * <p>Runs in a single transaction. A rejected deletion (e.g. the consultant is the last one of a
   * still-active agency) aborts the whole call, so the consultant can never be left with a partly
   * applied agency set — earlier deletions rolled back, later creations never reached. A consultant
   * stuck in such a half-applied state fails the subsequent topic update, because the topics are
   * then validated against agencies that are no longer the ones the admin selected.
   *
   * @param consultantId the consultant to update
   * @param agencyList the desired complete set of agency relations
   */
  @Transactional
  public void setConsultantAgencies(
      String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    var persistedAgencyIds =
        consultantAgencyAdminService.findConsultantAgencyIds(consultantId).stream()
            .collect(Collectors.toSet());
    var desiredAgencyIds =
        agencyList.stream().map(CreateConsultantAgencyDTO::getAgencyId).collect(Collectors.toSet());

    var agencyIdsToDelete =
        persistedAgencyIds.stream()
            .filter(persistedAgencyId -> !desiredAgencyIds.contains(persistedAgencyId))
            .collect(Collectors.toList());
    var agenciesToCreate =
        agencyList.stream()
            .filter(agency -> !persistedAgencyIds.contains(agency.getAgencyId()))
            .toList();
    // Only changed relations are checked: an agency admin keeps, untouched, the other agencies of
    // a shared counsellor.
    var changedAgencyIds = new HashSet<>(agencyIdsToDelete);
    agenciesToCreate.forEach(agency -> changedAgencyIds.add(agency.getAgencyId()));
    adminCallerScope.assertMayUseAgencies(changedAgencyIds);
    if (!agencyIdsToDelete.isEmpty()) {
      consultantAgencyAdminService.markConsultantAgenciesForDeletion(
          consultantId, agencyIdsToDelete);
    }

    agenciesToCreate.forEach(
        agency ->
            consultantAgencyRelationCreatorService.createNewConsultantAgency(consultantId, agency));
  }

  /**
   * Changes the consultant flag is_team_consultant and assignments for agency type changes.
   *
   * @param agencyId the id of the changed agency
   * @param agencyTypeDTO the request object containing the target type
   */
  public void changeAgencyType(Long agencyId, AgencyTypeDTO agencyTypeDTO) {
    adminCallerScope.assertMayUseAgencies(List.of(agencyId));
    if (TEAM_AGENCY.equals(agencyTypeDTO.getAgencyType())) {
      this.consultantAgencyAdminService.markAllAssignedConsultantsAsTeamConsultant(agencyId);
    }
    if (DEFAULT_AGENCY.equals(agencyTypeDTO.getAgencyType())) {
      this.consultantAgencyAdminService.removeConsultantsFromTeamSessionsByAgencyId(agencyId);
    }
  }

  /**
   * Marks the {@link ConsultantAgency} as deleted.
   *
   * @param consultantId the consultant id
   * @param agencyId the agency id
   */
  public void markConsultantAgencyForDeletion(String consultantId, Long agencyId) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    adminCallerScope.assertMayUseAgencies(List.of(agencyId));
    this.consultantAgencyAdminService.markConsultantAgencyForDeletion(consultantId, agencyId);
  }

  /**
   * Marks given list of agencies assigned to given consultant for deletion.
   *
   * @param consultantId given consultant id
   * @param agencyIds agencies that need to be removed from consultant
   */
  public void markConsultantAgenciesForDeletion(String consultantId, List<Long> agencyIds) {
    consultantAgencyAdminService.markConsultantAgenciesForDeletion(consultantId, agencyIds);
  }

  /**
   * Marks the {@link Consultant} as deleted.
   *
   * @param consultantId the consultant id
   */
  public void markConsultantForDeletion(String consultantId, Boolean forceDeleteSessions) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    this.consultantAdminService.markConsultantForDeletion(consultantId, forceDeleteSessions);
  }

  public void pauseConsultantDeletion(
      String consultantId, String reason, Integer months, String pausedBy) {
    adminCallerScope.assertMayActOnConsultant(consultantId);
    this.consultantAdminService.pauseConsultantDeletion(consultantId, reason, months, pausedBy);
  }

  /**
   * Retrieves all consultants of the agency with given id.
   *
   * @param agencyId the agency id
   * @return the generated {@link AgencyConsultantResponseDTO}
   */
  public AgencyConsultantResponseDTO findConsultantsForAgency(String agencyId) {
    var parsedAgencyId = Long.valueOf(agencyId);
    adminCallerScope.assertMayUseAgencies(List.of(parsedAgencyId));
    return this.consultantAgencyAdminService.findConsultantsForAgency(parsedAgencyId);
  }

  /**
   * Creates consultant agencies from given consultant id and agencies list and sets to status
   * IN_PROGRESS.
   *
   * @param consultantId given consultant
   * @param agencies list of agencies
   */
  public void prepareConsultantAgencyRelation(
      String consultantId, List<CreateConsultantAgencyDTO> agencies) {
    Set<Long> additionalAgencyIds =
        agencies.stream().map(CreateConsultantAgencyDTO::getAgencyId).collect(Collectors.toSet());
    agencies.forEach(
        agency ->
            this.consultantAgencyRelationCreatorService.prepareConsultantAgencyRelation(
                new CreateConsultantAgencyDTOInputAdapter(consultantId, agency) {
                  @Override
                  public Set<Long> getAdditionalAgencyIds() {
                    return additionalAgencyIds;
                  }
                }));
  }

  /**
   * Completes the assigment process of a consultant to given list of agencies and sets status of
   * each relation to CREATED if successfully executed.
   *
   * @param consultantId
   * @param agencies
   */
  public void completeConsultantAgencyAssigment(
      String consultantId, List<CreateConsultantAgencyDTO> agencies) {
    agencies.forEach(
        agency ->
            this.consultantAgencyRelationCreatorService.completeConsultantAgencyAssigment(
                new CreateConsultantAgencyDTOInputAdapter(consultantId, agency),
                LogService::logInfo));
  }

  /**
   * Determines which agencies should be set for deletion process.
   *
   * @param consultantId given consultant
   * @param newList new list agencies that consultant belongs to
   * @return filtered list of existing @{@link ConsultantAgency} ready for deletion
   */
  public List<Long> filterAgencyListForDeletion(
      String consultantId, List<CreateConsultantAgencyDTO> newList) {
    var newListIds =
        newList.stream().map(CreateConsultantAgencyDTO::getAgencyId).collect(Collectors.toList());
    var persistedAgencyIds =
        consultantAgencyAdminService.findConsultantAgencies(consultantId).getEmbedded().stream()
            .map(agencyAdminResponse -> agencyAdminResponse.getEmbedded().getId())
            .collect(Collectors.toList());
    return persistedAgencyIds.stream()
        .filter(persistedAgencyId -> !newListIds.contains(persistedAgencyId))
        .collect(Collectors.toList());
  }

  /**
   * Determines which from new agencies should be created.
   *
   * @param consultantId given consultant
   * @param newList new list of agencies that consultant belongs to
   */
  public void filterAgencyListForCreation(
      String consultantId, List<CreateConsultantAgencyDTO> newList) {
    var persistedAgencyIds =
        consultantAgencyAdminService.findConsultantAgencies(consultantId).getEmbedded().stream()
            .map(agencyAdminFullResponse -> agencyAdminFullResponse.getEmbedded().getId())
            .collect(Collectors.toList());
    var filteredList =
        newList.stream()
            .filter(agency -> !persistedAgencyIds.contains(agency.getAgencyId()))
            .collect(Collectors.toList());
    newList.clear();
    newList.addAll(filteredList);
  }

  public void checkAssignedAgenciesMatchConsultantTenant(
      String consultantId, List<CreateConsultantAgencyDTO> agencyList) {

    if (multiTenancyEnabled) {
      ConsultantAdminResponseDTO consultantById =
          consultantAdminService.findConsultantById(consultantId);
      validateConsultantExistsAndHasTenantAssigned(consultantId, consultantById);
      Long consultantTenantId = consultantById.getEmbedded().getTenantId().longValue();
      checkAssignedAgenciesMatchConsultantTenant(agencyList, consultantTenantId);
    }
  }

  private void checkAssignedAgenciesMatchConsultantTenant(
      List<CreateConsultantAgencyDTO> agencyList, Long consultantTenantId) {
    agencyList.stream()
        .map(a -> agencyService.getAgency(a.getAgencyId()))
        .map(
            agency -> {
              if (agency == null) {
                throw new BadRequestException("Agency not found");
              }
              return agency.getTenantId();
            })
        .filter(agencyTenantId -> !agencyTenantId.equals(consultantTenantId))
        .findAny()
        .ifPresent(
            agencyTenantId -> {
              log.warn(
                  "Tenant of the consultant does not match tenant of the agency. "
                      + "Consultant tenant {}, agency tenant {}. Requested agencies to  update: {}",
                  consultantTenantId,
                  agencyTenantId,
                  agencyList);
              throw new BadRequestException(
                  "Tenant of the consultant does not match tenant of the agency");
            });
  }

  private void validateConsultantExistsAndHasTenantAssigned(
      String consultantId, ConsultantAdminResponseDTO consultantById) {
    if (consultantById == null || consultantById.getEmbedded() == null) {
      log.warn("Consultant with id {} not found", consultantId);
      throw new BadRequestException("Consultant not found");
    }
    if (consultantById.getEmbedded().getTenantId() == null) {
      log.warn("Consultant has no tenant assigned ", consultantId);
      throw new BadRequestException("Consultant has no tenant assigned");
    }
  }
}
