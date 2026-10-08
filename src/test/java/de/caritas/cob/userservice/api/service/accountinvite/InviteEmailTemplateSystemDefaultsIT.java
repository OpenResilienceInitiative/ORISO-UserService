package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.COUNSELLOR_INVITE;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.DPA_FORWARD;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.TENANT_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The built-in system default templates and the cross-Träger isolation around them, against a real
 * database.
 *
 * <p>The defaults are the rows the Liquibase seed writes; this IT runs the very same seed file, so
 * the test data cannot drift from what production gets. Every admin sees and may send with them,
 * only the platform admin may change their text, and nobody may move them to another kind or
 * language or switch them off.
 *
 * <p>Isolation: a non-platform admin sees exactly the platform's templates plus their own Träger's
 * and cannot use or change another Träger's template even when they know its id. The owner of a
 * template is the Träger only, so every Beratungsstellen admin of that Träger sees it.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  InviteEmailTemplateService.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  InviteEmailTemplateSystemDefaultsIT.CallerConfig.class
})
class InviteEmailTemplateSystemDefaultsIT {

  static final String SYSTEM_DEFAULTS_SEED =
      "db/changelog/changeset/20261001_invite_email_template_system_default/seed-system-defaults.sql";

  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";
  private static final String OTHER_AGENCY_ADMIN_ID = "other-bst-admin-of-traeger-a";
  private static final long TRAEGER_A = 1L;
  private static final long TRAEGER_B = 2L;

  @TestConfiguration
  static class CallerConfig {
    @Bean
    AuthenticatedUser authenticatedUser() {
      return new AuthenticatedUser();
    }
  }

  @Autowired private InviteEmailTemplateService service;
  @Autowired private InviteEmailTemplateRepository templateRepository;
  @Autowired private AuthenticatedUser caller;
  @Autowired private DataSource dataSource;

  @MockitoBean private AgencyService agencyService;

  @AfterEach
  void cleanUp() {
    templateRepository.deleteAll();
  }

  // --- the seed ----------------------------------------------------------------------------

  @Test
  void seed_Should_WriteOneActivePlatformDefaultPerKindAndLanguage() throws Exception {
    runSystemDefaultsSeed();

    List<InviteEmailTemplate> defaults = systemDefaults();
    assertThat(defaults)
        .extracting(
            InviteEmailTemplate::getKind,
            InviteEmailTemplate::getLanguage,
            InviteEmailTemplate::getTenantId,
            InviteEmailTemplate::getActive)
        .containsExactlyInAnyOrder(
            tuple(COUNSELLOR_INVITE, "de", null, true),
            tuple(COUNSELLOR_INVITE, "en", null, true),
            tuple(TENANT_INVITE, "de", null, true),
            tuple(TENANT_INVITE, "en", null, true));
    assertThat(defaults)
        .allSatisfy(
            t -> {
              assertThat(t.getSubject()).isNotBlank();
              assertThat(AccountInviteService.withoutActionLink(t.getBody())).isNotBlank();
              // Paragraphs survive the seed: the layout turns blank lines into paragraphs.
              assertThat(t.getBody()).contains("\n\n");
            });
  }

  @Test
  void seed_Should_NotDuplicate_When_RunTwice() throws Exception {
    runSystemDefaultsSeed();
    runSystemDefaultsSeed();

    assertThat(systemDefaults()).hasSize(4);
  }

  // --- who sees, uses and changes the defaults ---------------------------------------------

