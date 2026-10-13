package de.caritas.cob.userservice.api.picture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.service.accountinvite.onboarding.CounsellorOnboardingService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.Tenants;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The onboarding wizard is public, so its request runs in the tenant the route resolves to, while
 * the invite and its counsellor belong to their own Träger. The picture writes must reach that
 * counsellor through the tenant filter.
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ConsultantPictureStore.class, ConsultantPictureAccess.class})
class ConsultantPictureOnboardingTenantIT {

  private static final long REQUEST_TENANT = 1L;
  private static final long INVITE_TENANT = 79L;
  private static final String TOKEN = "raw-invite-token";

  @Autowired private ConsultantPictureStore store;
  @Autowired private JdbcTemplate jdbc;

  @MockitoBean private CounsellorOnboardingService onboarding;
  @MockitoBean private AuthenticatedUser caller;
  @MockitoBean private AdminUserFacade admins;
  @MockitoBean private ConsultantAgencyAdminService agencies;

  private String consultantId;

  @BeforeEach
  void seedCounsellorOfAnotherTraeger() {
    consultantId = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id,"
            + " is_team_consultant, is_absent, language_formal, create_date, update_date,"
            + " walk_through_enabled, notifications_enabled)"
            + " VALUES (?, ?, 'Synthetic', 'Picture', ?, ?, 0, 0, 1, NOW(), NOW(), 0, 0)",
        consultantId,
        consultantId,
        consultantId + "@example.invalid",
        INVITE_TENANT);
    when(onboarding.requireOnboardingPictureInvite(TOKEN))
        .thenReturn(
            AccountInvite.builder()
                .tenantId(INVITE_TENANT)
                .provisionedUserId(consultantId)
                .build());
    Tenants.actIn(REQUEST_TENANT);
  }

  @AfterEach
  void cleanUp() {
    TenantContext.clear();
    jdbc.update("DELETE FROM consultant_picture WHERE consultant_id = ?", consultantId);
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", consultantId);
  }

  @Test
  void replaceForOnboarding_Should_StoreThePicture_When_InviteBelongsToAnotherTenant() {
    store.replaceForOnboarding(TOKEN, new byte[] {1, 2, 3}, "image/png");

    assertThat(pictureCount()).isEqualTo(1);
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(REQUEST_TENANT);
  }

  @Test
  void writeInternalOnlyForOnboarding_Should_Publish_When_InviteBelongsToAnotherTenant() {
    store.replaceForOnboarding(TOKEN, new byte[] {1, 2, 3}, "image/png");

    store.writeInternalOnlyForOnboarding(TOKEN, false);

    assertThat(
            jdbc.queryForObject(
                "SELECT internal_only FROM consultant_picture WHERE consultant_id = ?",
                Boolean.class,
                consultantId))
        .isFalse();
    assertThat(TenantContext.getCurrentTenant()).isEqualTo(REQUEST_TENANT);
  }

  private int pictureCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM consultant_picture WHERE consultant_id = ?",
        Integer.class,
        consultantId);
  }
}
