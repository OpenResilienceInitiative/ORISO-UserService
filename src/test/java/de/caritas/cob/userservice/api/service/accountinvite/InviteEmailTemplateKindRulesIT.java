package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.COUNSELLOR_INVITE;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.DPA_FORWARD;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.TENANT_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.InviteEmailTemplate;
import de.caritas.cob.userservice.api.port.out.InviteEmailTemplateRepository;
import de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateService.TemplateCommand;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
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
 * The kind rules for invite e-mail templates, against a real database, for every admin role:
 *
 * <ul>
 *   <li>Beratungsstellen admin: counsellor invites only.
 *   <li>Träger admin: counsellor and Träger invites, never "forward contract" (DPA_FORWARD).
 *   <li>Platform admin: every kind.
 * </ul>
 *
 * <p>The built-in system defaults are covered in {@link InviteEmailTemplateSystemDefaultsIT}.
 *
 * <p>The cross-Träger rule (own templates plus the platform's) is pinned in {@link
 * InviteEmailTemplateTenantScopeIT}; here it is re-checked for the kinds a Träger may use.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  InviteEmailTemplateService.class,
  AccountInviteAccessPolicy.class,
  AdminScope.class,
  InviteEmailTemplateKindRulesIT.CallerConfig.class
})
class InviteEmailTemplateKindRulesIT {

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

  private InviteEmailTemplate platformCounsellor;
  private InviteEmailTemplate platformTenant;
  private InviteEmailTemplate platformDpa;

  @BeforeEach
  void seedOnePlatformTemplatePerKind() {
    platformCounsellor = stored(null, COUNSELLOR_INVITE, "Platform counsellor invite");
    platformTenant = stored(null, TENANT_INVITE, "Platform Träger invite");
    platformDpa = stored(null, DPA_FORWARD, "Platform DPA forward");
  }

  @AfterEach
  void cleanUp() {
    templateRepository.deleteAll();
  }

  // --- Beratungsstellen admin -------------------------------------------------------------

  @Test
  void agencyAdmin_Should_CreateCounsellorInvite_But_NotTraegerInviteOrDpaForward() {
    actAsAgencyAdmin(TRAEGER_A);
    long before = templateRepository.count();

    assertThat(service.createTemplate(command("BST counsellor", COUNSELLOR_INVITE)).getTenantId())
        .isEqualTo(TRAEGER_A);
    assertThatThrownBy(() -> service.createTemplate(command("BST Träger", TENANT_INVITE)))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.createTemplate(command("BST DPA", DPA_FORWARD)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.count()).isEqualTo(before + 1);
  }

