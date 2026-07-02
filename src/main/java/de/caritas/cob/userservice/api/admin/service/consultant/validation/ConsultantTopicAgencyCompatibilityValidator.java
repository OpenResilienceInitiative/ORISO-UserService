package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Validates that directly assigned consultant topics are covered by active tenant agencies. */
@Service
@RequiredArgsConstructor
public class ConsultantTopicAgencyCompatibilityValidator {

  private final @NonNull AgencyService agencyService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;

  public void validateGrantTopicsAgainstSelectedAgencies(
      Collection<Long> topicIds, Collection<Long> agencyIds, Long tenantId) {
    validateTopicsCoveredByAgencies(topicIds, agencyIds, tenantId);
  }

  public void validateTopicUpdateAgainstAssignedAgencies(
      String consultantId, Collection<Long> topicIds, Long tenantId) {
    validateTopicsCoveredByAgencies(topicIds, assignedAgencyIdsOf(consultantId), tenantId);
  }

  public void validateCurrentTopicsAgainstSelectedAgencies(
      String consultantId, Collection<Long> selectedAgencyIds) {
    Consultant consultant = retrieveConsultant(consultantId);
    validateTopicsCoveredByAgencies(
        consultantTopicRepository.findTopicIdsByConsultantId(consultantId),
        selectedAgencyIds,
        consultant.getTenantId());
  }

  public void validateCurrentTopicsAgainstAssignedAndAdditionalAgency(
      String consultantId, Long agencyId, Long tenantId) {
    var agencyIds = new ArrayList<>(assignedAgencyIdsOf(consultantId));
    agencyIds.add(agencyId);
    validateTopicsCoveredByAgencies(
        consultantTopicRepository.findTopicIdsByConsultantId(consultantId), agencyIds, tenantId);
  }

  private void validateTopicsCoveredByAgencies(
      Collection<Long> topicIds, Collection<Long> agencyIds, Long tenantId) {
    var selectedTopicIds = normalizedIds(topicIds);
    if (selectedTopicIds.isEmpty()) {
      return;
    }

    var selectedAgencyIds = normalizedIds(agencyIds);
    if (selectedAgencyIds.isEmpty()) {
      throw new BadRequestException(
          String.format(
              "Consultant topic ids %s are not covered by any selected agency",
              selectedTopicIds));
    }

    var agencies = agenciesFor(selectedAgencyIds);
    assertAllSelectedAgenciesResolved(selectedAgencyIds, agencies);
    assertAgenciesBelongToTenant(agencies, tenantId);

    var coveredTopicIds =
        agencies.stream()
            .filter(this::isActive)
            .map(AgencyDTO::getTopicIds)
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    var uncoveredTopicIds =
        selectedTopicIds.stream()
            .filter(topicId -> !coveredTopicIds.contains(topicId))
            .collect(Collectors.toList());

    if (!uncoveredTopicIds.isEmpty()) {
      throw new BadRequestException(
          String.format(
              "Consultant topic ids %s are not covered by active selected/assigned agencies %s",
              uncoveredTopicIds, selectedAgencyIds));
    }
  }

  private List<Long> assignedAgencyIdsOf(String consultantId) {
    return consultantAgencyRepository.findByConsultantIdAndDeleteDateIsNull(consultantId).stream()
        .map(ConsultantAgency::getAgencyId)
        .collect(Collectors.toList());
  }

  private Consultant retrieveConsultant(String consultantId) {
    return consultantRepository
        .findByIdAndDeleteDateIsNull(consultantId)
        .orElseThrow(
            () ->
                new BadRequestException(
                    String.format("Consultant with id %s does not exist", consultantId)));
  }

  private List<AgencyDTO> agenciesFor(List<Long> selectedAgencyIds) {
    var agencies = agencyService.getAgenciesWithoutCaching(selectedAgencyIds);
    if (agencies == null) {
      return Collections.emptyList();
    }
    return agencies.stream().filter(Objects::nonNull).collect(Collectors.toList());
  }

  private void assertAllSelectedAgenciesResolved(
      List<Long> selectedAgencyIds, List<AgencyDTO> agencies) {
    Set<Long> resolvedAgencyIds =
        agencies.stream()
            .map(AgencyDTO::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    var missingAgencyIds =
        selectedAgencyIds.stream()
            .filter(agencyId -> !resolvedAgencyIds.contains(agencyId))
            .collect(Collectors.toList());
    if (!missingAgencyIds.isEmpty()) {
      throw new BadRequestException(
          String.format("Agency ids %s are not valid agencies", missingAgencyIds));
    }
  }

  private void assertAgenciesBelongToTenant(List<AgencyDTO> agencies, Long tenantId) {
    if (tenantId == null) {
      return;
    }
    var mismatchingAgencyIds =
        agencies.stream()
            .filter(agency -> !tenantId.equals(agency.getTenantId()))
            .map(AgencyDTO::getId)
            .collect(Collectors.toList());
    if (!mismatchingAgencyIds.isEmpty()) {
      throw new BadRequestException(
          String.format(
              "Selected agency ids %s do not belong to consultant tenant %s",
              mismatchingAgencyIds, tenantId));
    }
  }

  private boolean isActive(AgencyDTO agency) {
    return !Boolean.TRUE.equals(agency.getOffline());
  }

  private List<Long> normalizedIds(Collection<Long> ids) {
    if (ids == null) {
      return Collections.emptyList();
    }
    return ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
  }
}
