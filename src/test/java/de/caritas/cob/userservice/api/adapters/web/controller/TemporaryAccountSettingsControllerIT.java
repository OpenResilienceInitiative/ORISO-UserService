package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.TemporaryAccountSettingsRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType;
import de.caritas.cob.userservice.api.service.temporaryaccount.MessageRetentionMode;
import de.caritas.cob.userservice.api.service.temporaryaccount.TemporaryAccountSettingsService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Admin → Global settings for temporary accounts (ORISO-Admin#1241, #1476) through the real
 * security chain, controller, service and database: platform admin only, defaults, revision
 * conflict, validation, and the copy a new account takes.
 */
@TestPropertySource(
    properties = {
      "spring.profiles.active=testing",
      "spring.datasource.url=jdbc:h2:mem:temporary-account-settings;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
      "spring.datasource.username=sa",
      "spring.datasource.password=sa",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=never",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "keycloak.auth-server-url=https://auth.testing",
      "keycloak.realm=testing",
      "keycloak.config.admin-username=admin",
      "keycloak.config.admin-password=secret",
      "identity.openid-connect-url=https://auth.testing/realms/testing/protocol/openid-connect",
      "consulting.type.service.api.url=https://consulting-type.testing/service",
      "tenant.service.api.url=https://tenant.testing/service",
      "matrix.apiUrl=https://matrix.testing",
      "matrix.registrationSharedSecret=secret"
    })
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("testing")
class TemporaryAccountSettingsControllerIT {

  private static final String URL = "/service/users/admin/temporary-account-settings";
  private static final String VALID_BODY =
      """
      {"callDeleteAfterMinutes":10,"callMaxLifetimeHours":12,"chatInactivityDays":7,
       "hidePasswordField":true,"messageRetention":"ALWAYS_PERSISTENT","revision":%d}
      """;

  @MockitoBean
  private de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
      lifecycle;

  @MockitoBean private AuthenticatedUser authenticatedUser;

  @Autowired private MockMvc mvc;
  @Autowired private TemporaryAccountSettingsRepository repository;
  @Autowired private TemporaryAccountSettingsService service;

  @BeforeEach
  void activeCaller() {
    when(lifecycle.admit(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn("platform-admin");
  }

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  private static RequestPostProcessor roles(long tenantId, String... roles) {
    return jwt()
        .jwt(
            token ->
                token
                    .claim("realm_access", Map.of("roles", List.of(roles)))
                    .claim("tenantId", tenantId));
  }

  private static RequestPostProcessor platformAdmin() {
    return roles(0, "agency-admin", "tenant-admin");
  }

  private static MockHttpServletRequestBuilder putJson(String body) {
    return put(URL)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body)
        .cookie(new Cookie("CSRF-TOKEN", "test"))
        .header("X-CSRF-Token", "test");
  }

  @Test
  void onlyThePlatformAdminReadsOrChangesTheSettings() throws Exception {
    mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    mvc.perform(get(URL).with(roles(7, "agency-admin", "tenant-admin")))
        .andExpect(status().isForbidden());
    mvc.perform(putJson(VALID_BODY.formatted(0)).with(roles(7, "agency-admin", "tenant-admin")))
        .andExpect(status().isForbidden());
    mvc.perform(get(URL).with(roles(0, "user"))).andExpect(status().isForbidden());
  }

  @Test
  void platformAdminSeesTheDefaults_SavesOnce_AndGetsAConflictOnAStaleRevision() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    mvc.perform(get(URL).with(platformAdmin()))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.callDeleteAfterMinutes").value(15))
        .andExpect(jsonPath("$.callMaxLifetimeHours").value(24))
        .andExpect(jsonPath("$.chatInactivityDays").value(30))
        .andExpect(jsonPath("$.hidePasswordField").value(false))
        .andExpect(jsonPath("$.messageRetention").value("GUEST_DECIDES"))
        .andExpect(jsonPath("$.revision").value(0));

    mvc.perform(putJson(VALID_BODY.formatted(0)).with(platformAdmin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.callDeleteAfterMinutes").value(10))
        .andExpect(jsonPath("$.messageRetention").value("ALWAYS_PERSISTENT"))
        .andExpect(jsonPath("$.revision").value(1));

    mvc.perform(get(URL).with(platformAdmin()))
        .andExpect(jsonPath("$.chatInactivityDays").value(7))
        .andExpect(jsonPath("$.hidePasswordField").value(true));

    mvc.perform(putJson(VALID_BODY.formatted(0)).with(platformAdmin()))
        .andExpect(status().isConflict());
  }

  @Test
  void invalidValuesAreRejected() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);

    mvc.perform(
            putJson(
                    VALID_BODY
                        .formatted(0)
                        .replace("\"callMaxLifetimeHours\":12", "\"callMaxLifetimeHours\":25"))
                .with(platformAdmin()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            putJson(VALID_BODY.formatted(0).replace("\"chatInactivityDays\":7,", ""))
                .with(platformAdmin()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            putJson(VALID_BODY.formatted(0).replace("ALWAYS_PERSISTENT", "SOMETIMES"))
                .with(platformAdmin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aNewAccountCopiesTheValues_AndALaterChangeLeavesTheCopyAlone() throws Exception {
    when(authenticatedUser.isPlatformAdmin()).thenReturn(true);
    mvc.perform(putJson(VALID_BODY.formatted(0)).with(platformAdmin())).andExpect(status().isOk());

    var copy = service.snapshotForNewAccount(InviteLinkChatType.CHAT, 9L);

    mvc.perform(
            putJson(
                    VALID_BODY
                        .formatted(1)
                        .replace("\"chatInactivityDays\":7", "\"chatInactivityDays\":90"))
                .with(platformAdmin()))
        .andExpect(status().isOk());
    org.assertj.core.api.Assertions.assertThat(copy.getChatInactivityDays()).isEqualTo(7);
    org.assertj.core.api.Assertions.assertThat(copy.getMessageRetention())
        .isEqualTo(MessageRetentionMode.ALWAYS_PERSISTENT);
    org.assertj.core.api.Assertions.assertThat(service.current().chatInactivityDays())
        .isEqualTo(90);
  }
}
