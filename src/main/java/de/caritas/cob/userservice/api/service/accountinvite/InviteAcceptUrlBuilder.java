package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the accept URL embedded in invite mails. The target layer is decided server-side from the
 * invite's role and configuration — never by the calling client (TEN-INV-U6, #890):
 *
 * <ul>
 *   <li>{@code TENANT_ADMIN} → the PUBLIC ADMIN onboarding route. The tenant is an organisation,
 *       not an app user — the login belongs to the tenant admin and the flow completes on the Admin
 *       panel's public page ({@code /admin/tenant-onboarding/{token}}, Admin U8, #571).
 *   <li>all other roles (counsellors, advice seekers, …) → the public App accept route ({@code
 *       /account-invite/{token}}).
 * </ul>
 */
@Component
public class InviteAcceptUrlBuilder {

  /** Public Admin route serving the tenant-admin onboarding flow (ORISO-Admin U8, #571). */
  static final String ADMIN_TENANT_ONBOARDING_PATH = "/admin/tenant-onboarding";

  /** Public App route accepting non-admin invites. */
  static final String APP_ACCEPT_PATH = "/account-invite";

  private final String appFrontendBaseUrl;
  private final String adminFrontendBaseUrl;

  public InviteAcceptUrlBuilder(
      @Value("${account.invite.app.frontend.base-url:https://app.oriso.org}")
          String appFrontendBaseUrl,
      @Value("${account.invite.admin.frontend.base-url:https://app.oriso.org}")
          String adminFrontendBaseUrl) {
    this.appFrontendBaseUrl = normalize(appFrontendBaseUrl, "https://app.oriso.org");
    this.adminFrontendBaseUrl = normalize(adminFrontendBaseUrl, "https://app.oriso.org");
  }

  /** Builds the absolute accept URL for the given role's public frontend route. */
  public String buildAcceptUrl(AccountInviteTargetRole targetRole, String rawToken) {
    if (targetRole == AccountInviteTargetRole.TENANT_ADMIN) {
      return adminFrontendBaseUrl + ADMIN_TENANT_ONBOARDING_PATH + "/" + rawToken;
    }
    return appFrontendBaseUrl + APP_ACCEPT_PATH + "/" + rawToken;
  }

  private static String normalize(String baseUrl, String fallback) {
    String base = isBlank(baseUrl) ? fallback : baseUrl.trim();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }
}
