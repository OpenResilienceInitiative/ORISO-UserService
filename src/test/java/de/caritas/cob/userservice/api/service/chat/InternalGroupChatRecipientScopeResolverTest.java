package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.TenantCaseHandoverPolicyReadClient;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * The seam is the wire to the TenantService: its raw {@code permission-policies} JSON goes in
 * through the real client, the assertion is on the scope UserService enforces. A TenantService that
 * does not know the setting yet (ORISO-TenantService#321 not merged) must read as {@code AGENCY}.
 */
class InternalGroupChatRecipientScopeResolverTest {

  private static final String URL = "https://tenant.example.org/tenantadmin/7/permission-policies";

  private final RestTemplate rest = new RestTemplate();
  private final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  private InternalGroupChatRecipientScopeResolver resolver;
  private MutableClock clock;
  private final TenantService tenantService = mock(TenantService.class);

  @BeforeEach
  void setUp() {
    var identity = mock(IdentityAuthentication.class);
    var identityConfig = mock(IdentityClientConfig.class);
    var technical = new TechnicalUserConfig();
    technical.setClientId("synthetic-service");
    technical.setClientSecret("synthetic-password");
    when(identityConfig.getTechnicalUser()).thenReturn(technical);
    when(identity.loginService("synthetic-service", "synthetic-password"))
        .thenReturn(new IdentityLogin("synthetic-token", 60, 120, "synthetic-refresh"));
    var headers = new SecurityHeaderSupplier(mock(AuthenticatedUser.class));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    var factory = new TenantAdminServiceApiControllerFactory();
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.example.org");
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
    clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
    resolver =
        new InternalGroupChatRecipientScopeResolver(
            new TenantCaseHandoverPolicyReadClient(factory, identity, identityConfig, headers),
            tenantService,
            clock);
    // Production default; the single-tenant tests switch it off.
    ReflectionTestUtils.setField(resolver, "multitenancy", true);
  }

  @Test
  void theResolvedScopeOfTheTraegerIsReadFromTheTenantService() {
    expectPolicies(
        """
        {"tenantId":7,"policies":{},"internalGroupChat":{"recipientScope":
          {"value":"TENANT","mode":"ENFORCED","inherited":true}}}
        """);

    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.TENANT);
    server.verify();
  }

  @Test
  void aTenantServiceWithoutTheSettingMeansCounsellingCentreOnly() {
    expectPolicies("{\"tenantId\":7,\"policies\":{}}");

    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
  }

  @Test
  void aTenantServiceThatCannotBeReachedMeansCounsellingCentreOnly() {
    server
        .expect(ExpectedCount.once(), requestTo(URL))
        .andRespond(withServerError().body("secret-detail"));

    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
  }

  @Test
  void theAnswerIsKeptForAMinute() {
    expectPolicies(
        """
        {"tenantId":7,"policies":{},"internalGroupChat":{"recipientScope":
          {"value":"PLATFORM","mode":"SUGGESTED","inherited":false}}}
        """);
    expectPolicies("{\"tenantId\":7,\"policies\":{}}");

    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.PLATFORM);
    // Served from memory: the mock server saw exactly one call so far.
    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.PLATFORM);
    clock.advance(Duration.ofMinutes(10));
    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    server.verify();
  }

  @Test
  void aFailureIsRememberedForSecondsOnlyAndNeverWidens() {
    server.expect(ExpectedCount.once(), requestTo(URL)).andRespond(withServerError());
    expectPolicies(
        """
        {"tenantId":7,"policies":{},"internalGroupChat":{"recipientScope":
          {"value":"TENANT","mode":"SUGGESTED","inherited":false}}}
        """);

    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    // An outage is not hammered: no second call, no Keycloak login, still the narrow answer.
    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    clock.advance(Duration.ofSeconds(11));
    assertThat(resolver.resolve(7L)).isEqualTo(InternalGroupChatRecipientScope.TENANT);
    server.verify();
  }

  @Test
  void simultaneousCallsForOneTraegerReadTheSettingOnce() throws Exception {
    expectPolicies(
        """
        {"tenantId":7,"policies":{},"internalGroupChat":{"recipientScope":
          {"value":"TENANT","mode":"ENFORCED","inherited":true}}}
        """);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
    var start = new java.util.concurrent.CountDownLatch(1);
    try {
      var answers =
          new java.util.ArrayList<java.util.concurrent.Future<InternalGroupChatRecipientScope>>();
      for (int i = 0; i < 8; i++) {
        answers.add(
            pool.submit(
                () -> {
                  start.await();
                  return resolver.resolve(7L);
                }));
      }
      start.countDown();
      for (var answer : answers) {
        assertThat(answer.get()).isEqualTo(InternalGroupChatRecipientScope.TENANT);
      }
    } finally {
      pool.shutdownNow();
    }
    server.verify();
  }

  @Test
  void aConsultantWithoutATraegerStaysOnCounsellingCentreWithMultitenancyAndAsksNobody() {
    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    verify(tenantService, never()).getSingleTenancyTenantDataFresh();
    server.verify();
  }

  @Test
  void withoutMultitenancyTheConfiguredScopeOfTheSingleTenantApplies() {
    ReflectionTestUtils.setField(resolver, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh())
        .thenReturn(new RestrictedTenantDTO().id(7L).name("Tenant"));
    expectPolicies(
        """
        {"tenantId":7,"policies":{},"internalGroupChat":{"recipientScope":
          {"value":"TENANT","mode":"ENFORCED","inherited":true}}}
        """);

    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.TENANT);
    // The single tenant's id is remembered with the answer: no second lookup, no second read.
    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.TENANT);
    verify(tenantService, times(1)).getSingleTenancyTenantDataFresh();
    server.verify();
  }

  @Test
  void withoutMultitenancyAnUnreadableSingleTenantMeansCounsellingCentreOnly() {
    ReflectionTestUtils.setField(resolver, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh()).thenThrow(new IllegalStateException("x"));

    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    // The outage is not hammered, and nothing wider than the narrow default was granted.
    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    verify(tenantService, times(1)).getSingleTenancyTenantDataFresh();
    server.verify();
  }

  @Test
  void withoutMultitenancyASingleTenantWithoutAnIdMeansCounsellingCentreOnly() {
    ReflectionTestUtils.setField(resolver, "multitenancy", false);
    when(tenantService.getSingleTenancyTenantDataFresh()).thenReturn(new RestrictedTenantDTO());

    assertThat(resolver.resolve(null)).isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    server.verify();
  }

  private void expectPolicies(String body) {
    server
        .expect(ExpectedCount.once(), requestTo(URL))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
  }

  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
