package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.InviteEmailDelivery;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.IdentityEmailOwnerLookup;
import de.caritas.cob.userservice.api.port.out.InviteEmailDeliveryRepository;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.mail.InviteMailDispatchService;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.notification.AdminPanelUrl;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

/** Database-backed deletion checks use the real tenant and template-kind policy. */
@DataJpaTest(showSql = false)
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
// Preserve the pinned private H2 URL and its MariaDB mode; dynamic properties reject env redirects.
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import({
  InviteEmailTemplateService.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  InviteEmailTemplateDeleteIT.CallerConfig.class
})
class InviteEmailTemplateDeleteIT {

  private static final String EMBEDDED_DATABASE_URL =
      "jdbc:h2:mem:template-delete-scope-"
          + UUID.randomUUID()
          + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;NON_KEYWORDS=USER,VALUE,DAY";

  @DynamicPropertySource
  static void usePrivateEmbeddedDatabase(DynamicPropertyRegistry registry) {
    // Higher priority than generic datasource environment variables: never create/drop shared data.
    registry.add("spring.datasource.url", () -> EMBEDDED_DATABASE_URL);
    registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
    registry.add("spring.datasource.username", () -> "sa");
    registry.add("spring.datasource.password", () -> "");
    registry.add(
        "spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.H2Dialect");
  }

  enum Caller {
    PLATFORM,
    TENANT,
    AGENCY
  }

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private InviteEmailTemplateService service;
  @Autowired private InviteEmailTemplateRepository templates;
  @Autowired private AccountInviteRepository invites;
  @Autowired private InviteEmailDeliveryRepository deliveries;
  @Autowired private AuthenticatedUser caller;
  @Autowired private AccountInviteAccessPolicy policy;
  @Autowired private DataSource dataSource;
  @MockitoBean private AgencyService agencyService;

  @BeforeAll
  static void installQueueConstraint(@Autowired DataSource dataSource) throws Exception {
    try (var connection = dataSource.getConnection();
        var migration =
            new Liquibase(
                "db/changelog/changeset/20261013_invite_template_delete_guard/changeSet.xml",
                new ClassLoaderResourceAccessor(),
                DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
      migration.update(new Contexts(), new LabelExpression());
    }
  }

  @Test
  void databaseAlwaysUsesPrivateEmbeddedH2WithRequiredMariaDbCompatibility() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.getMetaData().getURL())
          .startsWith("jdbc:h2:mem:template-delete-scope-");
      try (var sql = connection.createStatement();
          var settings =
              sql.executeQuery(
                  "SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS"
                      + " WHERE SETTING_NAME IN ('MODE', 'NON_KEYWORDS')")) {
        var values = new java.util.HashMap<String, String>();
        while (settings.next()) {
          values.put(settings.getString(1), settings.getString(2));
        }
        assertThat(values).containsEntry("MODE", "MariaDB");
        assertThat(values.get("NON_KEYWORDS").split(","))
            .containsExactlyInAnyOrder("USER", "VALUE", "DAY");
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Caller.class)
  void deletesOwnCustomTemplateAndKeepsSentHistory(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(role == Caller.PLATFORM ? null : 1L, false);
    AccountInvite sent = invite(template, AccountInviteStatus.EMAIL_SENT);
    // Old release paths retained the queue id even after sending. Deletion retires that payload.
    sent.setQueuedTemplateId(template.getId());
    invites.saveAndFlush(sent);
    InviteEmailDelivery history = delivery(sent, template);
    InviteEmailTemplate other = template(2L, false);

    service.deleteTemplate(template.getId());
    templates.flush();

    assertThat(templates.findById(template.getId())).isEmpty();
    assertThat(templates.findById(other.getId())).isPresent();
    assertThat(invites.findById(sent.getId()).orElseThrow().getStatus())
        .isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(invites.findById(sent.getId()).orElseThrow().getQueuedTemplateId()).isNull();
    InviteEmailDelivery stored = deliveries.findById(history.getId()).orElseThrow();
    assertThat(stored.getTemplateId()).isEqualTo(template.getId());
    assertThat(stored.getSubjectSnapshot()).isEqualTo("Sent subject");
    assertThat(stored.getBodySnapshot()).isEqualTo("Sent body");
  }

