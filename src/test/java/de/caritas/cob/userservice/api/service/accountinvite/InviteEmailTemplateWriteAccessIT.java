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
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
 * Invite e-mail templates are global: one row is used by every Träger.
 *
 * <p>Everyone who may send invites may also <b>create</b> a template — Träger admins and
 * Beratungsstellen admins alike (owner decision 2026-09-23, confirmed and widened by Frank
 * 2026-09-24, ORISO-Admin#1026 Q30/Q31). <b>Changing a stored one</b> stays with the platform admin
 * (tenant 0), because that row is the mail every other Träger sends. Reading and using templates
 * was never restricted.
 *
 * <p>The caller is a real {@link AuthenticatedUser}, so the role helpers the check relies on run
 * unchanged; the restricted agency admin is the seeded admin {@value #AGENCY_ADMIN_ID}.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  InviteEmailTemplateService.class,
  AccountInviteAccessPolicy.class,
  InviteEmailTemplateWriteAccessIT.CallerConfig.class
})
class InviteEmailTemplateWriteAccessIT {

  private static final String AGENCY_ADMIN_ID = "d42c2e5e-143c-4db1-a90f-7cccf82fbb15";

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

  private InviteEmailTemplate sharedTemplate;

  @BeforeEach
  void seedSharedTemplate() {
    LocalDateTime now = LocalDateTime.now();
    sharedTemplate =
        templateRepository.save(
            InviteEmailTemplate.builder()
                .kind(InviteEmailTemplateKind.COUNSELLOR_INVITE)
                .name("Shared counsellor invite")
                .subject("Welcome")
                .body("Original body used by every Träger")
                .active(true)
                .createdByUserId("platform-admin")
                .createDate(now)
                .updateDate(now)
                .build());
  }

  @AfterEach
  void cleanUp() {
    templateRepository.deleteAll();
  }

  // --- Träger admin (tenant 1) -------------------------------------------------------------

  @Test
  void createTemplate_Should_Succeed_When_TenantAdminCreates() {
    actAsTenantAdmin();
    long before = templateRepository.count();

    InviteEmailTemplate created = service.createTemplate(command("Träger-eigene Vorlage"));

    assertThat(created.getId()).isNotNull();
    assertThat(created.getCreatedByUserId()).isEqualTo("tenant-admin-1");
    assertThat(templateRepository.count()).isEqualTo(before + 1);
  }

  @Test
  void updateTemplate_Should_Refuse_And_KeepTemplate_When_TenantAdminUpdatesSharedTemplate() {
    actAsTenantAdmin();

    assertThatThrownBy(() -> service.updateTemplate(sharedTemplate.getId(), command("Hijacked")))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.findById(sharedTemplate.getId()).orElseThrow().getBody())
        .isEqualTo("Original body used by every Träger");
  }

  @Test
  void listTemplates_Should_StillWork_When_TenantAdminReads() {
    actAsTenantAdmin();

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getId)
        .contains(sharedTemplate.getId());
  }

  // --- Beratungsstellen admin (restricted agency admin) ---------------------------------

  @Test
  void createTemplate_Should_Succeed_When_AgencyAdminCreates() {
    actAsAgencyAdmin();
    long before = templateRepository.count();

    InviteEmailTemplate created = service.createTemplate(command("BST-eigene Vorlage"));

    assertThat(created.getId()).isNotNull();
    assertThat(templateRepository.count()).isEqualTo(before + 1);
  }

  @Test
  void updateTemplate_Should_Refuse_When_AgencyAdminUpdates() {
    actAsAgencyAdmin();

    assertThatThrownBy(() -> service.updateTemplate(sharedTemplate.getId(), command("Hijacked")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void listTemplates_Should_StillWork_When_AgencyAdminReads() {
    actAsAgencyAdmin();

    assertThat(service.listTemplates(InviteEmailTemplateKind.COUNSELLOR_INVITE))
        .extracting(InviteEmailTemplate::getId)
        .contains(sharedTemplate.getId());
  }

  // --- Platform admin (tenant 0) -----------------------------------------------------------

  @Test
  void updateTemplate_Should_Refuse_When_AgencyAdminEditsEvenItsOwnCreation() {
    // The honest consequence of a template model without an owner: a template is
    // shared the moment it is stored, so not even its author may change it back.
    // This is what per-Träger templates (#1026 "Later") would lift.
    actAsAgencyAdmin();
    InviteEmailTemplate own = service.createTemplate(command("BST-eigene Vorlage"));

    assertThatThrownBy(() -> service.updateTemplate(own.getId(), command("Edited")))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void createAndUpdateTemplate_Should_Succeed_When_PlatformAdmin() {
    actAsPlatformAdmin();

    InviteEmailTemplate created = service.createTemplate(command("New platform template"));
    InviteEmailTemplate updated = service.updateTemplate(sharedTemplate.getId(), command("Edited"));

    assertThat(created.getId()).isNotNull();
    assertThat(updated.getName()).isEqualTo("Edited");
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static TemplateCommand command(String name) {
    return new TemplateCommand(
        InviteEmailTemplateKind.COUNSELLOR_INVITE, name, "de", "Subject", "Body", true);
  }

  private void actAsTenantAdmin() {
    actAs("tenant-admin-1", 1L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
  }

  private void actAsAgencyAdmin() {
    actAs(AGENCY_ADMIN_ID, 1L, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);
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
