package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.JdbcProtectedInvitationJournal;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.ProtectedInvitationMailOutbox;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.config.auth.TaskActor;
import de.caritas.cob.userservice.api.config.auth.TaskActorConfiguration;
import de.caritas.cob.userservice.api.config.auth.TaskActorCredentials;
import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdReservationReleaseTaskRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.SendInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.AgencyIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdReservationReleaseProcessor;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdAllocationClient;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.TenantIdReservation;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailSendReceipt;
import de.caritas.cob.userservice.api.tenant.AsTechnicalUser;
import de.caritas.cob.userservice.api.tenant.Tenants;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Actual public service/JPA boundary; HTTP and SMTP are explicit external test doubles. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationIssuanceJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Test
  void unknownApprovalLeavesCommittedDraftAndExactProposalWithoutTokenOrMail() {
    doThrow(new KeycloakProtectedInvitations.RemoteFailure(0))
        .when(approvals)
        .approve(any(), anyString());

    assertThatThrownBy(() -> service.createAndSendInvite(command(), template.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    AccountInvite saved = invites.findAll().getFirst();
    assertThat(saved.getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(saved.getTokenHash()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM protected_invitation_authorization WHERE invite_id=?",
                Integer.class,
                saved.getId()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT request_json FROM protected_invitation_authorization WHERE invite_id=?",
                String.class,
                saved.getId()))
        .doesNotContain(human.getAccessToken());
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM protected_invitation_mail_outbox", Integer.class))
        .isZero();
    verify(approvals).approve(any(), anyString());
    verifyNoInteractions(sender);
    assertThat(deliveries.count()).isZero();
  }

  @Test
  void sendingAnExistingDraftCannotMintALinkBeforeHumanApproval() {
    AccountInvite draft = service.createInvite(command());
    doThrow(new KeycloakProtectedInvitations.RemoteFailure(0))
        .when(approvals)
        .approve(any(), anyString());

    assertThatThrownBy(
            () -> service.sendInvite(new SendInviteCommand(draft.getId(), template.getId())))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    assertThat(invites.findById(draft.getId()).orElseThrow().getTokenHash()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM protected_invitation_authorization WHERE invite_id=?",
                Integer.class,
                draft.getId()))
        .isEqualTo(1);
    verifyNoInteractions(sender);
  }

  @Test
  void commonDeliveryCannotBypassProtectedApprovalWithAPreparedLink() {
    AccountInvite draft = service.createInvite(command());
    String token = "A".repeat(43);
    var prepared =
        new InviteDelivery.Prepared(
            draft,
            template,
            token,
            "https://admin.example.test/invite/" + token,
            "Invitation",
            "Please accept your invitation",
            LocalDateTime.now());

    assertThatThrownBy(() -> delivery.deliver(prepared, draft.getId(), false, ignored -> {}))
        .isInstanceOf(AccessDeniedException.class);

    verifyNoInteractions(sender, approvals);
    assertThat(deliveries.count()).isZero();
  }
}

@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  AccountInviteService.class, InviteTargetResolver.class, ReservationLedger.class,
  UnitQueue.class, InviteDelivery.class, AccountInviteTopicPermissionService.class,
  AccountInviteAccessPolicy.class, AdminScope.class, IdReservationReleaseProcessor.class,
  ProtectedInvitationIssuanceJpaFixture.Components.class
})
@AsTechnicalUser
abstract class ProtectedInvitationIssuanceJpaFixture {
  static final String RECIPIENT = "protected-owner@example.test";
  static final byte[] KEY =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  @Autowired AccountInviteService service;
  @Autowired InviteDelivery delivery;
  @Autowired AccountInviteRepository invites;
  @Autowired InviteEmailTemplateRepository templates;
  @Autowired InviteEmailDeliveryRepository deliveries;
  @Autowired IdReservationReleaseTaskRepository releases;
  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource source;
  @Autowired PlatformTransactionManager transactions;
  @Autowired Environment environment;

  @MockitoBean(answers = Answers.CALLS_REAL_METHODS)
  AuthenticatedUser human;

  @MockitoBean ExistingAccountSetupIssuer existingAccountSetupIssuer;
  @MockitoBean de.caritas.cob.userservice.api.service.agency.AgencyService agencyService;
  @MockitoBean IdentityEmailOwnerLookup identityEmailOwnerLookup;
  @MockitoBean TenantService tenantService;
  @MockitoBean TenantIdAllocationClient tenantIds;
  @MockitoBean AgencyIdAllocationClient agencyIds;
  @MockitoBean AgencyFacts agencyFacts;
  @MockitoBean InviteAcceptUrlBuilder acceptUrls;
  @MockitoBean InviteMailDispatchService sender;
  @MockitoBean InviteEmailDeliveryFailureRecorder failureRecorder;
  @MockitoBean KeycloakProtectedInvitations approvals;
  @MockitoBean TaskActorConfiguration actors;
  InviteEmailTemplate template;

