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
   * The tenant of a pre-account onboarding forward does not exist yet (only its ID is reserved,
   * ORISO-Admin#722) — the mail then falls back to the generic wording instead of failing.
   */
  private String resolveTenantName(Long tenantId) {
    try {
      var tenant = tenantService.getRestrictedTenantData(tenantId);
      return tenant == null || isBlank(tenant.getName()) ? "Ihrer Organisation" : tenant.getName();
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
      return "Ihrer Organisation";
    }
  }

  /**
   * TenantService's forward endpoint (TS#191) answers with a RELATIVE {@code signUrl}
   * ("/dpa-sign/<token>"); the Admin frontend resolves it against the app origin for display, but
   * this mail path received it verbatim and rejected every forward with "signLink is invalid"
   * (found in E2E run e2e-20260818-2024). A relative link is resolved against the configured origin
   * — which by construction satisfies the same-origin gate below; absolute links keep the strict
   * parse + origin check.
   */
  private URI resolveSignLink(String value) {
    if (isBlank(value)) {
      throw new BadRequestException("signLink is required");
    }
    String trimmed = value.trim();
    if (trimmed.startsWith("/") && !trimmed.startsWith("//")) {
      try {
        return permittedAppOrigin.resolve(trimmed);
      } catch (IllegalArgumentException exception) {
        throw new BadRequestException("signLink is invalid");
      }
    }
    return parseUri(trimmed, "signLink");
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
