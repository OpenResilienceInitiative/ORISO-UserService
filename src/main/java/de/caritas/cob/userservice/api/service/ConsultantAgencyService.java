package de.caritas.cob.userservice.api.service;

import static java.util.Collections.emptyList;
import static java.util.Objects.isNull;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.UserDtoMapper;
import de.caritas.cob.userservice.api.config.observability.ConsultantAgencyFallbackTelemetry;
import de.caritas.cob.userservice.api.config.observability.ConsultantAgencyFallbackTelemetry.Reason;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Language;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantAgencyService {

  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AgencyService agencyService;
  private final @NonNull AccountManaging accountManager;
  private final @NonNull UserDtoMapper userDtoMapper;
  private final @NonNull ConsultantAgencyFallbackTelemetry fallbackTelemetry;

  @Value("${registration.agency-fallback.consulting-type-id:#{null}}")
  private Integer registrationAgencyFallbackConsultingTypeId;

  /**
   * Save a {@link ConsultantAgency} to the database.
   *
   * @param consultantAgency {@link ConsultantAgency}
   * @return the {@link ConsultantAgency}
   */
  public ConsultantAgency saveConsultantAgency(ConsultantAgency consultantAgency) {
    return consultantAgencyRepository.save(consultantAgency);
  }

  /**
   * Returns a List of {@link ConsultantAgency} Consultants with the given agency ID.
   *
   * @param agencyId agency ID
   * @return {@link List} of {@link ConsultantAgency}
   */
  public List<ConsultantAgency> findConsultantsByAgencyId(Long agencyId) {
    return consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNull(agencyId);
  }

  /**
   * Returns a {@link List} of {@link ConsultantAgency} for the provided agency IDs.
   *
   * @param agencyIds list of agency Ids
   * @return {@link List} of {@link ConsultantAgency}
   */
  public List<ConsultantAgency> getConsultantsOfAgencies(List<Long> agencyIds) {
    return consultantAgencyRepository.findByAgencyIdInAndDeleteDateIsNull(agencyIds);
  }

  /**
   * Returns active {@link ConsultantAgency} rows for the given consultant IDs.
   *
   * @param consultantIds consultant IDs
   * @return {@link List} of {@link ConsultantAgency}
   */
  public List<ConsultantAgency> getConsultantAgenciesByConsultantIds(List<String> consultantIds) {
    if (consultantIds == null || consultantIds.isEmpty()) {
      return emptyList();
    }

    return consultantAgencyRepository.findByConsultantIdInAndDeleteDateIsNull(
        Set.copyOf(consultantIds));
  }

  /**
   * Returns an alphabetically sorted list of {@link ConsultantResponseDTO} depending on the
   * provided agencyId.
   *
   * @param agencyId agency ID
   * @return {@link List} of {@link ConsultantResponseDTO}
   */
  public List<ConsultantResponseDTO> getConsultantsOfAgency(Long agencyId) {

    var agencyList =
        consultantAgencyRepository.findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(
            agencyId);

    if (isNotEmpty(agencyList)) {
      return agencyList.stream()
          .filter(this::onlyConsultantNotMarkedAsDeleted)
          .map(this::convertToConsultantResponseDTO)
          .collect(Collectors.toList());
    }

    return emptyList();
  }

  private boolean onlyConsultantNotMarkedAsDeleted(ConsultantAgency consultantAgency) {
    checkForInconsistencies(consultantAgency);
    return isNull(consultantAgency.getConsultant().getDeleteDate());
  }

  @Transactional(readOnly = true)
  public Set<String> getLanguageCodesOfAgency(long agencyId) {
    var consultantAgencies = findConsultantsByAgencyId(agencyId);

    return consultantAgencies.stream()
        .map(ConsultantAgency::getConsultant)
        .map(Consultant::getLanguages)
        .flatMap(Collection::stream)
        .map(Language::getLanguageCode)
        .map(LanguageCode::name)
        .collect(Collectors.toSet());
  }

  private void checkForInconsistencies(ConsultantAgency agency) {
    checkForMissingAgency(agency);
    checkForMissingConsultant(agency);
  }

  private void checkForMissingAgency(ConsultantAgency agency) {
    if (isNull(agency)) {
      throw new InternalServerErrorException(
          "Database inconsistency: agency is null", LogService::logDatabaseError);
    }
  }

  private void checkForMissingConsultant(ConsultantAgency agency) {
    if (isNull(agency.getConsultant())) {
      throw new InternalServerErrorException(
          String.format(
              "Database inconsistency: could not get assigned consultant for agency with id %s",
              agency.getAgencyId()),
          LogService::logDatabaseError);
    }
  }

  private ConsultantResponseDTO convertToConsultantResponseDTO(ConsultantAgency agency) {
    var consultant = agency.getConsultant();
    var id = consultant.getId();

    var consultantDto =
        new ConsultantResponseDTO()
            .consultantId(id)
            .firstName(consultant.getFirstName())
            .lastName(consultant.getLastName())
            .username(consultant.getUsername())
            .isSupervisor(consultant.isSupervisor());

    accountManager
        .findConsultant(id)
        .ifPresent(
            consultantMap -> consultantDto.displayName(userDtoMapper.displayNameOf(consultantMap)));

    return consultantDto;
  }

  /**
   * Returns all agencies of given consultant.
   *
   * @param consultantId the id of the consultant
   * @return the related agencies
   */
  public List<AgencyDTO> getOnlineAgenciesOfConsultant(String consultantId) {
    var agencyIds =
        consultantAgencyRepository.findByConsultantId(consultantId).stream()
            .filter(agency -> agency.getDeleteDate() == null)
            .map(ConsultantAgency::getAgencyId)
            .collect(Collectors.toList());

    if (agencyIds.isEmpty()) {
      return emptyList();
    }

    var consultantTopicIds = consultantTopicRepository.findTopicIdsByConsultantId(consultantId);

    try {
      List<AgencyDTO> agencies =
          filterOutOfflineAgencies(agencyService.getAgenciesNotCached(agencyIds));
      if (agencies.isEmpty()) {
        recordFallback(Reason.EMPTY_RESPONSE);
        return agenciesWithLocalTopicAssignments(agencyIds, consultantTopicIds);
      }
      return enrichAgenciesWithConsultantTopicIds(agencies, consultantTopicIds);
    } catch (RuntimeException exception) {
      recordFallback(Reason.DEPENDENCY_ERROR);
      return agenciesWithLocalTopicAssignments(agencyIds, consultantTopicIds);
    }
  }

  private void recordFallback(Reason reason) {
    fallbackTelemetry
        .record(reason)
        .ifPresent(
            suppressed ->
                log.warn(
                    "AgencyService consultant-agency fallback active: reason={}, "
                        + "suppressedSincePreviousWarning={}. "
                        + "Per-call failures remain available in outbound dependency metrics.",
                    reason.tagValue(),
                    suppressed));
  }

  private List<AgencyDTO> enrichAgenciesWithConsultantTopicIds(
      List<AgencyDTO> agencies, List<Long> consultantTopicIds) {
    if (consultantTopicIds == null || consultantTopicIds.isEmpty()) {
      return agencies;
    }

    agencies.forEach(
        agency -> {
          if (agency.getTopicIds() == null || agency.getTopicIds().isEmpty()) {
            agency.setTopicIds(consultantTopicIds);
          }
        });
    return agencies;
  }

  private List<AgencyDTO> filterOutOfflineAgencies(List<AgencyDTO> agencies) {
    return agencies.stream()
        .filter(a -> !Boolean.TRUE.equals(a.getOffline()))
        .collect(Collectors.toList());
  }

  private List<AgencyDTO> agenciesWithLocalTopicAssignments(
      List<Long> agencyIds, List<Long> topicIds) {
    Map<Long, Integer> consultingTypesByAgency =
        sessionRepository.findLowestConsultingTypeIdsByAgencyIds(Set.copyOf(agencyIds)).stream()
            .collect(
                Collectors.toMap(
                    SessionRepository.AgencyConsultingTypeProjection::getAgencyId,
                    SessionRepository.AgencyConsultingTypeProjection::getConsultingTypeId));

    return agencyIds.stream()
        .map(
            agencyId ->
                new AgencyDTO()
                    .id(agencyId)
                    .offline(false)
                    .topicIds(topicIds)
                    .consultingType(
                        consultingTypesByAgency.getOrDefault(
                            agencyId, registrationAgencyFallbackConsultingTypeId)))
        .collect(Collectors.toList());
  }
}