  @BeforeEach
  void actualPublicServiceFixture() {
    assertThat(transactions).isInstanceOf(JpaTransactionManager.class);
    assertThat(((JpaTransactionManager) transactions).getDataSource()).isSameAs(source);
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    // Testing disables Liquibase; install the real append-only SQL explicitly.
    jdbc.execute("DROP TABLE IF EXISTS protected_invitation_mail_outbox");
    jdbc.execute("DROP TABLE IF EXISTS protected_invitation_authorization");
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_protected_invitation_367/migrate.sql"))
        .execute(source);
    new ResourceDatabasePopulator(
            new ClassPathResource(
                "db/changelog/changeset/20261022_protected_invitation_mail_367/migrate.sql"))
        .execute(source);
    Tenants.actAs(
        human,
        "15a51abf-a6e7-4513-942a-d1ea209e00dd",
        0L,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
    human.setUsername("reviewed-human@example.test");
    human.setAccessToken("synthetic-immediate-human-memory-only");
    when(identityEmailOwnerLookup.findByEmail(RECIPIENT)).thenReturn(Optional.empty());
    when(tenantIds.reserve(null))
        .thenReturn(new TenantIdReservation(17L, "9eb4c868-16d4-4be5-8c0b-5fb9d2b866b3"));
    when(actors.isEnabled())
        .thenReturn("FIVE_ACTORS".equals(environment.getProperty("TASK_IDENTITY_MODEL")));
    when(actors.require(TaskPurpose.ACCOUNT_PROVISIONING))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.ACCOUNT_CREATE,
                "backend-account-create",
                "synthetic-create-secret",
                "259d23a0-5b65-4559-8cc1-aa5683961d02"));
    when(actors.require(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn(
            new TaskActorCredentials(
                TaskActor.SETUP,
                "backend-setup",
                "synthetic-setup-secret",
                "4ebf5f9c-a3f4-41fe-8a22-ec1f511d77ed"));
    when(acceptUrls.buildAcceptUrl(any(), anyString()))
        .thenAnswer(call -> "https://admin.example.test/invite/" + call.getArgument(1));
    when(sender.send(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new InviteMailSendReceipt(RECIPIENT, Instant.now()));
    template =
        templates.saveAndFlush(
            InviteEmailTemplate.builder()
                .kind(InviteEmailTemplateKind.TENANT_INVITE)
                .name("Protected welcome")
                .subject("Welcome")
                .body("Please accept {{inviteLink}}")
                .language("de")
                .active(true)
                .createDate(LocalDateTime.now())
                .build());
  }

  @AfterEach
  void removeOnlyEphemeralTestRows() {
    jdbc.execute("DROP TABLE IF EXISTS protected_invitation_mail_outbox");
    jdbc.execute("DROP TABLE IF EXISTS protected_invitation_authorization");
    deliveries.deleteAll();
    releases.deleteAll();
    invites.deleteAll();
    templates.deleteAll();
  }

  CreateAccountInviteCommand command() {
    return new CreateAccountInviteCommand(
        AccountInviteTargetRole.TENANT_ADMIN,
        null,
        RECIPIENT,
        "Ada",
        "Lovelace",
        null,
        null,
        30L,
        IdAllocationMode.AUTO,
        null);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class Components {
    @Bean
    JdbcTemplate protectedIssuanceJdbc(DataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    AccountLifecycleCodec protectedIssuanceJson() {
      return new AccountLifecycleCodec(KEY);
    }

    @Bean
    ProtectedInvitationCodec protectedIssuanceCodec() {
      return new ProtectedInvitationCodec(
          KEY, Clock.systemUTC(), "oriso", "native-synthetic-realm");
    }

    @Bean
    JdbcProtectedInvitationJournal protectedIssuanceJournal(
        JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        ProtectedInvitationCodec codec,
        AccountLifecycleCodec json) {
      return new JdbcProtectedInvitationJournal(jdbc, manager, codec, json, Clock.systemUTC());
    }

    @Bean
    ProtectedInvitationMailOutbox protectedIssuanceOutbox(
        JdbcTemplate jdbc,
        PlatformTransactionManager manager,
        JdbcProtectedInvitationJournal journal,
        AccountLifecycleCodec json,
        KeycloakProtectedInvitations approvals,
        InviteMailDispatchService sender) {
      return new ProtectedInvitationMailOutbox(
          jdbc,
          manager,
          journal,
          json,
          KEY,
          "native-synthetic-realm",
          Clock.systemUTC(),
          approvals,
          sender);
    }

    @Bean
    ProtectedInvitationIssuance protectedIssuance(
        AccountInviteRepository invites,
        InviteEmailTemplateRepository templates,
        InviteEmailDeliveryRepository deliveries,
        PlatformTransactionManager manager,
        JdbcTemplate jdbc,
        EntityManager entityManager,
        AuthenticatedUser human,
        TaskActorConfiguration actors,
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
          new ProtectedInvitationProfiles(
              "native-synthetic-realm",
              Map.of(
                  AccountInviteTargetRole.TENANT_ADMIN,
                  "tenant-owner",
                  AccountInviteTargetRole.PLATFORM_ADMIN,
                  "platform-v1")),
          json,
          codec,
          journal,
          approvals,
          outbox);
    }
  }
}
