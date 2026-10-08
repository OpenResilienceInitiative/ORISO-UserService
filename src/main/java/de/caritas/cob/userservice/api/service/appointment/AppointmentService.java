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
      addTechnicalUserHeaders(
          appointmentConsultantApi.getApiClient(),
          de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_SYNC);
      de.caritas.cob.userservice.appointmentservice.generated.web.model.ConsultantDTO consultant =
          getConsultantDTO(consultantAdminResponseDTO, mapper);
      appointmentConsultantApi.createConsultant(consultant);
    }
  }

  /** Only the unfinished native creation's exact UUID/tenant can record a cleanup obligation. */
  public void createOwnedConsultant(
      ConsultantAdminResponseDTO response,
      Long tenantId,
      de.caritas.cob.userservice.api.port.out.OwnedAppointmentEffect effect) {
    if (!appointmentFeatureEnabled)
      throw new IllegalStateException("Owned appointment creation is disabled");
    if (response == null || response.getEmbedded() == null || effect == null)
      throw new IllegalArgumentException("Original appointment creation authority required");
    var consultant = getConsultantDTO(response, getObjectMapper(false));
    String id = response.getEmbedded().getId();
    if (id == null || !id.equals(consultant.getId()))
      throw new IllegalArgumentException("Appointment payload differs from its owned account");
    var api = appointmentConsultantServiceApiControllerFactory.createControllerApi();
    addCapturedTenantTaskHeaders(
        api.getApiClient(),
        de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_SYNC,
        tenantId);
    effect.started(id, tenantId);
    var created = api.createConsultantWithHttpInfo(consultant);
    if (created == null || !HttpStatus.OK.equals(created.getStatusCode()))
      throw new RestClientException("Appointment creation outcome is unacknowledged");
    effect.created(id); // the bundled POST contract keys this record by the supplied native UUID
  }

  /** Restart cleanup uses the captured tenant, independently of a rolled-back consultant row. */
  public void deleteOwnedCreationConsultant(String id, Long tenantId) {
    if (!appointmentFeatureEnabled)
      throw new IllegalStateException("Owned appointment cleanup is disabled and remains pending");
    if (id == null || id.isBlank())
      throw new IllegalArgumentException("Owned consultant ID required");
    var api = appointmentConsultantServiceApiControllerFactory.createControllerApi();
    addCapturedTenantTaskHeaders(
        api.getApiClient(),
        de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_CLEANUP,
        tenantId);
    try {
      api.deleteConsultant(id);
    } catch (HttpClientErrorException failure) {
      acceptDeletionIfConsultantNotFoundInAppointmentService(failure, id);
    }
  }

  private void addCapturedTenantTaskHeaders(
      ApiClient api, de.caritas.cob.userservice.api.config.auth.TaskIdentity task, Long tenantId) {
    var login = identityAuthentication.loginTask(identityClientConfig.getTaskIdentity(task));
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(login.accessToken());
    if (tenantId != null) headers.set("tenantId", tenantId.toString());
    else headers.remove("tenantId");
    headers.forEach((key, value) -> api.addDefaultHeader(key, value.iterator().next()));
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
      addTechnicalUserHeaders(
          appointmentConsultantApi.getApiClient(),
          de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_SYNC);
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
      addTechnicalUserHeaders(
          appointmentConsultantApi.getApiClient(),
          de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_CLEANUP);
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
  private void addTechnicalUserHeaders(
      ApiClient apiClient, de.caritas.cob.userservice.api.config.auth.TaskIdentity task) {
    var techUser = identityClientConfig.getTaskIdentity(task);
    var identityLogin = identityAuthentication.loginTask(techUser);
    var headers = securityHeaderSupplier.getKeycloakAndCsrfHttpHeaders(identityLogin.accessToken());
    tenantHeaderSupplier.addTenantHeader(headers);
    headers.forEach((key, value) -> apiClient.addDefaultHeader(key, value.iterator().next()));
  }

  public void syncAgencies(String consultantId, List<CreateConsultantAgencyDTO> agencyList) {
    if (!appointmentFeatureEnabled) {
      return;
    }

    AgencyApi controllerApi =
        this.appointmentAgencyServiceApiControllerFactory.createControllerApi();

    addTechnicalUserHeaders(
        controllerApi.getApiClient(),
        de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_SYNC);
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
    addTechnicalUserHeaders(
        controllerApi.getApiClient(),
        de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_CLEANUP);
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
      addTechnicalUserHeaders(
          appointmentConsultantApi.getApiClient(),
          de.caritas.cob.userservice.api.config.auth.TaskIdentity.APPOINTMENT_SYNC);
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
