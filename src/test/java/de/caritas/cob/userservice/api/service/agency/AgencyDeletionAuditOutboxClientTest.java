package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class AgencyDeletionAuditOutboxClientTest {

  private static final String AGENCY_SERVICE_URL = "https://agency.example/service";
  private static final String OUTBOX = AGENCY_SERVICE_URL + "/internal/deletion-audit-outbox";

  private MockRestServiceServer server;
  private IdentityClientConfig identity;
  private AgencyDeletionAuditOutboxClient client;

  @BeforeEach
  void setUp() {
    var restTemplate = new RestTemplate();
    server = MockRestServiceServer.bindTo(restTemplate).build();
    identity = mock(IdentityClientConfig.class);
    var headers = new SecurityHeaderSupplier(new AuthenticatedUser());
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "csrfHeader");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "csrfCookie");
    client = new AgencyDeletionAuditOutboxClient(restTemplate, headers, identity);
    ReflectionTestUtils.setField(client, "agencyServiceBaseUrl", AGENCY_SERVICE_URL);
  }

  @Test
  void readsPendingRowsWithTheRuntimeActorTokenForTheDeletionAuditPurpose() {
    when(identity.isFiveActorMode()).thenReturn(true);
    when(identity.taskAccessToken(TaskPurpose.DELETION_AUDIT_PULL)).thenReturn("runtime-token");
    server
        .expect(requestTo(OUTBOX + "?limit=25"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer runtime-token"))
        .andRespond(
            withSuccess(
                "[{\"id\":7,\"agencyId\":268,\"tenantId\":4,\"outcome\":\"EXECUTED\","
                    + "\"createdAt\":\"2026-10-30T01:00:00\"}]",
                MediaType.APPLICATION_JSON));

    var rows = client.fetchPending(25);

    assertThat(rows)
        .containsExactly(
            new AgencyDeletionAuditOutboxClient.PendingEntry(
                7L, 268L, 4L, "EXECUTED", "2026-10-30T01:00:00"));
    verify(identity).taskAccessToken(TaskPurpose.DELETION_AUDIT_PULL);
    server.verify();
  }

  @Test
  void acknowledgesTheRecordedIds() {
    when(identity.isFiveActorMode()).thenReturn(true);
    when(identity.taskAccessToken(TaskPurpose.DELETION_AUDIT_PULL)).thenReturn("runtime-token");
    server
        .expect(requestTo(OUTBOX + "/acknowledgements"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer runtime-token"))
        .andExpect(content().json("{\"ids\":[7,8]}"))
        .andRespond(withSuccess("{\"acknowledged\":2}", MediaType.APPLICATION_JSON));

    client.acknowledge(java.util.List.of(7L, 8L));

    server.verify();
  }

  @Test
  void neverFallsBackToTheLegacyTechnicalUser() {
    when(identity.isFiveActorMode()).thenReturn(false);

    assertThatThrownBy(() -> client.fetchPending(25)).isInstanceOf(IllegalStateException.class);

    verify(identity).isFiveActorMode();
    org.mockito.Mockito.verifyNoMoreInteractions(identity);
    server.verify();
  }

  @Test
  void aRefusedReadSurfacesWithoutTheResponseBody() {
    when(identity.isFiveActorMode()).thenReturn(true);
    when(identity.taskAccessToken(TaskPurpose.DELETION_AUDIT_PULL)).thenReturn("runtime-token");
    server
        .expect(requestTo(OUTBOX + "?limit=25"))
        .andRespond(withStatus(HttpStatus.FORBIDDEN).body("secret diagnostic"));

    assertThatThrownBy(() -> client.fetchPending(25))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("secret diagnostic")
        .hasNoCause();
  }

  @Test
  void anEmptyAcknowledgementMakesNoCall() {
    client.acknowledge(java.util.List.of());

    verifyNoInteractions(identity);
    server.verify();
  }
}
