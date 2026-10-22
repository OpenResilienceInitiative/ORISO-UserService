package de.caritas.cob.userservice.api.config.auth;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/** Real signed synthetic HTTP grants; it does not authenticate a native human or target. */
public final class SelfAppSignedIssuer implements AutoCloseable {
  private final SyntheticTaskIssuer issuer;
  private final TaskActorConfiguration actors;
  private final TaskActorTokenBroker broker;

  public SelfAppSignedIssuer(String adminSubject) throws Exception {
    issuer = new SyntheticTaskIssuer();
    issuer.adminSubject = adminSubject;
    var environment = FiveActorConfigurationTest.environment();
    environment.setProperty("IDENTITY_ACCOUNT_ADMIN_SERVICE_SUBJECT", adminSubject);
    actors = FiveActorConfigurationTest.configured(environment);
    var identity = new IdentityConfig();
    identity.setOpenidConnectUrl(issuer.base());
    var keycloak = new KeycloakConfig();
    keycloak.setAuthServerUrl(issuer.base());
    keycloak.setRealm("synthetic");
    broker =
        new TaskActorTokenBroker(
            actors,
            new KeycloakAuthClient(new RestTemplate(), new AuthenticatedUser(), identity),
            NimbusJwtDecoder.withJwkSetUri(issuer.base() + "/certs").build(),
            keycloak);
  }

  public String base() {
    return issuer.base();
  }

  public String issuer() {
    return issuer.issuer();
  }

  public TaskActorConfiguration actors() {
    return actors;
  }

  public TaskActorTokenBroker broker() {
    return broker;
  }

  @Override
  public void close() {
    issuer.close();
  }
}