  @Test
  void systemDefaults_Should_BeVisibleToEveryAdmin() throws Exception {
    runSystemDefaultsSeed();
    List<String> all =
        List.of(
            "COUNSELLOR_INVITE/de", "COUNSELLOR_INVITE/en", "TENANT_INVITE/de", "TENANT_INVITE/en");

    actAsPlatformAdmin();
    assertThat(defaultsIn(service.listTemplates(null))).containsExactlyInAnyOrderElementsOf(all);
    actAsTenantAdmin(TRAEGER_A);
    assertThat(defaultsIn(service.listTemplates(null))).containsExactlyInAnyOrderElementsOf(all);
    actAsTenantAdmin(TRAEGER_B);
    assertThat(defaultsIn(service.listTemplates(null))).containsExactlyInAnyOrderElementsOf(all);
    actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_A);
    assertThat(defaultsIn(service.listTemplates(COUNSELLOR_INVITE)))
        .containsExactlyInAnyOrder("COUNSELLOR_INVITE/de", "COUNSELLOR_INVITE/en");
  }

  @Test
  void systemDefaults_Should_BeUsableButNotChangeable_When_TraegerOrAgencyAdmin() throws Exception {
    runSystemDefaultsSeed();
    InviteEmailTemplate counsellorDe = systemDefault(COUNSELLOR_INVITE, "de");

    actAsTenantAdmin(TRAEGER_A);
    assertThat(service.requireUsableTemplate(counsellorDe.getId()).getId())
        .isEqualTo(counsellorDe.getId());
    assertThat(service.mayChange(counsellorDe)).isFalse();
    assertThatThrownBy(() -> service.updateTemplate(counsellorDe.getId(), command("Mine")))
        .isInstanceOf(ForbiddenException.class);

    actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_A);
    assertThat(service.requireUsableTemplate(counsellorDe.getId()).getId())
        .isEqualTo(counsellorDe.getId());
    assertThat(service.mayChange(counsellorDe)).isFalse();
    assertThatThrownBy(() -> service.updateTemplate(counsellorDe.getId(), command("Mine")))
        .isInstanceOf(ForbiddenException.class);

    assertThat(systemDefault(COUNSELLOR_INVITE, "de").getSubject())
        .isEqualTo(counsellorDe.getSubject());
  }

  @Test
  void systemDefaults_Should_StayInPlace_When_PlatformAdminEditsThem() throws Exception {
    runSystemDefaultsSeed();
    InviteEmailTemplate tenantEn = systemDefault(TENANT_INVITE, "en");
    actAsPlatformAdmin();
    assertThat(service.mayChange(tenantEn)).isTrue();

    InviteEmailTemplate edited =
        service.updateTemplate(
            tenantEn.getId(),
            new TemplateCommand(TENANT_INVITE, "Edited", "en", "New subject", "New body", true));
    assertThat(edited.getSubject()).isEqualTo("New subject");
    assertThat(edited.getSystemDefault()).isTrue();

    for (TemplateCommand moved :
        List.of(
            new TemplateCommand(COUNSELLOR_INVITE, "X", "en", "S", "B", true),
            new TemplateCommand(TENANT_INVITE, "X", "de", "S", "B", true),
            new TemplateCommand(TENANT_INVITE, "X", "en", "S", "B", false))) {
      assertThatThrownBy(() -> service.updateTemplate(tenantEn.getId(), moved))
          .as("%s", moved)
          .isInstanceOf(BadRequestException.class);
    }
    InviteEmailTemplate stored = templateRepository.findById(tenantEn.getId()).orElseThrow();
    assertThat(stored.getKind()).isEqualTo(TENANT_INVITE);
    assertThat(stored.getLanguage()).isEqualTo("en");
    assertThat(stored.getActive()).isTrue();
    assertThat(stored.getSubject()).isEqualTo("New subject");
  }

  @Test
  void createTemplate_Should_NeverMarkANewTemplateAsSystemDefault() {
    actAsPlatformAdmin();

    assertThat(service.createTemplate(command("Platform")).getSystemDefault()).isFalse();
  }

  // --- isolation: own Träger + platform, never another Träger ------------------------------

  @Test
  void nonPlatformAdmins_Should_SeeExactlyThePlatformsAndTheirOwnTraegersTemplates()
      throws Exception {
    runSystemDefaultsSeed();
    actAsPlatformAdmin();
    InviteEmailTemplate platform = service.createTemplate(command("Platform text"));
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate aOwn = service.createTemplate(command("A's own"));
    actAsTenantAdmin(TRAEGER_B);
    InviteEmailTemplate bOwn = service.createTemplate(command("B's own"));
    // Counsellor invites: the one kind every admin role works with.
    List<Long> platformIds =
        templateRepository.findAll().stream()
            .filter(t -> t.getTenantId() == null && t.getKind() == COUNSELLOR_INVITE)
            .map(InviteEmailTemplate::getId)
            .toList();
    assertThat(platformIds).contains(platform.getId()).hasSize(3);

    actAsTenantAdmin(TRAEGER_A);
    assertThat(idsOf(service.listTemplates(COUNSELLOR_INVITE)))
        .containsExactlyInAnyOrderElementsOf(plus(platformIds, aOwn.getId()));
    actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_A);
    assertThat(idsOf(service.listTemplates(COUNSELLOR_INVITE)))
        .containsExactlyInAnyOrderElementsOf(plus(platformIds, aOwn.getId()));
    actAsTenantAdmin(TRAEGER_B);
    assertThat(idsOf(service.listTemplates(COUNSELLOR_INVITE)))
        .containsExactlyInAnyOrderElementsOf(plus(platformIds, bOwn.getId()));
    actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_B);
    assertThat(idsOf(service.listTemplates(COUNSELLOR_INVITE)))
        .containsExactlyInAnyOrderElementsOf(plus(platformIds, bOwn.getId()));
  }

  @Test
  void traegerB_Should_NeitherSeeNorUseNorChangeTraegerAsTemplate_When_ItKnowsTheId() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate aCounsellor = service.createTemplate(command("A c", COUNSELLOR_INVITE));
    InviteEmailTemplate aTraeger = service.createTemplate(command("A t", TENANT_INVITE));
    InviteEmailTemplate aDpa =
        templateRepository.save(
            InviteEmailTemplate.builder()
                .tenantId(TRAEGER_A)
                .kind(DPA_FORWARD)
                .name("A d")
                .language("de")
                .subject("Subject")
                .body("Body")
                .active(true)
                .createDate(java.time.LocalDateTime.now())
                .build());

    for (Runnable traegerB :
        List.<Runnable>of(
            () -> actAsTenantAdmin(TRAEGER_B),
            () -> actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_B))) {
      traegerB.run();
      assertThat(idsOf(service.listTemplates(null)))
          .doesNotContain(aCounsellor.getId(), aTraeger.getId(), aDpa.getId());
      for (InviteEmailTemplate foreign : List.of(aCounsellor, aTraeger, aDpa)) {
        assertThat(idsOf(service.listTemplates(foreign.getKind()))).doesNotContain(foreign.getId());
        assertThatThrownBy(() -> service.requireUsableTemplate(foreign.getId()))
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(
                () ->
                    service.updateTemplate(foreign.getId(), command("Hijacked", foreign.getKind())))
            .isInstanceOf(ForbiddenException.class);
        assertThat(service.mayChange(foreign)).isFalse();
      }
    }
    assertThat(templateRepository.findById(aCounsellor.getId()).orElseThrow().getName())
        .isEqualTo("A c");
  }

  @Test
  void traegersTemplate_Should_BeVisibleAndUsableForEveryBeratungsstellenAdminOfThatTraeger() {
    // The owner of a template is the Träger only, not an agency: what the Träger admin
    // or one Beratungsstelle writes, every Beratungsstelle of that Träger sees.
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate byTraegerAdmin = service.createTemplate(command("By Träger admin"));
    actAsAgencyAdmin(OTHER_AGENCY_ADMIN_ID, TRAEGER_A);
    InviteEmailTemplate byOtherBst = service.createTemplate(command("By another BST"));

    actAsAgencyAdmin(AGENCY_ADMIN_ID, TRAEGER_A);
    assertThat(idsOf(service.listTemplates(null)))
        .contains(byTraegerAdmin.getId(), byOtherBst.getId());
    assertThat(service.requireUsableTemplate(byTraegerAdmin.getId()).getId())
        .isEqualTo(byTraegerAdmin.getId());
    assertThat(service.requireUsableTemplate(byOtherBst.getId()).getId())
        .isEqualTo(byOtherBst.getId());
    // Current behaviour, reported to the product owner: the same owner rule also lets a
    // Beratungsstellen admin change a template the Träger admin wrote.
    assertThat(service.mayChange(byTraegerAdmin)).isTrue();
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void runSystemDefaultsSeed() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource(SYSTEM_DEFAULTS_SEED));
    }
  }

  private List<InviteEmailTemplate> systemDefaults() {
    return templateRepository.findAll().stream()
        .filter(t -> Boolean.TRUE.equals(t.getSystemDefault()))
        .toList();
  }

  private InviteEmailTemplate systemDefault(InviteEmailTemplateKind kind, String language) {
    return systemDefaults().stream()
        .filter(t -> t.getKind() == kind && language.equals(t.getLanguage()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> defaultsIn(List<InviteEmailTemplate> templates) {
    return templates.stream()
        .filter(t -> Boolean.TRUE.equals(t.getSystemDefault()))
        .map(t -> t.getKind() + "/" + t.getLanguage())
        .toList();
  }

  private static List<Long> idsOf(List<InviteEmailTemplate> templates) {
    return templates.stream().map(InviteEmailTemplate::getId).toList();
  }

  private static List<Long> plus(List<Long> ids, Long id) {
    return java.util.stream.Stream.concat(ids.stream(), java.util.stream.Stream.of(id)).toList();
  }

  private static TemplateCommand command(String name) {
    return command(name, COUNSELLOR_INVITE);
  }

  private static TemplateCommand command(String name, InviteEmailTemplateKind kind) {
    return new TemplateCommand(kind, name, "de", "Subject", "Body", true);
  }

  private void actAsTenantAdmin(long tenantId) {
    actAs(
        "tenant-admin-" + tenantId,
        tenantId,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin(String adminId, long tenantId) {
    actAs(adminId, tenantId, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsPlatformAdmin() {
    actAs("platform-admin", 0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAs(String userId, Long tenantId, UserRole... roles) {
    caller.setUserId(userId);
    caller.setUsername(userId);
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
    caller.setGrantedAuthorities(Set.of());
  }
}
