package de.caritas.cob.userservice.api.service.accountinvite.onboarding;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance.AcceptanceClaim;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.service.httpheader.SecurityHeaderSupplier;
import de.caritas.cob.userservice.tenantadminservice.generated.web.model.MultilingualTenantDTO;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.client.RestTemplate;

/** Fixed protected held-bootstrap transport; no ordinary creation route is changed. */
@org.springframework.stereotype.Component
final class ReservedTenantBootstrap {
  ReservedTenantBootstrap(RestTemplate http, SecurityHeaderSupplier headers,
      TaskActorTokenBroker broker, JwtDecoder decoder, Environment environment,
      @org.springframework.beans.factory.annotation.Value("${tenant.service.api.url}") String base) {}
}
