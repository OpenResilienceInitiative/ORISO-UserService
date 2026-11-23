package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class KeycloakAccountLifecycleClientTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final UUID ATTEMPT = UUID.fromString("1c8c4d29-2fde-4bcf-b5b7-1c93be4df87f");

  @Mock private RestTemplate restTemplate;
  @Mock private IdentityClientConfig identityClientConfig;
  @Mock private TaskActorConfiguration taskActors;
  @Mock private TaskContextProofSigner proofSigner;

  private KeycloakAccountLifecycleClient client;

  @BeforeEach
  void setUp() {
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl("https://keycloak.example/auth/");
    keycloak.setRealm("online-beratung");
    client =
        new KeycloakAccountLifecycleClient(
            restTemplate, keycloak, identityClientConfig, taskActors, proofSigner);
  }

  @Test
  void createsLifecycleRequestWithTheProvisioningActorProof() throws Exception {
    var body = JSON.readTree("{\"email\":\"user@example.org\"}");
    var binding =
        new TaskActorCredentials(
            TaskActor.ACCOUNT_CREATE,
            "backend-account-create",
            "ignored-in-test",
            "8f5b9547-7a2e-41a4-b43c-fb8af2821102");
    when(taskActors.require(TaskPurpose.ACCOUNT_PROVISIONING)).thenReturn(binding);
    when(identityClientConfig.taskAccessToken(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenReturn("access-token");
    when(proofSigner.sign(
            TaskPurpose.ACCOUNT_PROVISIONING, binding, "account.create", "PUT", ATTEMPT, body))
        .thenReturn("task-proof");
    when(restTemplate.exchange(
            any(URI.class),
            eq(HttpMethod.PUT),
            any(HttpEntity.class),
            eq(com.fasterxml.jackson.databind.JsonNode.class)))
        .thenReturn(ResponseEntity.ok(JSON.readTree("{\"state\":\"PENDING\"}")));

    assertThat(client.create(ATTEMPT, body).path("state").asText()).isEqualTo("PENDING");

    @SuppressWarnings("unchecked")
    ArgumentCaptor<HttpEntity<?>> request = ArgumentCaptor.forClass(HttpEntity.class);
    ArgumentCaptor<URI> endpoint = ArgumentCaptor.forClass(URI.class);
    verify(restTemplate)
        .exchange(
            endpoint.capture(),
            eq(HttpMethod.PUT),
            request.capture(),
            eq(com.fasterxml.jackson.databind.JsonNode.class));
    assertThat(endpoint.getValue())
        .hasToString(
            "https://keycloak.example/auth/realms/online-beratung/"
                + "oriso-account-lifecycle/v1/attempts/"
                + ATTEMPT);
    assertThat(request.getValue().getBody()).isEqualTo(body);
    assertThat(request.getValue().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
        .isEqualTo("Bearer access-token");
    assertThat(request.getValue().getHeaders().getFirst("X-ORISO-Task-Context"))
        .isEqualTo("task-proof");
  }

  @Test
  void requiresAnAccountPurposeForRecovery() throws Exception {
    assertThatThrownBy(
            () ->
                client.recover(
                    TaskPurpose.RUNTIME_POLICY, ATTEMPT, JSON.readTree("{\"reason\":\"retry\"}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("account task purpose");
  }
}
