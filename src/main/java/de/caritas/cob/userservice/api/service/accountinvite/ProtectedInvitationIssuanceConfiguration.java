package de.caritas.cob.userservice.api.service.accountinvite;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.keycloak.config.KeycloakConfig;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedAccountActivation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedAccountCreation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationAcceptance;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationCancellation;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationJournal;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedAccounts;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedRestrictions;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedAccountRecovery;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationMailOutbox;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationRestrictionCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedOtpBootstrap;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorTokenBroker;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.AccountInactivityEnrollmentService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.OperatorDpaContentClient;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.ProtectedAdminOnboardingService;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.TenantCreationClient;
import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/** Uses the existing coordinated C activation, actor registry and sole managed context key. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ORISO_ACCOUNT_LIFECYCLE_ENABLED", havingValue = "true")
public class ProtectedInvitationIssuanceConfiguration {
  private final Environment environment;

  public ProtectedInvitationIssuanceConfiguration(Environment environment) {
    this.environment = Objects.requireNonNull(environment);
  }

  @Bean
  public ProtectedInvitationProfiles protectedInvitationProfiles() {
    try {
      if (!"FIVE_ACTORS".equals(required("TASK_IDENTITY_MODEL"))) throw invalid();
      String raw = required("ORISO_PROTECTED_INVITATION_PROFILE_IDS");
      if (raw.getBytes(StandardCharsets.UTF_8).length > 4096) throw invalid();
      var mapper =
          new ObjectMapper()
              .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
              .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
      var node = mapper.readTree(raw);
      if (!node.isObject()) throw invalid();
      var names = new HashSet<String>();
      node.fieldNames().forEachRemaining(names::add);
      if (!names.equals(Set.of("TENANT_ADMIN", "PLATFORM_ADMIN"))) throw invalid();
      var ids = new EnumMap<AccountInviteTargetRole, String>(AccountInviteTargetRole.class);
      for (var role :
          Set.of(AccountInviteTargetRole.TENANT_ADMIN, AccountInviteTargetRole.PLATFORM_ADMIN)) {
        if (!node.path(role.name()).isTextual()) throw invalid();
        ids.put(role, node.path(role.name()).textValue());
      }
      return new ProtectedInvitationProfiles(required("KEYCLOAK_REALM_INTERNAL_ID"), ids);
    } catch (java.io.IOException | RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean
  ProtectedInvitationCodec protectedInvitationCodec(
      ProtectedInvitationProfiles profiles, KeycloakConfig keycloak) {
    String realm = required("ORISO_ACCOUNT_LIFECYCLE_REALM");
    if (!realm.equals(keycloak.getRealm())) throw invalid();
    return new ProtectedInvitationCodec(key(), Clock.systemUTC(), realm, profiles.realmId());
  }

  @Bean
  JdbcProtectedInvitationJournal protectedInvitationJournal(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      ProtectedInvitationCodec codec,
      AccountLifecycleCodec json) {
    return new JdbcProtectedInvitationJournal(jdbc, manager, codec, json, Clock.systemUTC());
  }

  @Bean
  KeycloakProtectedInvitations keycloakProtectedInvitations(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec codec,
      KeycloakConfig keycloak) {
    String server =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    return new KeycloakProtectedInvitations(
        http, actors, broker, codec, server, keycloak.getRealm());
  }

  @Bean
  ProtectedInvitationMailOutbox protectedInvitationMailOutbox(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      AccountLifecycleCodec json,
      ProtectedInvitationProfiles profiles,
      KeycloakProtectedInvitations approvals,
      InviteMailDispatchService sender) {
    return new ProtectedInvitationMailOutbox(
        jdbc,
        manager,
        journal,
        json,
        key(),
        profiles.realmId(),
        Clock.systemUTC(),
        approvals,
        sender);
  }

  @Bean
  ProtectedInvitationIssuance protectedInvitationIssuance(
      AccountInviteRepository invites,
      InviteEmailTemplateRepository templates,
      InviteEmailDeliveryRepository deliveries,
      PlatformTransactionManager manager,
      JdbcTemplate jdbc,
      EntityManager entityManager,
      AuthenticatedUser human,
      TaskActorConfiguration actors,
      ProtectedInvitationProfiles profiles,
      AccountLifecycleCodec json,
      ProtectedInvitationCodec codec,
      JdbcProtectedInvitationJournal journal,
      KeycloakProtectedInvitations approvals,
      ProtectedInvitationMailOutbox outbox) {
    return new ProtectedInvitationIssuance(
        invites,
        templates,
        deliveries,
        manager,
        jdbc,
        entityManager,
        human,
        actors,
        profiles,
        json,
        codec,
        journal,
        approvals,
        outbox);
  }

  @Bean
  ProtectedInvitationRestrictionCodec protectedInvitationRestrictionCodec(
      ProtectedInvitationCodec codec,
      AccountLifecycleCodec json,
      ProtectedInvitationProfiles profiles,
      KeycloakConfig keycloak) {
    String issuer =
        keycloak.getAuthServerUrl().replaceAll("/+$", "") + "/realms/" + keycloak.getRealm();
    return new ProtectedInvitationRestrictionCodec(
        codec, json, key(), Clock.systemUTC(), profiles.realmId(), issuer);
  }

  @Bean
  KeycloakProtectedRestrictions keycloakProtectedRestrictions(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedInvitationCodec codec,
      ProtectedInvitationRestrictionCodec restrictions,
      KeycloakConfig keycloak) {
    String server =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    return new KeycloakProtectedRestrictions(
        http, actors, broker, codec, restrictions, server, keycloak.getRealm());
  }

  @Bean
  JdbcProtectedInvitationCancellation protectedInvitationCancellation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationRestrictionCodec restrictions,
      AccountLifecycleCodec json,
      KeycloakProtectedRestrictions receiver,
      ProtectedInvitationIssuance issuance,
      AccountInviteRepository invites,
      EntityManager entityManager) {
    return new JdbcProtectedInvitationCancellation(
        jdbc,
        manager,
        journal,
        restrictions,
        json,
        receiver,
        id -> {
          var invite =
              invites.findById(id).orElseThrow(ProtectedInvitationIssuanceConfiguration::invalid);
          entityManager.refresh(invite);
          return issuance.restrictionProposal(invite);
        },
        Clock.systemUTC());
  }

  private byte[] key() {
    try {
      return Base64.getDecoder().decode(required("ORISO_TASK_CONTEXT_KEY"));
    } catch (RuntimeException failure) {
      throw invalid();
    }
  }

  @Bean
  ProtectedAccountCodec protectedAccountCodec(
      ProtectedInvitationCodec approvals,
      ProtectedInvitationProfiles profiles,
      KeycloakConfig keycloak) {
    String issuer =
        UriComponentsBuilder.fromUriString(keycloak.getAuthServerUrl())
            .pathSegment("realms", keycloak.getRealm())
            .build()
            .encode()
            .toUriString();
    return new ProtectedAccountCodec(
        key(), Clock.systemUTC(), approvals, keycloak.getRealm(), profiles.realmId(), issuer);
  }

  @Bean
  JdbcProtectedInvitationAcceptance protectedInvitationAcceptance(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedInvitationCodec approvals,
      AccountLifecycleCodec json) {
    return new JdbcProtectedInvitationAcceptance(
        jdbc, manager, journal, approvals, json, Clock.systemUTC());
  }

  @Bean
  JdbcProtectedAccountCreation protectedAccountCreation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json) {
    return new JdbcProtectedAccountCreation(
        jdbc, manager, journal, accounts, json, Clock.systemUTC());
  }

  @Bean
  KeycloakProtectedAccounts keycloakProtectedAccounts(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedAccountCodec accounts,
      KeycloakConfig keycloak) {
    String server =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    return new KeycloakProtectedAccounts(
        http, actors, broker, accounts, server, keycloak.getRealm());
  }

  @Bean
  ProtectedAccountRecovery protectedAccountRecovery(
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      ProtectedAccountCodec accounts,
      KeycloakConfig keycloak) {
    String server =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    return new ProtectedAccountRecovery(
        http, actors, broker, accounts, server, keycloak.getRealm());
  }

  @Bean
  ProtectedOtpBootstrap protectedOtpBootstrap(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      AccountLifecycleCodec json,
      TaskActorConfiguration actors,
      TaskActorTokenBroker broker,
      @Qualifier("keycloakRestTemplate") RestTemplate http,
      HttpServletRequest request,
      ProtectedInvitationProfiles profiles,
      KeycloakConfig keycloak) {
    String server =
        environment.getProperty(
            "ORISO_IDENTITY_INTERNAL_AUTH_SERVER_URL", keycloak.getAuthServerUrl());
    return new ProtectedOtpBootstrap(
        jdbc,
        manager,
        json,
        actors,
        broker,
        http,
        request,
        Clock.systemUTC(),
        server,
        keycloak.getRealm(),
        profiles.realmId());
  }

  @Bean
  JdbcProtectedAccountActivation protectedAccountActivation(
      JdbcTemplate jdbc,
      PlatformTransactionManager manager,
      JdbcProtectedInvitationJournal journal,
      ProtectedAccountCodec accounts,
      AccountLifecycleCodec json,
      ProtectedOtpBootstrap otp,
      AdminRepository admins,
      AccountInactivityEnrollmentService inactivity) {
    return new JdbcProtectedAccountActivation(
        jdbc, manager, journal, accounts, json, otp, admins, inactivity, Clock.systemUTC());
  }

  @Bean
  ProtectedAdminOnboardingService protectedAdminOnboardingService(
      AccountInviteService invites,
      TaskActorConfiguration actors,
      JdbcProtectedInvitationAcceptance acceptance,
      ProtectedAccountCodec accounts,
      JdbcProtectedAccountCreation creation,
      KeycloakProtectedAccounts nativeAccounts,
      ProtectedOtpBootstrap otp,
      JdbcProtectedAccountActivation activation,
      AccountInactivityEnrollmentService inactivity,
      UsernameTranscoder usernames,
      ProtectedInvitationProfiles profiles,
      OperatorDpaContentClient dpa,
      TenantCreationClient tenants) {
    return new ProtectedAdminOnboardingService(
        invites,
        actors,
        acceptance,
        accounts,
        creation,
        nativeAccounts,
        otp,
        activation,
        inactivity,
        usernames,
        profiles,
        dpa,
        tenants,
        Clock.systemUTC());
  }

  private String required(String name) {
    String value = environment.getProperty(name);
    if (value == null || value.isBlank() || value.contains("${")) throw invalid();
    return value;
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException(
        "Configure the reviewed protected invitation profile bindings");
  }
}
