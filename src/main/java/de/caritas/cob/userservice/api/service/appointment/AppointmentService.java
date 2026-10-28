package de.caritas.cob.userservice.api.service.appointment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentAgencyServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentAskerServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.apiclient.AppointmentConsultantServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.RoutineTaskAuthentication;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.api.service.httpheader.TenantHeaderSupplier;
import de.caritas.cob.userservice.appointmentservice.generated.ApiClient;
import de.caritas.cob.userservice.appointmentservice.generated.web.AgencyApi;
import de.caritas.cob.userservice.appointmentservice.generated.web.ConsultantApi;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.AgencyConsultantSyncRequestDTO;
import de.caritas.cob.userservice.appointmentservice.generated.web.model.AskerDTO;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/** Service class to communicate with the AppointmentService. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppointmentService {

  private final @NonNull AppointmentConsultantServiceApiControllerFactory
      appointmentConsultantServiceApiControllerFactory;

  private final @NonNull AppointmentAgencyServiceApiControllerFactory
      appointmentAgencyServiceApiControllerFactory;

  private final @NonNull AppointmentAskerServiceApiControllerFactory
      appointmentAskerServiceApiControllerFactory;

  private final @NonNull SecurityHeaderSupplier securityHeaderSupplier;
  private final @NonNull TenantHeaderSupplier tenantHeaderSupplier;
  private final @NonNull IdentityAuthentication identityAuthentication;
  private final @NonNull IdentityClientConfig identityClientConfig;

  @Value("${feature.appointment.enabled}")
  private boolean appointmentFeatureEnabled;

  public void createConsultant(ConsultantAdminResponseDTO consultantAdminResponseDTO)
      throws RestClientException {
    if (!appointmentFeatureEnabled) {
      return;
    }

    if (consultantAdminResponseDTO != null) {
      ObjectMapper mapper = getObjectMapper(false);
      ConsultantApi appointmentConsultantApi =
          this.appointmentConsultantServiceApiControllerFactory.createControllerApi();
      addTaskHeaders(appointmentConsultantApi.getApiClient(), TaskPurpose.APPOINTMENT_SYNC);
      de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO consultant =
          getConsultantDTO(consultantAdminResponseDTO, mapper);
      appointmentConsultantApi.createConsultant(consultant);
    }
  }

  /** Creation ledger owns the exact returned native account and the captured original tenant. */
  public void createOwnedConsultant(ConsultantAdminResponseDTO response, Long capturedTenant) {
    requireOwnedOutsideSql();
    if (!appointmentFeatureEnabled
        || response == null
        || response.getEmbedded() == null
        || capturedTenant == null
        || capturedTenant < 1)
      throw new IllegalStateException("Owned appointment creation is unavailable");
    var dto = getConsultantDTO(response, getObjectMapper(false));
    if (dto == null || dto.getId() == null || !dto.getId().equals(response.getEmbedded().getId()))
      throw new IllegalArgumentException("Owned appointment payload differs from account");
    var api = appointmentConsultantServiceApiControllerFactory.createControllerApi();
    addCapturedTaskHeaders(api.getApiClient(), TaskPurpose.APPOINTMENT_SYNC, capturedTenant);
    try {
      var result = api.createConsultantWithHttpInfo(dto);
      if (result == null || !HttpStatus.OK.equals(result.getStatusCode()))
        throw new IllegalStateException("Owned appointment creation is unacknowledged");
    } catch (RestClientException failure) {
      throw new IllegalStateException("Owned appointment creation outcome is unconfirmed");
    }
  }

  /** Cleanup does not depend on a consultant row which may already have rolled back. */
  public void deleteOwnedCreationConsultant(String accountId, Long capturedTenant) {
    requireOwnedOutsideSql();
    if (!appointmentFeatureEnabled
        || accountId == null
        || accountId.isBlank()
        || capturedTenant == null
        || capturedTenant < 1)
      throw new IllegalStateException("Owned appointment cleanup remains pending");
    var api = appointmentConsultantServiceApiControllerFactory.createControllerApi();
    addCapturedTaskHeaders(api.getApiClient(), TaskPurpose.APPOINTMENT_CLEANUP, capturedTenant);
    try {
      var result = api.deleteConsultantWithHttpInfo(accountId);
      if (result == null || !HttpStatus.OK.equals(result.getStatusCode()))
        throw new IllegalStateException("Owned appointment cleanup remains unacknowledged");
    } catch (HttpClientErrorException failure) {
      if (!HttpStatus.NOT_FOUND.equals(failure.getStatusCode()))
        throw new IllegalStateException("Owned appointment cleanup outcome is unconfirmed");
    } catch (RestClientException failure) {
      throw new IllegalStateException("Owned appointment cleanup outcome is unconfirmed");
    }
  }

  private static void requireOwnedOutsideSql() {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive())
      throw new IllegalStateException("Owned appointment transport cannot run inside SQL");
  }

  private void addCapturedTaskHeaders(ApiClient api, TaskPurpose purpose, Long tenant) {
    String token =
        RoutineTaskAuthentication.accessToken(
            purpose, identityClientConfig, identityAuthentication);
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(token);
    headers.set("tenantId", tenant.toString());
    headers.forEach((key, value) -> api.addDefaultHeader(key, value.getFirst()));
  }

  private de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO
      getConsultantDTO(ConsultantAdminResponseDTO consultantAdminResponseDTO, ObjectMapper mapper) {
    de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO consultant =
        null;
    try {
      consultant =
          mapper.readValue(
              mapper.writeValueAsString(consultantAdminResponseDTO.getEmbedded()),
              de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO
                  .class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
    return consultant;
  }

  public void syncConsultantData(Consultant consultant) {
    ConsultantAdminResponseDTO consultantAdminResponseDTO = new ConsultantAdminResponseDTO();
    ConsultantDTO consultantEmbeded = new ConsultantDTO();
    consultantEmbeded.setId(consultant.getId());
    consultantEmbeded.setFirstname(consultant.getFirstName());
    consultantEmbeded.setLastname(consultant.getLastName());
    consultantEmbeded.setEmail(consultant.getEmail());
    consultantEmbeded.setAbsent(consultant.isAbsent());
    consultantAdminResponseDTO.setEmbedded(consultantEmbeded);
    updateConsultant(consultantAdminResponseDTO);
  }

  public void updateConsultant(ConsultantAdminResponseDTO consultantAdminResponseDTO) {
    if (!appointmentFeatureEnabled) {
      return;
    }
    ConsultantApi appointmentConsultantApi =
        this.appointmentConsultantServiceApiControllerFactory.createControllerApi();

    if (consultantAdminResponseDTO != null) {
      ObjectMapper mapper = getObjectMapper(false);
      addTaskHeaders(appointmentConsultantApi.getApiClient(), TaskPurpose.APPOINTMENT_SYNC);
      try {
        de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO consultant =
            mapper.readValue(
                mapper.writeValueAsString(consultantAdminResponseDTO.getEmbedded()),
                de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO
                    .class);
        appointmentConsultantApi.updateConsultant(consultant.getId(), consultant);
      } catch (Exception e) {
        log.error(e.getMessage());
      }
    }
  }

  /**
   * ObjectMapper provider method for injecting Mocks while testing
   *
   * @param failOnUnknownProperties DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
   * @return ObjectMapper
   */
  protected ObjectMapper getObjectMapper(boolean failOnUnknownProperties) {
    return new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
  }

  public void deleteConsultant(String consultantId) {
    if (!appointmentFeatureEnabled) {
      return;
    }
    ConsultantApi appointmentConsultantApi =
        this.appointmentConsultantServiceApiControllerFactory.createControllerApi();

    if (consultantId != null && !consultantId.isEmpty()) {
      addTaskHeaders(appointmentConsultantApi.getApiClient(), TaskPurpose.APPOINTMENT_CLEANUP);
      try {
        appointmentConsultantApi.deleteConsultant(consultantId);
      } catch (HttpClientErrorException ex) {
        acceptDeletionIfConsultantNotFoundInAppointmentService(ex, consultantId);
      }
    }
  }

  private void acceptDeletionIfConsultantNotFoundInAppointmentService(
      HttpClientErrorException ex, String consultantId) {
    if (!HttpStatus.NOT_FOUND.equals(ex.getStatusCode())) {
      throw ex;
    } else {
      log.warn(
          "No consultant with id {} was not found in appointmentService. Proceeding with deletion.",
          consultantId);
    }
  }

  @SuppressWarnings("Duplicates")
  private void addTaskHeaders(ApiClient apiClient, TaskPurpose purpose) {
    String token =
        RoutineTaskAuthentication.accessToken(
            purpose, identityClientConfig, identityAuthentication);
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(token);
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  public void syncAgencies(String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    if (!appointmentFeatureEnabled) {
      return;
    }

    AgencyApi controllerApi =
        this.appointmentAgencyServiceApiControllerFactory.createControllerApi();

    addTaskHeaders(controllerApi.getApiClient(), TaskPurpose.APPOINTMENT_SYNC);
    var agencies =
        agencyList.stream()
            .map(CreateConsultantAgencyDTO::getAgencyId)
            .collect(Collectors.toList());
    AgencyConsultantSyncRequestDTO request = new AgencyConsultantSyncRequestDTO();
    request.setAgencies(agencies);
    request.setConsultantId(consultantId);
    controllerApi.agencyConsultantsSync(request);
  }

  public void deleteAsker(String askerId) {
    if (!appointmentFeatureEnabled) {
      return;
    }
    de.caritas.cob.userservice.appointmentservice.generated.web.AskerApi controllerApi =
        this.appointmentAskerServiceApiControllerFactory.createControllerApi();
    addTaskHeaders(controllerApi.getApiClient(), TaskPurpose.APPOINTMENT_CLEANUP);
    controllerApi.deleteAskerData(askerId);
  }

  public void updateAskerEmail(String askerId, String email) {
    if (!appointmentFeatureEnabled) {
      return;
    }
    de.caritas.cob.userservice.appointmentservice.generated.web.AskerApi askerApi =
        this.appointmentAskerServiceApiControllerFactory.createControllerApi();
    addDefaultHeaders(askerApi.getApiClient());
    try {
      de.caritas.cob.userservice.appointmentservice.generated.web.model.AskerDTO askerDTO =
          new AskerDTO().id(askerId).email(email);
      askerApi.updateAskerEmail(askerId, askerDTO);
    } catch (Exception e) {
      log.error(e.getMessage());
    }
  }

  private void addDefaultHeaders(
      de.caritas.cob.userservice.appointmentservice.generated.ApiClient apiClient) {
    HttpHeaders headers = this.securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders();
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  public void patchConsultant(String consultantId, String displayName) {
    if (!appointmentFeatureEnabled) {
      return;
    }
    ConsultantApi appointmentConsultantApi =
        this.appointmentConsultantServiceApiControllerFactory.createControllerApi();

    if (consultantId != null && !consultantId.isEmpty()) {
      addTaskHeaders(appointmentConsultantApi.getApiClient(), TaskPurpose.APPOINTMENT_SYNC);
      try {
        appointmentConsultantApi.patchConsultant(
            consultantId,
            new de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO()
                .displayName(displayName));
      } catch (HttpClientErrorException ex) {
        acceptDeletionIfConsultantNotFoundInAppointmentService(ex, consultantId);
      }
    }
  }
}
