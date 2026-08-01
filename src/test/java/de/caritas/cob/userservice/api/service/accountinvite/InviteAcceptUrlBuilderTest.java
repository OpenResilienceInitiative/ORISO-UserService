package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Link-target contract (TEN-INV-U6, #890): tenant-admin invites must land on the PUBLIC ADMIN
 * onboarding route, counsellor (and other app-level) invites on the public App accept route.
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
  void buildAcceptUrl_Should_targetPublicAppRoute_ForCounsellor() {
    String url = builder.buildAcceptUrl(AccountInviteTargetRole.COUNSELLOR, "tok-2");

    assertThat(url).isEqualTo("https://app.example.org/account-invite/tok-2");
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

    assertThat(slashy.buildAcceptUrl(AccountInviteTargetRole.COUNSELLOR, "tok"))
        .isEqualTo("https://app.example.org/account-invite/tok");
    assertThat(slashy.buildAcceptUrl(AccountInviteTargetRole.TENANT_ADMIN, "tok"))
        .isEqualTo("https://admin.example.org/admin/tenant-onboarding/tok");
  }

  @Test
  void buildAcceptUrl_Should_fallBackToDefaultOrigin_When_ConfigurationBlank() {
    var blank = new InviteAcceptUrlBuilder("  ", null);

    assertThat(blank.buildAcceptUrl(AccountInviteTargetRole.COUNSELLOR, "tok"))
        .isEqualTo("https://app.oriso.org/account-invite/tok");
    assertThat(blank.buildAcceptUrl(AccountInviteTargetRole.TENANT_ADMIN, "tok"))
        .isEqualTo("https://app.oriso.org/admin/tenant-onboarding/tok");
  }
}
