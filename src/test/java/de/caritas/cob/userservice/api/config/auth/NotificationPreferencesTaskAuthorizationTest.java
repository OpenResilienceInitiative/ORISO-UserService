package de.caritas.cob.userservice.api.config.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.time.Instant;
import java.util.*;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.intercept.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class NotificationPreferencesTaskAuthorizationTest {
  private static final byte[] KEY =
      "signed-test-jwt-key-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    var decoder =
        NimbusJwtDecoder.withSecretKey(new SecretKeySpec(KEY, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    var provider = new JwtAuthenticationProvider(decoder);
    var bearer = new BearerTokenAuthenticationFilter(provider::authenticate);
    var guard =
        new NotificationPreferencesTaskAuthorization(
            "backend-notification-dispatch", "dispatch-subject", "userservice");
    var authorization =
        new AuthorizationFilter(
            (authentication, request) ->
                guard.authorize(authentication, new RequestAuthorizationContext(request)));
    mvc =
        MockMvcBuilders.standaloneSetup(new PreferencesEndpoint())
            .addFilters(
                bearer,
                new ExceptionTranslationFilter(new BearerTokenAuthenticationEntryPoint()),
                authorization)
            .build();
  }

  @AfterEach
  void cleanup() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void OwnSignedTaskBindingCanReadPreferences() throws Exception {
    mvc.perform(
            get("/users/notifications")
                .header(
                    "Authorization",
                    "Bearer "
                        + token(
                            "dispatch-subject",
                            "backend-notification-dispatch",
                            "userservice",
                            List.of("notification-dispatch", "notifications-technical"))))
        .andExpect(status().isNoContent());
  }

  @Test
  void HumanRoleAssignmentForeignServiceAndWrongAudienceAreForbidden() throws Exception {
    for (String denied :
        List.of(
            token("human-subject", "app", "userservice", List.of("notifications-technical")),
            token(
                "other-service",
                "backend-notification-dispatch",
                "userservice",
                List.of("notification-dispatch", "notifications-technical")),
            token(
                "dispatch-subject",
                "other-client",
                "userservice",
                List.of("notification-dispatch", "notifications-technical")),
            token(
                "dispatch-subject",
                "backend-notification-dispatch",
                "agencyservice",
                List.of("notification-dispatch", "notifications-technical")),
            token(
                "dispatch-subject",
                "backend-notification-dispatch",
                "userservice",
                List.of("notification-dispatch", "notifications-technical", "technical")))) {
      SecurityContextHolder.clearContext();
      mvc.perform(get("/users/notifications").header("Authorization", "Bearer " + denied))
          .andExpect(status().isForbidden());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullAndEmptySource
  void absentAudienceIsForbiddenWithoutFailingTheRequest(String audience) throws Exception {
    mvc.perform(
            get("/users/notifications")
                .header(
                    "Authorization",
                    "Bearer "
                        + token(
                            "dispatch-subject",
                            "backend-notification-dispatch",
                            audience,
                            List.of("notification-dispatch", "notifications-technical"))))
        .andExpect(status().isForbidden());
  }

  private static String token(String subject, String client, String audience, List<String> roles)
      throws Exception {
    var jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .subject(subject)
                .audience(audience)
                .claim("azp", client)
                .claim("realm_access", Map.of("roles", roles))
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60)))
                .build());
    jwt.sign(new MACSigner(KEY));
    return jwt.serialize();
  }

  @RestController
  @org.springframework.context.annotation.Profile("isolated-task-authorization-http")
  static class PreferencesEndpoint {
    @GetMapping("/users/notifications")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void preferences() {}
  }
}
