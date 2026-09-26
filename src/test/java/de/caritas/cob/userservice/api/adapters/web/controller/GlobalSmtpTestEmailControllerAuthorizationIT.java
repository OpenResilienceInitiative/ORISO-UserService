package de.caritas.cob.userservice.api.adapters.web.controller;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.SINGLE_TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TECHNICAL_DEFAULT;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.TENANT_ADMIN;
import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.USER_ADMIN;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.service.notification.GlobalSmtpTestEmailService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("testing")
class GlobalSmtpTestEmailControllerAuthorizationIT {

  private static final String ENDPOINT = "/users/system-notification-emails/test";
  private static final String BODY =
      """
      {"host":"smtp.example.org","port":587,"secure":false,
       "from":"from@example.org","recipientEmail":"to@example.org"}
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private GlobalSmtpTestEmailService globalSmtpTestEmailService;

  @Test
  void platformAdminCanSendTheTestMail() throws Exception {
    mvc.perform(request().with(adminToken(0, TENANT_ADMIN))).andExpect(status().isOk());

    verify(globalSmtpTestEmailService).sendTestEmail(any());
  }

  @Test
  void singleTenantAdminIsForbidden() throws Exception {
    mvc.perform(request().with(jwt().authorities(new SimpleGrantedAuthority(SINGLE_TENANT_ADMIN))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(globalSmtpTestEmailService);
  }

  @Test
  void tenantScopedAdminWithPlatformRolesIsForbidden() throws Exception {
    mvc.perform(request().with(adminToken(1, TENANT_ADMIN))).andExpect(status().isForbidden());

    verifyNoInteractions(globalSmtpTestEmailService);
  }

  @Test
  void userAdminIsForbidden() throws Exception {
    mvc.perform(request().with(jwt().authorities(new SimpleGrantedAuthority(USER_ADMIN))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(globalSmtpTestEmailService);
  }

  @Test
  void technicalAccountIsForbidden() throws Exception {
    mvc.perform(request().with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(globalSmtpTestEmailService);
  }

  @Test
  void onlyPlatformAdminCanReadRedactedDeploymentSettings() throws Exception {
    mvc.perform(
            get("/users/system-notification-emails/platform-settings")
                .with(adminToken(0, TENANT_ADMIN)))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(jsonPath("$.configured").exists())
        .andExpect(jsonPath("$.username").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist());

    mvc.perform(
            get("/users/system-notification-emails/platform-settings")
                .with(adminToken(1, TENANT_ADMIN)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/users/system-notification-emails/platform-settings")
                .with(jwt().authorities(new SimpleGrantedAuthority(TECHNICAL_DEFAULT))))
        .andExpect(status().isForbidden());
  }

  private static MockHttpServletRequestBuilder request() {
    return post(ENDPOINT)
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  /** Same shape as the Admin's super-admin token: both admin roles plus a tenant id claim. */
  private static JwtRequestPostProcessor adminToken(int tenantId, String authority) {
    return jwt()
        .authorities(new SimpleGrantedAuthority(authority))
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of("agency-admin", "tenant-admin")))
                    .claim("tenantId", tenantId));
  }
}
