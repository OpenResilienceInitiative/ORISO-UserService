package de.caritas.cob.userservice.api.service;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.caritas.cob.userservice.api.adapters.web.controller.AccountInactivityController;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountInactivityAdminAuthorizationTest {
  @Test
  void platformAdminRolePlacementAndNormalizationMatchAcceptedTokenForms() throws Exception {
    var ds =
        new DriverManagerDataSource(
            "jdbc:h2:mem:adminrole" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    var jdbc = new JdbcTemplate(ds);
    jdbc.execute(
        "CREATE TABLE account_inactivity(identity_id VARCHAR(36) PRIMARY KEY,tenant_id"
            + " BIGINT,assigned_months INT,revision BIGINT,last_activity TIMESTAMP,due_at"
            + " TIMESTAMP,status VARCHAR(20),last_error VARCHAR(1000),attempts INT)");
    var clock = Clock.fixed(Instant.parse("2026-09-16T10:00:00Z"), ZoneOffset.UTC);
    var lifecycle =
        new AccountInactivityService(
            jdbc,
            new DataSourceTransactionManager(ds),
            clock,
            new AccountInactivityEnrollmentTest.ExternalEffects());
    var controller =
        new AccountInactivityController(
            lifecycle,
            clock,
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(
                    de.caritas.cob.userservice.api.workflow.accountinactivity
                        .AccountInactivityBootstrap.class));
    var mvc = MockMvcBuilders.standaloneSetup(controller).build();
    var variants =
        List.of(
            Map.<String, Object>of(
                "realm_access", Map.of("roles", List.of("agency-admin", "tenant-admin"))),
            Map.<String, Object>of(
                "resource_access",
                Map.of("admin", Map.of("roles", List.of("agency-admin", "tenant-admin")))),
            Map.<String, Object>of(
                "realm_access",
                Map.of("roles", List.of("ROLE_AGENCY_ADMIN")),
                "resource_access",
                Map.of("admin", Map.of("roles", List.of("ROLE_TENANT_ADMIN")))));
    for (var claims : variants)
      for (Object tenant : List.of(0, 0L, 0.0, "0")) {
        var jwt =
            Jwt.withTokenValue("test")
                .header("alg", "RS256")
                .subject("platform")
                .claim("tenantId", tenant)
                .claims(c -> c.putAll(claims))
                .build();
        mvc.perform(
                get("/useradmin/account-inactivity/candidates")
                    .principal(new JwtAuthenticationToken(jwt)))
            .andExpect(status().isOk());
      }
    for (Object tenant : List.of(1, 0.1, "no-tenant")) {
      var jwt =
          Jwt.withTokenValue("test")
              .header("alg", "RS256")
              .subject("tenant-admin")
              .claim("tenantId", tenant)
              .claims(c -> c.putAll(variants.getFirst()))
              .build();
      mvc.perform(
              get("/useradmin/account-inactivity/candidates")
                  .principal(new JwtAuthenticationToken(jwt)))
          .andExpect(status().isForbidden());
    }
    for (var roles :
        List.of(List.of("tenant-admin"), List.of("agency-admin"), List.of("technical"))) {
      var jwt =
          Jwt.withTokenValue("test")
              .header("alg", "RS256")
              .subject("not-platform")
              .claim("tenantId", 0)
              .claim("realm_access", Map.of("roles", roles))
              .build();
      mvc.perform(
              get("/useradmin/account-inactivity/candidates")
                  .principal(new JwtAuthenticationToken(jwt)))
          .andExpect(status().isForbidden());
    }
  }
}