  @Test
  void agencyAdmin_Should_ListCounsellorInvitesOnly() {
    actAsAgencyAdmin(TRAEGER_A);

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getKind)
        .containsOnly(COUNSELLOR_INVITE);
    assertThat(service.listTemplates(COUNSELLOR_INVITE))
        .extracting(InviteEmailTemplate::getId)
        .contains(platformCounsellor.getId());
    // Same style as the invite list: a kind out of reach lists nothing, it is not a 403.
    assertThat(service.listTemplates(TENANT_INVITE)).isEmpty();
    assertThat(service.listTemplates(DPA_FORWARD)).isEmpty();
  }

  @Test
  void agencyAdmin_Should_NotUseTraegerInviteOrDpaForward_When_TheyKnowTheId() {
    actAsAgencyAdmin(TRAEGER_A);

    assertThat(service.requireUsableTemplate(platformCounsellor.getId()).getId())
        .isEqualTo(platformCounsellor.getId());
    assertThatThrownBy(() -> service.requireUsableTemplate(platformTenant.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.requireUsableTemplate(platformDpa.getId()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void agencyAdmin_Should_NotTurnOwnTemplateIntoTraegerInvite() {
    actAsAgencyAdmin(TRAEGER_A);
    InviteEmailTemplate own = service.createTemplate(command("BST own", COUNSELLOR_INVITE));

    assertThatThrownBy(() -> service.updateTemplate(own.getId(), command("Now", TENANT_INVITE)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.findById(own.getId()).orElseThrow().getKind())
        .isEqualTo(COUNSELLOR_INVITE);
    assertThat(service.updateTemplate(own.getId(), command("Edited", COUNSELLOR_INVITE)).getName())
        .isEqualTo("Edited");
  }

  // --- Träger admin -----------------------------------------------------------------------

  @Test
  void traegerAdmin_Should_CreateCounsellorAndTraegerInvites_But_NotDpaForward() {
    actAsTenantAdmin(TRAEGER_A);
    long before = templateRepository.count();

    service.createTemplate(command("A counsellor", COUNSELLOR_INVITE));
    service.createTemplate(command("A Träger", TENANT_INVITE));
    assertThatThrownBy(() -> service.createTemplate(command("A DPA", DPA_FORWARD)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.count()).isEqualTo(before + 2);
  }

  @Test
  void traegerAdmin_Should_ListCounsellorAndTraegerInvites_But_NeverDpaForward() {
    actAsTenantAdmin(TRAEGER_A);

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getKind)
        .containsOnly(COUNSELLOR_INVITE, TENANT_INVITE);
    assertThat(service.listTemplates(DPA_FORWARD)).isEmpty();
  }

  @Test
  void traegerAdmin_Should_NotUseOrChangeDpaForward_Even_When_ItIsTheirOwnOldRow() {
    // A DPA_FORWARD row a Träger saved before the kind rule existed.
    InviteEmailTemplate oldOwnDpa = stored(TRAEGER_A, DPA_FORWARD, "A's old DPA text");
    actAsTenantAdmin(TRAEGER_A);

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getId)
        .doesNotContain(oldOwnDpa.getId(), platformDpa.getId());
    assertThatThrownBy(() -> service.requireUsableTemplate(oldOwnDpa.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.requireUsableTemplate(platformDpa.getId()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> service.updateTemplate(oldOwnDpa.getId(), command("Edited", COUNSELLOR_INVITE)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(service.mayChange(oldOwnDpa)).isFalse();
  }

  @Test
  void traegerAdmin_Should_NotTurnOwnTemplateIntoDpaForward() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate own = service.createTemplate(command("A own", TENANT_INVITE));

    assertThatThrownBy(() -> service.updateTemplate(own.getId(), command("Now", DPA_FORWARD)))
        .isInstanceOf(ForbiddenException.class);
    assertThat(templateRepository.findById(own.getId()).orElseThrow().getKind())
        .isEqualTo(TENANT_INVITE);
  }

  @Test
  void traegerB_Should_NeverSeeOrUseTraegerAsTemplates_OfAnyAllowedKind() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate aCounsellor = service.createTemplate(command("A c", COUNSELLOR_INVITE));
    InviteEmailTemplate aTraeger = service.createTemplate(command("A t", TENANT_INVITE));
    actAsTenantAdmin(TRAEGER_B);

    assertThat(service.listTemplates(null))
        .extracting(InviteEmailTemplate::getId)
        .doesNotContain(aCounsellor.getId(), aTraeger.getId());
    assertThat(service.listTemplates(TENANT_INVITE))
        .extracting(InviteEmailTemplate::getId)
        .containsExactly(platformTenant.getId());
    for (InviteEmailTemplate foreign : List.of(aCounsellor, aTraeger)) {
      assertThatThrownBy(() -> service.requireUsableTemplate(foreign.getId()))
          .isInstanceOf(ForbiddenException.class);
      assertThatThrownBy(
              () -> service.updateTemplate(foreign.getId(), command("Hijacked", foreign.getKind())))
          .isInstanceOf(ForbiddenException.class);
      assertThat(service.mayChange(foreign)).isFalse();
    }
  }

  @Test
  void agencyAdminOfTraegerB_Should_NeverSeeOrUseTraegerAsCounsellorTemplate() {
    actAsTenantAdmin(TRAEGER_A);
    InviteEmailTemplate aCounsellor = service.createTemplate(command("A c", COUNSELLOR_INVITE));
    actAsAgencyAdmin(TRAEGER_B);

    assertThat(service.listTemplates(COUNSELLOR_INVITE))
        .extracting(InviteEmailTemplate::getId)
        .doesNotContain(aCounsellor.getId());
    assertThatThrownBy(() -> service.requireUsableTemplate(aCounsellor.getId()))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- Platform admin ---------------------------------------------------------------------

  @Test
  void platformAdmin_Should_CreateListAndUseEveryKind() {
    actAsPlatformAdmin();

    for (InviteEmailTemplateKind kind : InviteEmailTemplateKind.values()) {
      InviteEmailTemplate created = service.createTemplate(command("Platform " + kind, kind));
      assertThat(created.getTenantId()).isNull();
      assertThat(service.requireUsableTemplate(created.getId()).getKind()).isEqualTo(kind);
      assertThat(service.mayChange(created)).isTrue();
    }
    assertThat(service.listTemplates(DPA_FORWARD))
        .extracting(InviteEmailTemplate::getId)
        .contains(platformDpa.getId());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private InviteEmailTemplate stored(Long tenantId, InviteEmailTemplateKind kind, String name) {
    LocalDateTime now = LocalDateTime.now();
    return templateRepository.save(
        InviteEmailTemplate.builder()
            .tenantId(tenantId)
            .kind(kind)
            .name(name)
            .language("de")
            .subject("Subject")
            .body("Body")
            .active(true)
            .createDate(now)
            .updateDate(now)
            .build());
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
