package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Link-target contract (TEN-INV-U6, #890; extended by #997): tenant-admin invites land on the
 * PUBLIC ADMIN onboarding route, counsellor invites on the PUBLIC ADMIN counsellor wizard, other
 * app-level invites on the public App accept route.
 */
class InviteAcceptUrlBuilderTest {

  private final InviteAcceptUrlBuilder builder =
      new InviteAcceptUrlBuilder("https://app.example.org", "https://admin.example.org");

  @Test
  void buildAcceptUrl_Should_targetPublicAdminOnboardingRoute_ForTenantAdmin() {
    String url = builder.buildAcceptUrl(AccountInviteTargetRole.TENANT_ADMIN, "tok-1");

    assertThat(url).isEqualTo("https://admin.example.org/admin/tenant-onboarding/tok-1");
  }

  @Test
  void buildAcceptUrl_Should_targetPublicAdminWizardRoute_ForCounsellor() {
    String url = builder.buildAcceptUrl(AccountInviteTargetRole.COUNSELLOR, "tok-2");

    assertThat(url).isEqualTo("https://admin.example.org/admin/counsellor-onboarding/tok-2");
  }

  @Test
  void buildAcceptUrl_Should_targetPublicAppRoute_ForOtherRoles() {
    assertThat(builder.buildAcceptUrl(AccountInviteTargetRole.AGENCY_ADMIN, "t"))
        .isEqualTo("https://app.example.org/account-invite/t");
    assertThat(builder.buildAcceptUrl(AccountInviteTargetRole.PLATFORM_ADMIN, "t"))
        .isEqualTo("https://app.example.org/account-invite/t");
    assertThat(builder.buildAcceptUrl(AccountInviteTargetRole.ADVICE_SEEKER, "t"))
        .isEqualTo("https://app.example.org/account-invite/t");
  }

  @Test
  void buildAcceptUrl_Should_stripTrailingSlashesFromConfiguredBaseUrls() {
    var slashy =
        new InviteAcceptUrlBuilder("https://app.example.org///", "https://admin.example.org/");

    assertThat(slashy.buildAcceptUrl(AccountInviteTargetRole.ADVICE_SEEKER, "tok"))
        .isEqualTo("https://app.example.org/account-invite/tok");
    assertThat(slashy.buildAcceptUrl(AccountInviteTargetRole.TENANT_ADMIN, "tok"))
        .isEqualTo("https://admin.example.org/admin/tenant-onboarding/tok");
    assertThat(slashy.buildAcceptUrl(AccountInviteTargetRole.COUNSELLOR, "tok"))
        .isEqualTo("https://admin.example.org/admin/counsellor-onboarding/tok");
  }

  /**
   * Regression for the dev invite mails that linked to production (2026-09-16), and for the
   * localhost fallback that replaced it: a blank invite origin must stop startup and name the
   * variable, never guess a host (ORISO-Helm#368).
   */
  @Test
  void constructor_Should_failNamingTheVariable_When_AppOriginBlank() {
    assertThatThrownBy(() -> new InviteAcceptUrlBuilder("  ", "https://admin.example.org"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACCOUNT_INVITE_APP_FRONTEND_BASE_URL");
  }

  @Test
  void constructor_Should_failNamingTheVariable_When_AdminOriginMissing() {
    assertThatThrownBy(() -> new InviteAcceptUrlBuilder("https://app.example.org", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ACCOUNT_INVITE_ADMIN_FRONTEND_BASE_URL");
  }
}
