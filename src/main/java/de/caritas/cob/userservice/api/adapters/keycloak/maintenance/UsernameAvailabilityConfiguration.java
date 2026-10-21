package de.caritas.cob.userservice.api.adapters.keycloak.maintenance;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Compiling runtime-wiring diagnostic signature; no gateway registration before public wire RED.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ORISO_ACCOUNT_LIFECYCLE_ENABLED", havingValue = "true")
public class UsernameAvailabilityConfiguration {}
