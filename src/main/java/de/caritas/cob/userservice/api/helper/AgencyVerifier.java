package de.caritas.cob.userservice.api.helper;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/** Verifier class for agency verifications. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgencyVerifier {

  private final @NonNull AgencyService agencyService;

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
          getVerifiedAgency(userDTO.getAgencyId(), Integer.parseInt(userDTO.getConsultingType())))) {
        throw new BadRequestException(
            String.format(
                "Agency with id %s does not match to consulting" + " type %s",
                userDTO.getAgencyId(), userDTO.getConsultingType()));
      }
    } catch (HttpClientErrorException.Forbidden | HttpClientErrorException.Unauthorized e) {
      log.warn(
          "Skipping strict agency verification during registration due to downstream auth error: {}",
          e.getMessage());
    }
  }
}
