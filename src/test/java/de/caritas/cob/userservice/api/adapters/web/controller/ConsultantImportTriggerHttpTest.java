package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.config.CsrfSecurityProperties;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.facade.EmailNotificationFacade;
import de.caritas.cob.userservice.api.service.ConsultantImportService;
import de.caritas.cob.userservice.api.service.SessionDataService;
import de.caritas.cob.userservice.api.service.session.SessionService;
import jakarta.servlet.http.Cookie;
import java.security.*;
import java.security.interfaces.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/**
 * Actual configured security chain, inactivity filter and delegate; only the importer is mocked.
 */
@SpringJUnitWebConfig(ConsultantImportTriggerHttpTest.Config.class)
@org.springframework.test.context.ActiveProfiles("scenario-c-import-http-fixture")
@TestPropertySource(properties = {"TASK_IDENTITY_MODEL=FIVE_ACTORS", "multitenancy.enabled=false"})
class ConsultantImportTriggerHttpTest {
  private static final String SUBJECT = "84000000-0000-4000-8000-000000000001";

  @Configuration
  @Profile("scenario-c-import-http-fixture")
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    RoleAuthorizationAuthorityMapper.class,
    UserSupportControllerDelegate.class,
    ImportRoute.class
  })
  static class Config {
    @Bean
    CsrfSecurityProperties csrfSecurityProperties() {
      var value = new CsrfSecurityProperties();
      var cookie = new CsrfSecurityProperties.ConfigProperty();
      cookie.setProperty("CSRF-TOKEN");
      value.setCookie(cookie);
      var header = new CsrfSecurityProperties.ConfigProperty();
      header.setProperty("X-CSRF-Token");
      value.setHeader(header);
      var whitelist = new CsrfSecurityProperties.Whitelist();
      var whitelistHeader = new CsrfSecurityProperties.ConfigProperty();
      whitelistHeader.setProperty("X-CSRF-Whitelist");
      whitelist.setHeader(whitelistHeader);
      value.setWhitelist(whitelist);
      return value;
    }

    @Bean
    TaskActorTokenBroker broker(
        TaskActorConfiguration actors, KeycloakAuthClient auth, JwtDecoder decoder) {
      var config = new KeycloakConfig();
      config.setAuthServerUrl("https://public-auth.example.test");
      config.setRealm("synthetic");
      return new TaskActorTokenBroker(actors, auth, decoder, config);
    }
  }

  @RestController
  @Profile("scenario-c-import-http-fixture")
  static class ImportRoute {
    private final UserSupportControllerDelegate delegate;

    ImportRoute(UserSupportControllerDelegate delegate) {
      this.delegate = delegate;
    }

    @PostMapping("/users/consultants/import")
    org.springframework.http.ResponseEntity<Void> run() {
      return delegate.importConsultants();
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired TaskActorTokenBroker broker;
  @MockitoBean JwtDecoder jwtDecoder;
  @MockitoBean TaskActorConfiguration actors;
  @MockitoBean KeycloakAuthClient authentication;
  @MockitoBean ConsultantImportService importer;
  @MockitoBean SessionService sessions;
  @MockitoBean EmailNotificationFacade email;
  @MockitoBean SessionDataService sessionData;

  @MockitoBean
  de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService inactivity;

  @MockitoBean
  de.caritas.cob.userservice.api.admin.service.admin.ManagedAccountStatusService accounts;

  private KeyPair keys;
  private MockMvc mvc;

  @BeforeEach
  void realSignedBinding() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    keys = generator.generateKeyPair();
    var nativeDecoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
    when(jwtDecoder.decode(anyString()))
        .thenAnswer(call -> nativeDecoder.decode(call.getArgument(0)));
    when(actors.require(TaskPurpose.CONSULTANT_IMPORT))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP, "backend-setup", "synthetic-setup-secret", SUBJECT));
    when(inactivity.snapshot(anyString())).thenReturn(Optional.empty());
    when(inactivity.admit(anyString(), any())).thenReturn(false);
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void validSetupReachesOnlyTheFixedImportDelegateAndItsPrivateLiveCapability() throws Exception {
    String raw = token(Map.of());
    try (var checked = assertDoesNotThrow(() -> broker.verifyConsultantImportTrigger(raw))) {
      assertDoesNotThrow(checked::requireLive);
    }
    mvc.perform(
            post("/users/consultants/import")
                .header("Authorization", "Bearer " + raw)
                .cookie(new Cookie("CSRF-TOKEN", "fixture-csrf"))
                .header("X-CSRF-Token", "fixture-csrf"))
        .andExpect(status().isOk());
    var capability =
        org.mockito.ArgumentCaptor.forClass(
            TaskActorTokenBroker.VerifiedConsultantImportTrigger.class);
    verify(importer).startImport(capability.capture());
    verify(importer, never()).startImport();
    assertThrows(IllegalStateException.class, capability.getValue()::requireLive);
    verifyNoInteractions(authentication);
  }

  @Test
  void wrongBindingOrOtherPathCannotReachAnyImportOrFileEffect() throws Exception {
    var variants = new ArrayList<Map<String, Object>>();
    variants.add(Map.of("sub", "84000000-0000-4000-8000-000000000002"));
    variants.add(Map.of("azp", "backend-account-admin"));
    variants.add(Map.of("realm_access", Map.of("roles", List.of("technical"))));
    variants.add(Map.of("realm_access", Map.of("roles", List.of("user-admin"))));
    variants.add(Map.of("aud", List.of("userservice")));
    variants.add(Map.of("exp", Date.from(Instant.now().minusSeconds(1))));
    variants.add(
        Map.of(
            "realm_access",
            Map.of(
                "roles",
                List.of(
                    "config-wizard",
                    "invitation-reservations",
                    "consultant-import",
                    "account-maintenance"))));
    for (var changed : variants) {
      var response =
          mvc.perform(
                  post("/users/consultants/import")
                      .header("Authorization", "Bearer " + token(changed))
                      .cookie(new Cookie("CSRF-TOKEN", "fixture-csrf"))
                      .header("X-CSRF-Token", "fixture-csrf"))
              .andReturn()
              .getResponse();
      assertTrue(Set.of(401, 403).contains(response.getStatus()), changed.keySet().toString());
    }
    mvc.perform(
            post("/users/messages/key")
                .header("Authorization", "Bearer " + token(Map.of()))
                .cookie(new Cookie("CSRF-TOKEN", "fixture-csrf"))
                .header("X-CSRF-Token", "fixture-csrf"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(importer, authentication, sessions, email, sessionData);
  }

  @Test
  void legacyTechnicalImportStillUsesItsOriginalNoArgumentEntry() throws Exception {
    var environment = (ConfigurableEnvironment) context.getEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "legacy-import-fixture", Map.of("TASK_IDENTITY_MODEL", "LEGACY")));
    try {
      mvc.perform(
              post("/users/consultants/import")
                  .header(
                      "Authorization",
                      "Bearer "
                          + token(Map.of("realm_access", Map.of("roles", List.of("technical")))))
                  .cookie(new Cookie("CSRF-TOKEN", "fixture-csrf"))
                  .header("X-CSRF-Token", "fixture-csrf"))
          .andExpect(status().isOk());
      verify(importer).startImport();
      verify(importer, never())
          .startImport(any(TaskActorTokenBroker.VerifiedConsultantImportTrigger.class));
      verifyNoInteractions(authentication);
    } finally {
      environment.getPropertySources().remove("legacy-import-fixture");
    }
  }

  private String token(Map<String, Object> changes) throws Exception {
    var values = new HashMap<String, Object>();
    values.put("iss", "https://public-auth.example.test/realms/synthetic");
    values.put("sub", SUBJECT);
    values.put("azp", "backend-setup");
    values.put(
        "aud",
        List.of(
            "tenantservice",
            "agencyservice",
            "consultingtypeservice",
            "userservice",
            "oriso-task-commands"));
    values.put(
        "realm_access",
        Map.of(
            "roles",
            List.of("config-wizard", "invitation-reservations", "consultant-import", "smtp-sync")));
    values.put("iat", Date.from(Instant.now().minusSeconds(1)));
    values.put("exp", Date.from(Instant.now().plusSeconds(300)));
    values.putAll(changes);
    var claims = new JWTClaimsSet.Builder();
    values.forEach(claims::claim);
    var signed = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
    signed.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
    return signed.serialize();
  }
}
