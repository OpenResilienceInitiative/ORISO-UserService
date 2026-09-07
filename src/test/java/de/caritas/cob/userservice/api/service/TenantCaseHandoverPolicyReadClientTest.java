package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import de.caritas.cob.userservice.api.config.apiclient.TenantAdminServiceApiControllerFactory;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class TenantCaseHandoverPolicyReadClientTest {
  final RestTemplate rest = new RestTemplate();
  final MockRestServiceServer server = MockRestServiceServer.createServer(rest);
  final IdentityAuthentication identity = mock(IdentityAuthentication.class);
  final IdentityClientConfig config = mock(IdentityClientConfig.class);
  final AuthenticatedUser requestUser = mock(AuthenticatedUser.class);
  final SecurityHeaderSupplier headers = new SecurityHeaderSupplier(requestUser);
  final TenantAdminServiceApiControllerFactory factory =
      new TenantAdminServiceApiControllerFactory();
  final TenantCaseHandoverPolicyReadClient client =
      new TenantCaseHandoverPolicyReadClient(factory, identity, config, headers);

  @BeforeEach
  void setup() {
    var technical = new TechnicalUserConfig();
    technical.setUsername("synthetic-service");
    technical.setPassword("synthetic-password");
    when(config.getTechnicalUser()).thenReturn(technical);
    when(identity.login("synthetic-service", "synthetic-password"))
        .thenReturn(new IdentityLogin("synthetic-token", 60, 120, "synthetic-refresh"));
    ReflectionTestUtils.setField(headers, "csrfHeaderProperty", "X-CSRF-TOKEN");
    ReflectionTestUtils.setField(headers, "csrfCookieProperty", "CSRF-TOKEN");
    ReflectionTestUtils.setField(factory, "tenantServiceApiUrl", "https://tenant.example.org");
    ReflectionTestUtils.setField(factory, "restTemplate", rest);
  }

  @Test
  void generatedClientUsesTechnicalHeadersAndExactTenantWithoutRequestContext() {
    server
        .expect(requestTo("https://tenant.example.org/tenantadmin/40/permission-policies"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andExpect(
            header(
                "X-CSRF-TOKEN", org.hamcrest.Matchers.not(org.hamcrest.Matchers.isEmptyString())))
        .andExpect(header("Cookie", org.hamcrest.Matchers.containsString("CSRF-TOKEN=")))
        .andRespond(
            withSuccess(
                """
            {"tenantId":40,"policies":{},"caseHandoverPolicies":{"reasons":{
              "COUNSELLOR_ASKED_FOR_ADVICE":{"maxAccessDurationMinutes":{"value":180}},
              "COUNSELLOR_ON_HOLIDAY":{"clientConsentRequired":{"value":false}}
            }}}
            """,
                MediaType.APPLICATION_JSON));
    var result = client.getTenantPermissionPolicies(40L);
    assertThat(result.getTenantId()).isEqualTo(40L);
    var reasons = result.getCaseHandoverPolicies().getReasons();
    assertThat(reasons.get("COUNSELLOR_ASKED_FOR_ADVICE").getMaxAccessDurationMinutes().getValue())
        .isEqualTo(180);
    assertThat(reasons.get("COUNSELLOR_ON_HOLIDAY").getClientConsentRequired().getValue())
        .isFalse();
    verify(identity).logout("synthetic-refresh", "synthetic-token");
    verifyNoInteractions(requestUser);
    server.verify();
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403, 503})
  void failedProviderReadRetainsOnlyStatusAndClassAndLogsOut(int status) {
    server
        .expect(anything())
        .andRespond(
            withStatus(HttpStatus.valueOf(status))
                .body("synthetic-sensitive-downstream-body")
                .contentType(MediaType.TEXT_PLAIN));
    assertThatThrownBy(() -> client.getTenantPermissionPolicies(40L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("HTTP " + status)
        .hasMessageNotContaining("synthetic-sensitive")
        .hasNoCause();
    verify(identity).logout("synthetic-refresh", "synthetic-token");
    server.verify();
  }

  @Test
  void missingTokenCannotIssueUnauthenticatedRead() {
    when(identity.login(anyString(), anyString()))
        .thenReturn(new IdentityLogin("", 0, 0, "synthetic-refresh"));
    assertThatThrownBy(() -> client.getTenantPermissionPolicies(40L)).hasNoCause();
    verify(identity).logout("synthetic-refresh", "");
    server.verify();
  }

  @Test
  void loginFailureCannotExposeCredentialsOrAttemptLogout() {
    when(identity.login(anyString(), anyString()))
        .thenThrow(new IllegalStateException("synthetic-password"));
    assertThatThrownBy(() -> client.getTenantPermissionPolicies(40L))
        .hasMessageNotContaining("synthetic-password")
        .hasNoCause();
    verify(identity, never()).logout(anyString(), anyString());
    server.verify();
  }

  @Test
  void logoutFailureDoesNotDiscardSuccessfulRead() {
    server
        .expect(anything())
        .andRespond(withSuccess("{\"tenantId\":40,\"policies\":{}}", MediaType.APPLICATION_JSON));
    when(identity.logout("synthetic-refresh", "synthetic-token"))
        .thenThrow(new IllegalStateException("synthetic-sensitive-reply"));
    assertThat(client.getTenantPermissionPolicies(40L).getTenantId()).isEqualTo(40L);
    server.verify();
  }

  @Test
  void standardHeaderSupplierIsSelectedAlongsideTrackingSupplier() {
    var tracking = mock(SecurityHeaderSupplier.class);
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withPropertyValues(
            "tenant.service.api.url=https://tenant.example.org",
            "csrf.header.property=X-CSRF-TOKEN",
            "csrf.cookie.property=CSRF-TOKEN")
        .withBean(RestTemplate.class, () -> rest)
        .withBean(TenantAdminServiceApiControllerFactory.class, () -> factory)
        .withBean(IdentityAuthentication.class, () -> identity)
        .withBean(IdentityClientConfig.class, () -> config)
        .withBean("securityHeaderSupplier", SecurityHeaderSupplier.class, () -> headers)
        .withBean("trackingSecurityHeaderSupplier", SecurityHeaderSupplier.class, () -> tracking)
        .withBean(TenantCaseHandoverPolicyReadClient.class)
        .run(context -> assertThat(context).hasNotFailed());
  }
}
