package de.caritas.cob.userservice.api.facade.userdata;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * User data for a Beratungsstellen-Admin ({@code restricted-agency-admin} / {@code agency-admin}
 * without a consultant role).
 *
 * <p>The identity part comes from Keycloak like for every other admin. The assigned agencies come
 * from the {@code admin_agency} relation — the same source that fills the "Beratungsstellen" column
 * of the admin listings and that AgencyService uses to scope the agency search for this role. Until
 * ORISO-UserService#1101 the list was hardcoded empty, so the Admin UI could not route an agency
 * admin to their own agency.
 *
 * <p>Fails open on the agency lookup only: an unreachable AgencyService degrades to an empty list
 * instead of breaking login, mirroring {@link ConsultantDataProvider}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgencyAdminDataProvider {

  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull KeycloakUserDataProvider keycloakUserDataProvider;
  private final @NonNull AdminAgencyRepository adminAgencyRepository;
  private final @NonNull AgencyService agencyService;

  public UserDataResponseDTO retrieveData() {
    var userData = keycloakUserDataProvider.retrieveAuthenticatedUserData();
    var agencyIds = assignedAgencyIds();
    if (!agencyIds.isEmpty()) {
      userData.setAgencies(agencyDTOsOf(agencyIds));
    }
    return userData;
  }

  private List<Long> assignedAgencyIds() {
    return adminAgencyRepository.findByAdminId(authenticatedUser.getUserId()).stream()
        .map(AdminAgency::getAgencyId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private List<AgencyDTO> agencyDTOsOf(List<Long> agencyIds) {
    try {
      return agencyService.getAgencies(agencyIds);
    } catch (RestClientResponseException e) {
      log.warn(
          "Could not load agencies for agency admin {}: status={}, body={}",
          authenticatedUser.getUserId(),
          e.getStatusCode().value(),
          e.getResponseBodyAsString());
      return List.of();
    } catch (RestClientException e) {
      log.warn(
          "Could not load agencies for agency admin {}; returning user data without agencies",
          authenticatedUser.getUserId(),
          e);
      return List.of();
    }
  }
}
