package de.caritas.cob.userservice.api.helper;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Verifier class for agency verifications. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyVerifier {

  private final @NonNull AgencyService agencyService;
  private final @NonNull SessionRepository sessionRepository;

  @Value("${registration.agency-fallback.consulting-type-id:#{null}}")
  private Integer registrationAgencyFallbackConsultingTypeId;

  /**
   * Checks if the given agency ID {@link AgencyDTO#getId()} is assigned to the provided consulting
   * ID and returns the corresponding agency as {@link AgencyDTO}.
   *
   * @param agencyId {@link AgencyDTO#getId()}
   * @param consultingTypeId the consulting Id
   * @return {@link AgencyDTO} or null if agency is not found
   */
  public AgencyDTO getVerifiedAgency(Long agencyId, int consultingTypeId) {
    var agencyDTO = agencyService.getAgencyWithoutCaching(agencyId);
    if (isNull(agencyDTO)) {
      agencyDTO = resolveRegistrationAgencyFallback(agencyId, consultingTypeId).orElse(null);
    }

    if (nonNull(agencyDTO) && !agencyDTO.getConsultingType().equals(consultingTypeId)) {
      throw new BadRequestException(
          String.format(
              "The provided agency with id %s is not assigned to the provided consulting type %s",
              agencyId, consultingTypeId));
    }

    return agencyDTO;
  }

  public void checkIfConsultingTypeMatchesToAgency(UserDTO userDTO) {
    try {
      if (isNull(
          getVerifiedAgency(
              userDTO.getAgencyId(), Integer.parseInt(userDTO.getConsultingType())))) {
        throw new BadRequestException(
            String.format(
                "Agency with id %s does not match to consulting" + " type %s",
                userDTO.getAgencyId(), userDTO.getConsultingType()));
      }
    } catch (HttpClientErrorException.Forbidden
        | HttpClientErrorException.Unauthorized
        | HttpClientErrorException.NotFound e) {
      log.warn(
          "Skipping strict agency verification during registration due to downstream agency lookup error: {}",
          e.getMessage());
    }
  }

  private Optional<AgencyDTO> resolveRegistrationAgencyFallback(
      Long agencyId, int consultingTypeId) {
    return resolveConsultingTypeForFallback(agencyId, consultingTypeId)
        .map(
            consultingType ->
                new AgencyDTO().id(agencyId).consultingType(consultingType).teamAgency(false));
  }

  private Optional<Integer> resolveConsultingTypeForFallback(Long agencyId, int consultingTypeId) {
    var consultingTypesFromSessions =
        sessionRepository.findDistinctConsultingTypeIdsByAgencyId(agencyId, PageRequest.of(0, 1));
    if (!consultingTypesFromSessions.isEmpty()) {
      return Optional.of(consultingTypesFromSessions.get(0));
    }

    if (registrationAgencyFallbackConsultingTypeId != null
        && registrationAgencyFallbackConsultingTypeId.equals(consultingTypeId)) {
      return Optional.of(registrationAgencyFallbackConsultingTypeId);
    }

    return Optional.empty();
  }
}
