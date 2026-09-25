package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminDepartmentDTO;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.TopicDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Reads AgencyService's admin detail endpoint with the caller's token: only that view carries the
 * delete date, and its tenant filter hides other tenants' agencies.
 */
@Component
@RequiredArgsConstructor
public class AgencyServiceAgencyFacts implements AgencyFacts {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull AgencyAdminServiceApiControllerFactory controllerFactory;

  @Override
  public Optional<Agency> find(long agencyId) {
    try {
      var response = controllerApi().getAgency(agencyId);
      var agency = response == null ? null : response.getEmbedded();
      if (agency == null) {
        return Optional.empty();
      }
      return Optional.of(
          new Agency(
              agency.getId(),
              agency.getTenantId(),
              isDeleted(agency.getDeleteDate()),
              topicIds(agency)));
    } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden exception) {
      return Optional.empty();
    }
  }

  /** Departments carry the topic ID even when the topic name lookup failed. */
  private static List<Long> topicIds(AgencyAdminResponseDTO agency) {
    if (agency.getDepartments() != null && !agency.getDepartments().isEmpty()) {
      return agency.getDepartments().stream()
          .map(AgencyAdminDepartmentDTO::getTopicId)
          .filter(Objects::nonNull)
          .distinct()
          .toList();
    }
    return agency.getTopics() == null
        ? List.of()
        : agency.getTopics().stream().map(TopicDTO::getId).filter(Objects::nonNull).toList();
  }

  /** AgencyService writes the delete date with String.valueOf, so a live agency says "null". */
  static boolean isDeleted(String deleteDate) {
    return deleteDate != null && !deleteDate.isBlank() && !"null".equalsIgnoreCase(deleteDate);
  }

  private AdminAgencyControllerApi controllerApi() {
    var api = controllerFactory.createControllerApi();
    HttpHeaders headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach(
        (key, value) -> api.getApiClient().addDefaultHeader(key, value.iterator().next()));
    return api;
  }
}
