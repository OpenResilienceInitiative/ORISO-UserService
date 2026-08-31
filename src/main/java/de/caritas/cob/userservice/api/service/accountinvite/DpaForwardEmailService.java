package de.caritas.cob.userservice.api.service.accountinvite;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.service.notification.DpaSigningEmailDispatchService;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DpaForwardEmailService {

  /** See {@link #resolveTenantName(Long)} for why this fallback is German-only. */
  private static final String GENERIC_ORGANISATION_NAME = "Ihrer Organisation";

  private final TenantService tenantService;
  private final DpaSigningEmailDispatchService dpaSigningEmailDispatchService;
  private final URI permittedAppOrigin;

  public DpaForwardEmailService(
      @NonNull TenantService tenantService,
      @NonNull DpaSigningEmailDispatchService dpaSigningEmailDispatchService,
      @Value("${dpa.sign.frontend.base-url:https://app.oriso.org}") String appBaseUrl) {
    this.tenantService = tenantService;
    this.dpaSigningEmailDispatchService = dpaSigningEmailDispatchService;
    this.permittedAppOrigin = parseUri(appBaseUrl, "appBaseUrl");
  }

  public void sendSigningLink(DpaForwardEmailCommand command) {
    if (command == null
        || command.tenantId() == null
        || isBlank(command.recipientEmail())
        || command.expiresAt() == null) {
      throw new BadRequestException("tenantId, recipientEmail and expiresAt are required");
    }

    URI signLink = resolveSignLink(command.signLink());
    if (!hasSameOrigin(signLink, permittedAppOrigin)
        || signLink.getPath() == null
        || !signLink.getPath().startsWith("/dpa-sign/")) {
      throw new BadRequestException("signLink must use the configured ORISO App origin");
    }

    var tenantName = resolveTenantName(command.tenantId());
    dpaSigningEmailDispatchService.send(
        command.recipientEmail().trim(), tenantName, signLink.toString(), command.expiresAt());
  }

  /**
   * The TenantService builds the sign link as {@code app.base.url + "/dpa-sign/" + token} and that
   * base URL is optional there — when it is unset (as on pre-dev) the link arrives PATH-ONLY, e.g.
   * {@code /dpa-sign/<token>}. Browser clients resolve such a link against their own origin, but a
   * mail must carry an absolute URL, so resolve it against the configured ORISO App origin instead
   * of rejecting it. Anything that already carries a scheme/host stays untouched and still has to
   * pass the same-origin guard below, so this never widens the set of accepted origins.
   */
  private URI resolveSignLink(String value) {
    if (isBlank(value)) {
      throw new BadRequestException("signLink is required");
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("/")) {
      try {
        return permittedAppOrigin.resolve(URI.create(trimmed));
      } catch (IllegalArgumentException exception) {
        throw new BadRequestException("signLink is invalid");
      }
    }
    return parseUri(trimmed, "signLink");
  }

  /**
   * The tenant of a pre-account onboarding forward does not exist yet (only its ID is reserved,
   * ORISO-Admin#722) — the mail then falls back to the generic wording instead of failing.
   *
   * <p>The fallback is deliberately German-only, like the DPA_FORWARD mail it lands in: the {@link
   * DpaSigningEmailDispatchService} contract carries no language at all and the downstream template
   * is maintained in German only — the DPA is a German-language contract between the platform
   * operator and a Träger. If that dispatch contract ever grows a language dimension, this fallback
   * must follow it.
   */
  private String resolveTenantName(Long tenantId) {
    try {
      var tenant = tenantService.getRestrictedTenantData(tenantId);
      return tenant == null || isBlank(tenant.getName())
          ? GENERIC_ORGANISATION_NAME
          : tenant.getName();
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
      return GENERIC_ORGANISATION_NAME;
    }
  }

  private static URI parseUri(String value, String field) {
    if (isBlank(value)) {
      throw new BadRequestException(field + " is required");
    }
    try {
      URI uri = URI.create(value.trim());
      if (isBlank(uri.getScheme()) || isBlank(uri.getHost())) {
        throw new IllegalArgumentException("absolute URI required");
      }
      return uri;
    } catch (IllegalArgumentException exception) {
      throw new BadRequestException(field + " is invalid");
    }
  }

  private static boolean hasSameOrigin(URI left, URI right) {
    return left.getScheme().equalsIgnoreCase(right.getScheme())
        && left.getHost().equalsIgnoreCase(right.getHost())
        && effectivePort(left) == effectivePort(right)
        && Objects.equals(left.getUserInfo(), right.getUserInfo());
  }

  private static int effectivePort(URI uri) {
    if (uri.getPort() >= 0) {
      return uri.getPort();
    }
    return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  public record DpaForwardEmailCommand(
      Long tenantId, String recipientEmail, String signLink, LocalDateTime expiresAt) {}
}