  @ParameterizedTest
  @EnumSource(Caller.class)
  void refusesSystemDefaultAndKeepsHistory(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(null, true);
    InviteEmailDelivery history =
        delivery(invite(template, AccountInviteStatus.EMAIL_SENT), template);

    assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
        .isInstanceOf(ForbiddenException.class);

    assertThat(templates.findById(template.getId())).isPresent();
    assertThat(deliveries.findById(history.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"TENANT", "AGENCY"})
  void refusesForeignTenantTemplateAndPreservesIt(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(2L, false);

    assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
        .isInstanceOf(ForbiddenException.class);

    assertThat(templates.findById(template.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"TENANT", "AGENCY"})
  void refusesReadonlyPlatformCustomTemplate(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(null, false);

    assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
        .isInstanceOf(ForbiddenException.class);

    assertThat(templates.findById(template.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"TENANT", "AGENCY"})
  void refusesTemplateKindOutsideCallerReach(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(1L, false);
    template.setKind(
        role == Caller.AGENCY
            ? InviteEmailTemplateKind.TENANT_INVITE
            : InviteEmailTemplateKind.DPA_FORWARD);
    templates.saveAndFlush(template);

    assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
        .isInstanceOf(ForbiddenException.class);

    assertThat(templates.findById(template.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(Caller.class)
  void refusesQueuedTemplateWithoutChangingQueuedInviteOrDelivery(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(role == Caller.PLATFORM ? null : 1L, false);
    AccountInvite queued = invite(template, AccountInviteStatus.WAITING_FOR_UNIT);
    InviteEmailDelivery history =
        delivery(invite(template, AccountInviteStatus.EMAIL_SENT), template);

    assertThatThrownBy(() -> service.deleteTemplate(template.getId()))
        .isInstanceOf(ConflictException.class);

    assertThat(templates.findById(template.getId())).isPresent();
    AccountInvite stored = invites.findById(queued.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(AccountInviteStatus.WAITING_FOR_UNIT);
    assertThat(stored.getQueuedTemplateId()).isEqualTo(template.getId());
    assertThat(deliveries.findById(history.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(Caller.class)
  void missingTemplateReportsNotFoundAndKeepsOtherRows(Caller role) {
    actAs(role);
    InviteEmailTemplate template = template(1L, false);

    assertThatThrownBy(() -> service.deleteTemplate(Long.MAX_VALUE))
        .isInstanceOf(NotFoundException.class);

    assertThat(templates.findById(template.getId())).isPresent();
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"TENANT", "AGENCY"})
  void scopedCatalogueAndDirectIdsAllowOnlyOwnTemplatesAndSystemDefaults(Caller role) {
    actAs(role);
    InviteEmailTemplate own = template(1L, false);
    InviteEmailTemplate system = template(null, true);
    InviteEmailTemplate legacyPlatformCustom = template(null, false);
    InviteEmailTemplate foreign = template(2L, false);
    InviteEmailTemplate foreignFlaggedAsSystem = template(2L, true);

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getId)
        .containsExactlyInAnyOrder(own.getId(), system.getId());
    assertThat(service.requireUsableTemplate(own.getId()).getId()).isEqualTo(own.getId());
    assertThat(service.requireUsableTemplate(system.getId()).getId()).isEqualTo(system.getId());
    for (InviteEmailTemplate hidden :
        java.util.List.of(legacyPlatformCustom, foreign, foreignFlaggedAsSystem)) {
      assertThatThrownBy(() -> service.requireUsableTemplate(hidden.getId()))
          .isInstanceOf(ForbiddenException.class);
    }
    assertThat(templates.count()).isEqualTo(5);
  }

  @ParameterizedTest
  @EnumSource(
      value = Caller.class,
      names = {"TENANT", "AGENCY"})
  void directPreviewAndSendRejectExcludedTemplateIdsBeforeRenderingOrWriting(Caller role) {
    actAs(role);
    InviteEmailTemplate legacyPlatform = template(null, false);
    InviteEmailTemplate foreign = template(2L, false);
    var dispatch = mock(InviteMailDispatchService.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    var preview =
        new InviteEmailPreviewService(
            templates,
            policy,
            mock(InviteAcceptUrlBuilder.class),
            dispatch,
            mock(AdminPanelUrl.class));
    var sender =
        new AccountInviteService(
            invites,
            templates,
            caller,
            mock(IdentityEmailOwnerLookup.class),
            transactionManager,
            policy,
            mock(AgencyFacts.class),
            mock(InviteTargetResolver.class),
            mock(ReservationLedger.class),
            mock(UnitQueue.class),
            mock(InviteDelivery.class),
            mock(ExistingAccountSetupIssuer.class));
    long before = invites.count();

    for (InviteEmailTemplate hidden : java.util.List.of(legacyPlatform, foreign)) {
      assertThatThrownBy(
              () ->
                  preview.preview(
                      new InviteEmailPreviewService.PreviewCommand(
                          hidden.getId(), null, null, null, null)))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(() -> sender.createAndSendInvite(null, hidden.getId()))
          .isInstanceOf(ForbiddenException.class);
    }

    assertThat(invites.count()).isEqualTo(before);
    verifyNoInteractions(dispatch, transactionManager);
  }

  private InviteEmailTemplate template(Long tenantId, boolean systemDefault) {
    LocalDateTime now = LocalDateTime.now();
    return templates.saveAndFlush(
        InviteEmailTemplate.builder()
            .tenantId(tenantId)
            .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
            .name("Custom invite")
            .language("de")
            .subject("Editable subject")
            .body("Editable body")
            .active(true)
            .systemDefault(systemDefault)
            .createDate(now)
            .updateDate(now)
            .build());
  }

  private AccountInvite invite(InviteEmailTemplate template, AccountInviteStatus status) {
    LocalDateTime now = LocalDateTime.now();
    return invites.saveAndFlush(
        AccountInvite.builder()
            .targetRole(AccountInviteTargetRole.COUNSELLOR)
            .tenantId(1L)
            .recipientEmail("template-delete@example.test")
            .status(status)
            .queuedTemplateId(
                status == AccountInviteStatus.WAITING_FOR_UNIT ? template.getId() : null)
            .waitingForUnit(
                status == AccountInviteStatus.WAITING_FOR_UNIT ? InviteUnitType.AGENCY : null)
            .emailVerificationStatus(EmailVerificationStatus.PENDING)
            .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
            .createDate(now)
            .updateDate(now)
            .build());
  }

  private InviteEmailDelivery delivery(AccountInvite invite, InviteEmailTemplate template) {
    return deliveries.saveAndFlush(
        InviteEmailDelivery.builder()
            .accountInviteId(invite.getId())
            .templateId(template.getId())
            .templateKind(template.getKind())
            .subjectSnapshot("Sent subject")
            .bodySnapshot("Sent body")
            .recipientSnapshot(invite.getRecipientEmail())
            .status(InviteEmailDeliveryStatus.SENT)
            .createDate(LocalDateTime.now())
            .build());
  }

  private void actAs(Caller role) {
    caller.setUserId(
        role == Caller.AGENCY ? "d42c2e5e-143c-4db1-a90f-7cccf82fbb15" : "template-admin");
    caller.setTenantId(role == Caller.PLATFORM ? 0L : 1L);
    UserRole[] roles =
        role == Caller.AGENCY
            ? new UserRole[] {UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN}
            : new UserRole[] {UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN};
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }
}
