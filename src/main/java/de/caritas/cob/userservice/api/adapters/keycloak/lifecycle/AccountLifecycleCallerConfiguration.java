package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.maintenance.SelfLocaleMaintenance;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.config.MatrixConfig;
import de.caritas.cob.userservice.api.adapters.persistence.password.JdbcExistingSetupPasswordClaims;
import de.caritas.cob.userservice.api.config.auth.*;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;

/**
 * Complete caller bean graph remains opt-in; the global coordinated C startup fence still applies.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ORISO_ACCOUNT_LIFECYCLE_ENABLED", havingValue = "true")
public class AccountLifecycleCallerConfiguration {
  private final Environment environment;
  private final Map<String, Set<String>> groups;
  private final Set<String> humanClients;

  public AccountLifecycleCallerConfiguration(Environment environment) {
    this.environment = environment;
    if (!"FIVE_ACTORS".equals(environment.getProperty("TASK_IDENTITY_MODEL"))) throw invalid();
    try {
      var mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
      var node = mapper.readTree(required("ORISO_ACCOUNT_LIFECYCLE_GROUPS"));
      if (!node.isObject() || node.isEmpty()) throw invalid();
      var parsed = new LinkedHashMap<String, Set<String>>();
      node.fields()
          .forEachRemaining(
              entry -> {
                if (!entry.getKey().matches("/human-roles/[a-z0-9-]{1,80}")
                    || !entry.getValue().isArray()
                    || entry.getValue().isEmpty()) throw invalid();
                var roles = new HashSet<String>();
                entry
                    .getValue()
                    .forEach(
                        role -> {
                          if (!role.isTextual() || !roles.add(role.asText())) throw invalid();
                        });
                if (OrdinaryAccountCreation.kind(roles) == null) throw invalid();
                parsed.put(entry.getKey(), Set.copyOf(roles));
              });
      if (new HashSet<>(parsed.values()).size() != parsed.size()
          || !parsed.containsValue(Set.of("user"))) throw invalid();
      groups = Map.copyOf(parsed);
      var clients = mapper.readTree(required("ORISO_IDENTITY_HUMAN_CLIENT_IDS"));
      if (!clients.isArray() || clients.isEmpty()) throw invalid();
      var approved = new HashSet<String>();
      clients.forEach(
          client -> {
            if (!client.isTextual()
                || !client.asText().matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,254}")
                || !approved.add(client.asText())) throw invalid();
          });
      humanClients = Set.copyOf(approved);
    } catch (java.io.IOException failure) {
      throw invalid();
    }
  }

  @Bean
  AccountLifecycleCodec accountLifecycleCodec() {
    try {
      return new AccountLifecycleCodec(
          Base64.getDecoder().decode(required("ORISO_TASK_CONTEXT_KEY")));
    } catch (IllegalArgumentException failure) {
      throw invalid();
    }
  }

  @Bean
  ReviewedMaintenanceProfiles reviewedMaintenanceProfiles(
      de.caritas.cob.userservice.api.service.accountinvite.ProtectedInvitationProfiles initial,
      KeycloakConfig keycloak) {
    String realmId = required("KEYCLOAK_REALM_INTERNAL_ID");
    if (!realmId.equals(initial.realmId())) throw invalid();
    var initialIds = new TreeMap<String, String>();
    initial.profileIds().forEach((kind, id) -> initialIds.put(kind.name(), id));
    return ReviewedMaintenanceProfiles.read(
        required("ORISO_ACCOUNT_MAINTENANCE_CONFIG"),
        required("ORISO_PROTECTED_INVITATIONS_CONFIG"),
        groups,
        initialIds,
        realmId,
        keycloak.getRealm());
  }

  @Bean
  ClaimedPasswordMaintenance claimedPasswordMaintenance(
      IdentityClientConfig identities,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      KeycloakConfig keycloak,
      ReviewedMaintenanceProfiles reviewed) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    var selectors =
        reviewed.profiles().entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    entry ->
                        entry.getValue().stream()
                            .map(
                                profile ->
                                    new ClaimedPasswordMaintenance.ProfileSelector(
                                        profile.family(), profile.id(), profile.version()))
                            .toList()));
    return new ClaimedPasswordMaintenance(
        identities,
        jdbc,
        manager,
        actors,
        broker,
        codec,
        http,
        Clock.systemUTC(),
        internalServer,
        publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm(),
        keycloak.getRealm(),
        required("KEYCLOAK_REALM_INTERNAL_ID"),
        selectors);
  }

  @Bean
  SelfAppOtpMaintenance selfAppOtpMaintenance(
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig identities,
      AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest request,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      KeycloakConfig keycloak) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    return new SelfAppOtpMaintenance(
        identities,
        user,
        request,
        jdbc,
        manager,
        actors,
        broker,
        codec,
        http,
        Clock.systemUTC(),
        internalServer,
        keycloak.getRealm(),
        required("KEYCLOAK_REALM_INTERNAL_ID"),
        publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm());
  }

  @Bean
  de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance
      humanAdminAccountMaintenance(
          de.caritas.cob.userservice.api.port.out.IdentityClientConfig identities,
          AuthenticatedUser user,
          de.caritas.cob.userservice.api.admin.service.admin.AdminScope scope,
          JdbcTemplate jdbc,
          PlatformTransactionManager manager,
          jakarta.servlet.http.HttpServletRequest request,
          TaskActorConfiguration actors,
          TaskActorTokenBroker broker,
          AccountLifecycleCodec codec,
          @Qualifier("keycloakRestTemplate") RestTemplate http,
          KeycloakConfig keycloak) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    var transport =
        new de.caritas.cob.userservice.api.adapters.keycloak.maintenance
            .KeycloakHumanAdminMaintenance(
            http,
            actors,
            broker,
            codec,
            internalServer,
            keycloak.getRealm(),
            required("KEYCLOAK_REALM_INTERNAL_ID"));
    var maintenance =
        new de.caritas.cob.userservice.api.service.identity.HumanAdminAccountMaintenance(
            identities,
            user,
            scope,
            jdbc,
            manager,
            request,
            transport,
            Clock.systemUTC(),
            publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm());
    maintenance.configureReviewedOrdinaryGroups(groups);
    return maintenance;
  }

  @Bean
  de.caritas.cob.userservice.api.port.out.AccountInactivityIdentityTransport
      accountInactivityIdentityTransport(
          AuthenticatedUser user,
          jakarta.servlet.http.HttpServletRequest request,
          de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService
              existing,
          TaskActorConfiguration actors,
          TaskActorTokenBroker broker,
          AccountLifecycleCodec codec,
          @Qualifier("keycloakRestTemplate") RestTemplate http,
          KeycloakConfig keycloak) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    String issuer = publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm();
    var transport =
        new de.caritas.cob.userservice.api.adapters.keycloak.maintenance
            .KeycloakInactivityMaintenance(
            http,
            actors,
            broker,
            codec,
            internalServer,
            keycloak.getRealm(),
            required("KEYCLOAK_REALM_INTERNAL_ID"),
            issuer);
    existing.configureScopedInactivity(transport, user, request, issuer, humanClients);
    return transport;
  }

  @Bean
  SelfPasswordMaintenance selfPasswordMaintenance(
      IdentityClientConfig identities,
      AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest request,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      KeycloakConfig keycloak) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    return new SelfPasswordMaintenance(
        identities,
        user,
        request,
        jdbc,
        manager,
        actors,
        broker,
        codec,
        http,
        Clock.systemUTC(),
        internalServer,
        publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm(),
        keycloak.getRealm(),
        required("KEYCLOAK_REALM_INTERNAL_ID"));
  }

  @Bean
  SelfLocaleMaintenance selfLocaleMaintenance(
      IdentityClientConfig identities,
      AuthenticatedUser user,
      jakarta.servlet.http.HttpServletRequest request,
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      KeycloakConfig keycloak) {
    String publicServer = keycloak.getAuthServerUrl();
    String internalServer =
        environment.getProperty("ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", publicServer);
    if (publicServer == null
        || publicServer.isBlank()
        || internalServer == null
        || internalServer.isBlank()) throw invalid();
    return new SelfLocaleMaintenance(
        identities,
        user,
        request,
        jdbc,
        manager,
        actors,
        broker,
        codec,
        http,
        Clock.systemUTC(),
        internalServer,
        publicServer.replaceAll("/+$", "") + "/realms/" + keycloak.getRealm(),
        keycloak.getRealm(),
        required("KEYCLOAK_REALM_INTERNAL_ID"));
  }

  @Bean
  JdbcExistingSetupPasswordClaims existingSetupPasswordClaims(JdbcTemplate jdbc) {
    return new JdbcExistingSetupPasswordClaims(jdbc, Clock.systemUTC());
  }

  @Bean
  JdbcAccountLifecycleJournal accountLifecycleJournal(
      JdbcTemplate jdbc, PlatformTransactionManager manager, AccountLifecycleCodec codec) {
    return new JdbcAccountLifecycleJournal(jdbc, manager, codec, Clock.systemUTC(), 300);
  }

  @Bean
  OrdinaryRegistrationEffectCleanup accountLifecycleEffectCleanup(
      MatrixSynapseService matrix, AppointmentService appointments) {
    return new OrdinaryRegistrationEffectCleanup(matrix, appointments);
  }

  @Bean
  JdbcOwnedLifecycleEffects accountLifecycleEffects(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      OrdinaryRegistrationEffectCleanup cleanup,
      AccountLifecycleCodec codec) {
    return new JdbcOwnedLifecycleEffects(jdbc, manager, cleanup, codec);
  }

  @Bean
  IdentityProvisioningCommands accountLifecycleCommands(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      AccountLifecycleCodec codec,
      KeycloakConfig keycloak) {
    String configuredRealm = required("ORISO_ACCOUNT_LIFECYCLE_REALM");
    if (!configuredRealm.equals(keycloak.getRealm())) throw invalid();
    for (var purpose :
        List.of(
            TaskPurpose.CONFIG_WIZARD,
            TaskPurpose.ACCOUNT_PROVISIONING,
            TaskPurpose.ACCOUNT_MAINTENANCE,
            TaskPurpose.SMTP_SYNC,
            TaskPurpose.APPOINTMENT_SYNC)) {
      var actor = actors.require(purpose);
      if (humanClients.contains(actor.clientId())) throw invalid();
    }
    String url =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    if (url == null || url.isBlank()) throw invalid();
    return new KeycloakAccountLifecycleCommands(
        http,
        actors,
        broker,
        codec,
        url,
        keycloak.getRealm(),
        required("KEYCLOAK_REALM_INTERNAL_ID"));
  }

  @Bean
  AccountLifecycleDispatcher accountLifecycleDispatcher(
      IdentityProvisioningCommands commands,
      JdbcAccountLifecycleJournal journal,
      JdbcOwnedLifecycleEffects effects,
      AccountLifecycleCodec codec) {
    return new AccountLifecycleDispatcher(commands, journal, effects, codec);
  }

  @Bean
  AccountLifecycleCoordinator accountLifecycleCoordinator(
      IdentityProvisioningCommands commands,
      JdbcAccountLifecycleJournal journal,
      AccountLifecycleDispatcher dispatcher,
      AccountLifecycleCodec codec) {
    return new AccountLifecycleCoordinator(commands, journal, dispatcher, codec);
  }

  @Bean
  OrdinaryRegistrationLifecycle ordinaryRegistrationLifecycle(
      AccountLifecycleCoordinator coordinator,
      JdbcAccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      MatrixSynapseService matrix,
      MatrixConfig matrixConfig) {
    String group =
        groups.entrySet().stream()
            .filter(e -> e.getValue().equals(Set.of("user")))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(AccountLifecycleCallerConfiguration::invalid);
    return new OrdinaryRegistrationLifecycle(
        coordinator, journal, commands, effects, matrix, matrixConfig, group);
  }

  @Bean
  OrdinaryCreationTenantResolver ordinaryCreationTenantResolver(
      de.caritas.cob.userservice.api.admin.service.tenant.TenantService tenants,
      de.caritas.cob.userservice.api.service.agency.AgencyService agencies,
      de.caritas.cob.userservice.api.port.out.IdentityClientConfig identities) {
    return new OrdinaryCreationTenantResolver(tenants, agencies, identities);
  }

  @Bean
  OrdinaryAccountCreation ordinaryAccountCreation(
      AccountLifecycleCoordinator coordinator,
      JdbcAccountLifecycleJournal journal,
      IdentityProvisioningCommands commands,
      JdbcOwnedLifecycleEffects effects,
      AccountLifecycleCodec codec,
      MatrixSynapseService matrix,
      MatrixConfig matrixConfig,
      AppointmentService appointments,
      AuthenticatedUser user,
      KeycloakConfig keycloak,
      OrdinaryCreationTenantResolver tenantResolver) {
    String issuer =
        keycloak.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + keycloak.getRealm();
    return new OrdinaryAccountCreation(
        coordinator,
        journal,
        commands,
        effects,
        codec,
        matrix,
        matrixConfig,
        appointments,
        user,
        issuer,
        humanClients,
        groups,
        tenantResolver);
  }

  @Bean
  AccountLifecycleRetryScheduler accountLifecycleRetryScheduler(
      JdbcAccountLifecycleJournal journal,
      AccountLifecycleDispatcher dispatcher,
      ClaimedPasswordMaintenance claimed,
      SelfPasswordMaintenance self) {
    return new AccountLifecycleRetryScheduler(journal, dispatcher, claimed, self);
  }

  private String required(String name) {
    String value = environment.getProperty(name);
    if (value == null || value.isBlank() || value.contains("${")) throw invalid();
    return value;
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("Configure the reviewed ordinary lifecycle caller bindings");
  }
}
