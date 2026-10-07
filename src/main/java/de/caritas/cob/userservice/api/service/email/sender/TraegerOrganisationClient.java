package de.caritas.cob.userservice.api.service.email.sender;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

/**
 * A Träger's own organisation data, entered at onboarding or under Träger → Allgemein: its full
 * legal name (else its display name), its postal address, and its contact e-mail and phone as one
 * contact line. What the Träger did not enter stays empty, and the platform owner's value applies.
 *
 * <p>The address is only in the admin view of a tenant ({@code GET /tenant/{id}}), which needs the
 * {@code tenant-admin} role; mails are also sent where no such user is logged in, so the read
 * authenticates as the technical user, like {@code OperatorDpaContentClient}. Every failure
 * degrades to "no Träger data", which leaves the platform owner's block in the footer.
 */
@Slf4j
@Component
public class TraegerOrganisationClient {

  static final Duration CACHE_TTL = Duration.ofMinutes(5);

  private final de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient
      contextClient;
  private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

  public TraegerOrganisationClient(
      @NonNull
          de.caritas.cob.userservice.api.service.notification.TenantSystemEmailClient
              contextClient) {
    this.contextClient = contextClient;
  }

  /**
   * @param tenantId the Träger; {@code null} and the platform tenant have no Träger data
   */
  public Optional<SenderOrganisation> fetch(Long tenantId) {
    if (tenantId == null || TenantContext.TECHNICAL_TENANT_ID.equals(tenantId)) {
      return Optional.empty();
    }
    Cached cached = cache.get(tenantId);
    if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
      return Optional.of(cached.organisation());
    }
    try {
      Map<String, Object> tenant = contextClient.readTenant(tenantId);
      SenderOrganisation organisation =
          tenant == null
              ? SenderOrganisation.NONE
              : new SenderOrganisation(
                  isBlank(text(tenant.get("legalName")))
                      ? text(tenant.get("name"))
                      : text(tenant.get("legalName")),
                  text(tenant.get("address")),
                  SenderOrganisation.contactLine(
                      text(tenant.get("contactEmail")), text(tenant.get("contactPhone"))));
      if (organisation.isEmpty()) {
        return Optional.empty();
      }
      cache.put(tenantId, new Cached(organisation, System.nanoTime() + CACHE_TTL.toNanos()));
      return Optional.of(organisation);
    } catch (HttpClientErrorException.NotFound reservedOnly) {
      // A tenant-admin invite goes out before its tenant exists.
      return Optional.empty();
    } catch (RuntimeException exception) {
      log.warn(
          "Could not read the organisation data of tenant {} — the mail footer uses the platform"
              + " owner's",
          tenantId,
          exception);
      return Optional.empty();
    }
  }

  private static String text(Object value) {
    return value instanceof String string ? string : null;
  }

  private record Cached(SenderOrganisation organisation, long expiresAtNanos) {}
}
