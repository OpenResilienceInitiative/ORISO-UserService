package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cross-Träger isolation of the invite e-mail templates, against a real database.
 *
 * <p>Opening template creation to every admin who may send invites (ORISO-Admin#1026 Q30/Q31) is
 * only safe once a template has an owner. Without one, a Beratungsstellen admin of Träger A writes
 * a row that Träger B sees in its list and sends to its own people — a cross-tenant write through a
 * feature nobody reads as dangerous.
 *
 * <p>So a template now carries a {@code tenant_id}:
 *
 * <ul>
 *   <li>{@code null} = a <b>platform template</b>, written by the platform admin, visible to
 *       everyone and changeable only by the platform admin. Every row that existed before this
 *       change is one.
 *   <li>a tenant id = that Träger's own template: only they see it, use it and change it.
 * </ul>
 *
 * <p>The caller is a real {@link AuthenticatedUser}, so the role helpers the scoping relies on
 * ({@code isPlatformAdmin}, {@code hasRestrictedAgencyPriviliges}) run unchanged. The restricted
 * agency admin is the seeded admin {@value #AGENCY_ADMIN_ID}, who belongs to tenant 1.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  InviteEmailTemplateService.class,
  AccountInviteAccessPolicy.class,
  InviteEmailTemplateTenantScopeIT.CallerConfig.class
})
class InviteEmailTemplateTenantScopeIT {

  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";
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

  @MockitoBean private AgencyService agencyService;

  @AfterEach
  void cleanUp() {
    templateRepository.deleteAll();
  }

  // --- who owns a new template ------------------------------------------------------------

  @Test
  void createTemplate_Should_StampTheCallersTenant_When_TraegerAdminCreates() {
    actAsTenantAdmin(TRAEGER_A);

    InviteEmailTemplate created = service.createTemplate(command("A's own"));

    assertThat(created.getTenantId()).isEqualTo(TRAEGER_A);
  }

  @Test
  void createTemplate_Should_StampTheCallersTenant_When_BeratungsstellenAdminCreates() {
    actAsAgencyAdmin(TRAEGER_A);

    InviteEmailTemplate created = service.createTemplate(command("BST's own"));

    assertThat(created.getTenantId()).isEqualTo(TRAEGER_A);
  }

  @Test
  void createTemplate_Should_LeaveTheOwnerEmpty_When_PlatformAdminCreates() {
    actAsPlatformAdmin();

    InviteEmailTemplate created = service.createTemplate(command("Platform text"));

    assertThat(created.getTenantId()).isNull();
  }

  // --- what a Träger sees -----------------------------------------------------------------

  @Test
  void listTemplates_Should_HideAnotherTraegersTemplate() {
    actAsTenantAdmin(TRAEGER_B);
    service.createTemplate(command("B's own"));
    actAsPlatformAdmin();
    service.createTemplate(command("Platform text"));
    actAsTenantAdmin(TRAEGER_A);
    service.createTemplate(command("A's own"));

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getName)
        .containsExactlyInAnyOrder("A's own", "Platform text")
        .doesNotContain("B's own");
  }

  @Test
  void listTemplates_Should_HideAnotherTraegersTemplate_When_FilteredByKind() {
    actAsTenantAdmin(TRAEGER_B);
    service.createTemplate(command("B's own"));
    actAsPlatformAdmin();
    service.createTemplate(command("Platform text"));
    actAsTenantAdmin(TRAEGER_A);
    service.createTemplate(command("A's own"));

    assertThat(service.listTemplates(InviteEmailTemplateKind.COUNSELLOR_INVITE))
        .extracting(InviteEmailTemplate::getName)
        .containsExactlyInAnyOrder("A's own", "Platform text");
  }

  @Test
  void listTemplates_Should_ShowEverything_When_PlatformAdminReads() {
    actAsTenantAdmin(TRAEGER_B);
    service.createTemplate(command("B's own"));
    actAsPlatformAdmin();

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getName)
        .contains("B's own");
  }

  // --- who may change what ----------------------------------------------------------------

  @Test
  void updateTemplate_Should_Succeed_When_TraegerChangesItsOwnTemplate() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate own = service.createTemplate(command("A's own"));

    InviteEmailTemplate updated = service.updateTemplate(own.getId(), command("A's own, edited"));

    assertThat(updated.getName()).isEqualTo("A's own, edited");
    assertThat(updated.getTenantId()).isEqualTo(TRAEGER_A);
  }

  @Test
  void updateTemplate_Should_Refuse_When_TraegerChangesAnotherTraegersTemplate() {
    actAsTenantAdmin(TRAEGER_B);
    InviteEmailTemplate foreign = service.createTemplate(command("B's own"));
    actAsTenantAdmin(TRAEGER_A);

    assertThatThrownBy(() -> service.updateTemplate(foreign.getId(), command("Hijacked")))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.findById(foreign.getId()).orElseThrow().getName())
        .isEqualTo("B's own");
  }

  @Test
  void updateTemplate_Should_Refuse_When_TraegerChangesAPlatformTemplate() {
    actAsPlatformAdmin();
    InviteEmailTemplate platformTemplate = service.createTemplate(command("Platform text"));
    actAsTenantAdmin(TRAEGER_A);

    assertThatThrownBy(() -> service.updateTemplate(platformTemplate.getId(), command("Hijacked")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void updateTemplate_Should_Succeed_When_PlatformAdminChangesATraegersTemplate() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate own = service.createTemplate(command("A's own"));
    actAsPlatformAdmin();

    assertThat(service.updateTemplate(own.getId(), command("Corrected by the operator")).getName())
        .isEqualTo("Corrected by the operator");
  }

  // --- who may USE what -------------------------------------------------------------------

  @Test
  void requireUsableTemplate_Should_Refuse_When_TraegerSendsWithAnotherTraegersTemplate() {
    // Sending is where a hidden template would still leak: the id travels in the
    // request body, so hiding it from the list is not enough.
    actAsTenantAdmin(TRAEGER_B);
    InviteEmailTemplate foreign = service.createTemplate(command("B's own"));
    actAsTenantAdmin(TRAEGER_A);

    assertThatThrownBy(() -> service.requireUsableTemplate(foreign.getId()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void requireUsableTemplate_Should_Succeed_When_TraegerSendsWithAPlatformTemplate() {
    actAsPlatformAdmin();
    InviteEmailTemplate platformTemplate = service.createTemplate(command("Platform text"));
    actAsTenantAdmin(TRAEGER_A);

    assertThat(service.requireUsableTemplate(platformTemplate.getId()).getName())
        .isEqualTo("Platform text");
  }

  @Test
  void requireUsableTemplate_Should_Succeed_When_TraegerSendsWithItsOwnTemplate() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate own = service.createTemplate(command("A's own"));

    assertThat(service.requireUsableTemplate(own.getId()).getId()).isEqualTo(own.getId());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static TemplateCommand command(String name) {
    return new TemplateCommand(
        InviteEmailTemplateKind.COUNSELLOR_INVITE, name, "de", "Subject", "Body", true);
  }

  private void actAsTenantAdmin(long tenantId) {
    actAs(
        "tenant-admin-" + tenantId,
        tenantId,
        UserRole.TENANT_ADMIN,
        UserRole.AGENCY_ADMIN,
        UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin(long tenantId) {
    actAs(AGENCY_ADMIN_ID, tenantId, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
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
