package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyTopicsDTO;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Validates that directly assigned consultant topics are covered by tenant agencies. */
@Service
@RequiredArgsConstructor
public class ConsultantTopicAgencyCompatibilityValidator {

  private final @NonNull AgencyService agencyService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;

  /**
   * @return the topics per selected centre (#1264): each topic is stored for every selected centre
   *     that offers it
   */
  public Map<Long, Set<Long>> validateGrantTopicsAgainstSelectedAgencies(
      Collection<Long> topicIds, Collection<Long> agencyIds, Long tenantId) {
    var agencies = validateTopicsCoveredByAgencies(topicIds, agencyIds, tenantId);
    return distributeOverOfferingAgencies(topicIds, agencies);
  }

  public void validateTopicUpdateAgainstAssignedAgencies(
      String consultantId, Collection<Long> topicIds, Long tenantId) {
    validateTopicsCoveredByAgencies(topicIds, assignedAgencyIdsOf(consultantId), tenantId);
  }

  /**
   * Validates an admin topic update and returns the target topics per counselling centre (#1264),
   * or {@code null} when the request leaves topics untouched.
   *
   * <p>{@code topicsByAgency} wins when it is non-empty: each centre must be assigned to the
   * consultant and must offer every topic listed for it. Otherwise the flat {@code topicIds} are
   * stored for every assigned centre that offers them, so existing clients keep their behaviour.
   */
  public Map<Long, Set<Long>> resolveTopicUpdate(
      String consultantId,
      Collection<Long> topicIds,
      List<ConsultantAgencyTopicsDTO> topicsByAgency,
      Long tenantId) {
    var assignedAgencyIds = assignedAgencyIdsOf(consultantId);
    // The generated DTO defaults an absent list to [], so an empty list counts as "not sent".
    if (topicsByAgency != null && !topicsByAgency.isEmpty()) {
      return resolvePerAgency(topicsByAgency, assignedAgencyIds, tenantId);
    }
    var agencies = validateTopicsCoveredByAgencies(topicIds, assignedAgencyIds, tenantId);
    if (topicIds == null) {
      return null;
    }
    return distributeOverOfferingAgencies(topicIds, agencies);
  }

  private Map<Long, Set<Long>> distributeOverOfferingAgencies(
      Collection<Long> topicIds, List<AgencyDTO> agencies) {
    var selectedTopicIds = Set.copyOf(normalizedIds(topicIds));
    Map<Long, Set<Long>> target = new TreeMap<>();
    agencies.forEach(
        agency -> {
          var offered = agency.getTopicIds() == null ? List.<Long>of() : agency.getTopicIds();
          var topics =
              offered.stream()
                  .filter(selectedTopicIds::contains)
                  .collect(Collectors.toCollection(TreeSet::new));
          if (!topics.isEmpty()) {
            target.put(agency.getId(), topics);
          }
        });
    return target;
  }

  private Map<Long, Set<Long>> resolvePerAgency(
      List<ConsultantAgencyTopicsDTO> topicsByAgency, List<Long> assignedAgencyIds, Long tenantId) {
    Map<Long, Set<Long>> target = new TreeMap<>();
    for (var entry : topicsByAgency) {
      if (entry == null || entry.getAgencyId() == null) {
        throw new BadRequestException("topicsByAgency entries need an agencyId");
      }
      target
          .computeIfAbsent(entry.getAgencyId(), id -> new TreeSet<>())
          .addAll(normalizedIds(entry.getTopicIds()));
    }
    var unassigned =
        target.keySet().stream().filter(id -> !assignedAgencyIds.contains(id)).toList();
    if (!unassigned.isEmpty()) {
      throw new BadRequestException(
          String.format("Agency ids %s are not assigned to the consultant", unassigned));
    }
    if (target.isEmpty()) {
      return target;
    }
    var agencyIds = List.copyOf(target.keySet());
    var agencies = agenciesFor(agencyIds);
    assertAllSelectedAgenciesResolved(agencyIds, agencies);
    assertAgenciesBelongToTenant(agencies, tenantId);
    agencies.forEach(
        agency -> {
          var offered = agency.getTopicIds() == null ? List.<Long>of() : agency.getTopicIds();
          var uncovered =
              target.get(agency.getId()).stream().filter(id -> !offered.contains(id)).toList();
          if (!uncovered.isEmpty()) {
            throw new BadRequestException(
                String.format(
                    "Consultant topic ids %s are not offered by agency %s (coverage: %s)",
                    uncovered, agency.getId(), offered));
          }
        });
    target.values().removeIf(Set::isEmpty);
    return target;
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
    validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
        consultantId, List.of(agencyId), tenantId);
  }

  public void validateCurrentTopicsAgainstAssignedAndAdditionalAgencies(
      String consultantId, Collection<Long> additionalAgencyIds, Long tenantId) {
    var coveredAgencyIds = new ArrayList<>(assignedAgencyIdsOf(consultantId));
    coveredAgencyIds.addAll(normalizedIds(additionalAgencyIds));
    validateTopicsCoveredByAgencies(
        consultantTopicRepository.findTopicIdsByConsultantId(consultantId),
        coveredAgencyIds,
        tenantId);
  }

  private List<AgencyDTO> validateTopicsCoveredByAgencies(
      Collection<Long> topicIds, Collection<Long> agencyIds, Long tenantId) {
    var selectedTopicIds = normalizedIds(topicIds);
    var selectedAgencyIds = normalizedIds(agencyIds);
    if (selectedAgencyIds.isEmpty()) {
      if (selectedTopicIds.isEmpty()) {
        return List.of();
      }
      throw new BadRequestException(
          String.format(
              "Consultant topic ids %s are not covered by any selected agency", selectedTopicIds));
    }

    var agencies = agenciesFor(selectedAgencyIds);
    assertAllSelectedAgenciesResolved(selectedAgencyIds, agencies);
    assertAgenciesBelongToTenant(agencies, tenantId);

    if (selectedTopicIds.isEmpty()) {
      return agencies;
    }

    // Offline agencies count towards topic coverage on purpose: a freshly created agency is
    // offline until it has an assigned consultant, so filtering them out here would make it
    // impossible to ever assign the first consultant to a new agency (bootstrap deadlock).
    var coveredTopicIds =
        agencies.stream()
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
              "Consultant topic ids %s are not covered by selected/assigned agencies %s (coverage: %s)",
              uncoveredTopicIds, selectedAgencyIds, describeCoverage(agencies)));
    }
    return agencies;
  }

  /**
   * Renders {@code agencyId=[coveredTopicIds]} pairs so a rejection tells the admin which agency
   * was actually evaluated and what it covers, instead of only listing the agency ids.
   */
  private String describeCoverage(List<AgencyDTO> agencies) {
    return agencies.stream()
        .map(
            agency ->
                String.format(
                    "%s=%s",
                    agency.getId(),
                    agency.getTopicIds() == null ? List.of() : agency.getTopicIds()))
        .collect(Collectors.joining(", ", "{", "}"));
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

  /**
   * Reads the agencies uncached on purpose. Topic coverage lives in the AgencyService and is edited
   * there (add a topic to an existing agency), while the userservice caches agencies in {@code
   * agencyCache} for three hours with no cross-service invalidation. Serving this validation from
   * that cache rejects topics the agency already covers until the entry expires.
   */
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

  private List<Long> normalizedIds(Collection<Long> ids) {
    if (ids == null) {
      return Collections.emptyList();
    }
    return ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
  }
}
