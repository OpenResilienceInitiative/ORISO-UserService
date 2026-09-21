package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.agencyadminserivce.generated.ApiClient;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.AdminAgencyControllerApi;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminDepartmentDTO;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.TopicDTO;
import de.caritas.cob.userservice.api.config.apiclient.AgencyAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.model.TopicPermission;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Reads an agency's default topic permission and its departments (ORISO-Admin#1026, slice 6) from
 * AgencyService's admin detail endpoint, with the calling admin's token.
 */
@Service
@RequiredArgsConstructor
public class AgencyTopicPermissionLookup {

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull AgencyAdminServiceApiControllerFactory
      agencyAdminServiceApiControllerFactory;

  /** The agency default and the topics a counsellor of this agency could pick. */
  public record AgencyTopicSettings(TopicPermission defaultPermission, List<Long> topicIds) {}

  /**
   * @return the agency's settings, or empty when AgencyService does not know the agency (404)
   */
  public Optional<AgencyTopicSettings> find(long agencyId) {
    try {
      var response = createControllerApi().getAgency(agencyId);
      var agency = response == null ? null : response.getEmbedded();
      if (agency == null) {
        return Optional.empty();
      }
      return Optional.of(new AgencyTopicSettings(defaultPermission(agency), topicIds(agency)));
    } catch (HttpClientErrorException.NotFound exception) {
      return Optional.empty();
    }
  }

  /**
   * An AgencyService that does not know the setting yet answers without it — that is the behaviour
   * every agency had before the setting existed: {@link TopicPermission#CREATE}.
   */
  private static TopicPermission defaultPermission(AgencyAdminResponseDTO agency) {
    var settings = agency.getSettings();
    if (settings == null || settings.getCounsellorTopicPermission() == null) {
      return TopicPermission.CREATE;
    }
    return TopicPermission.valueOf(settings.getCounsellorTopicPermission().getValue());
  }

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

  private AdminAgencyControllerApi createControllerApi() {
    var controllerApi = agencyAdminServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(controllerApi.getApiClient());
    return controllerApi;
  }

  private void addDefaultHeaders(ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }
}
