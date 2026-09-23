package de.caritas.cob.userservice.api.service.email.sender;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * The platform owner's organisation: the "Betreiber" block the super admin maintains under Admin →
 * Globale Einstellungen → Dokument-Stammdaten (ORISO-Admin#735), which TenantService serves without
 * authentication at {@code /tenant/public/dpia}.
 *
 * <p>Every failure degrades to "nothing entered": a mail must not fail because its footer could not
 * be read. Only an answer is cached, so a freshly saved address shows in the next mail after at
 * most {@link #CACHE_TTL}.
 */
@Slf4j
@Component
public class PlatformOperatorOrganisationClient {

  static final String PATH = "/tenant/public/dpia";
  static final Duration CACHE_TTL = Duration.ofMinutes(5);
  private static final String CONTACT_SEPARATOR = " · ";

  private final RestTemplate restTemplate;
  private final String url;
  private final AtomicReference<Cached> cache = new AtomicReference<>();

  public PlatformOperatorOrganisationClient(
      @NonNull RestTemplate restTemplate,
      @Value("${tenant.service.api.url}") String tenantServiceApiUrl) {
    this.restTemplate = restTemplate;
    this.url = tenantServiceApiUrl + PATH;
  }

  public Optional<SenderOrganisation> fetch() {
    Cached cached = cache.get();
    if (cached != null && System.nanoTime() - cached.expiresAtNanos() < 0) {
      return cached.organisation();
    }
    Optional<SenderOrganisation> organisation;
    try {
      organisation = read();
    } catch (RuntimeException exception) {
      log.warn(
          "Could not read the platform operator's master data — mail footers omit it", exception);
      return Optional.empty();
    }
    cache.set(new Cached(organisation, System.nanoTime() + CACHE_TTL.toNanos()));
    return organisation;
  }

  private Optional<SenderOrganisation> read() {
    @SuppressWarnings("unchecked")
    Map<String, Object> response = restTemplate.getForObject(url, Map.class);
    Object operator = response == null ? null : response.get("operator");
    if (!(operator instanceof Map<?, ?> fields)) {
      return Optional.empty();
    }
    String legalName = text(fields, "legalName");
    SenderOrganisation organisation =
        new SenderOrganisation(
            isBlank(legalName) ? text(fields, "shortName") : legalName,
            text(fields, "address"),
            Stream.of(text(fields, "contactEmail"), text(fields, "contactPhone"))
                .filter(value -> !isBlank(value))
                .map(String::trim)
                .collect(Collectors.joining(CONTACT_SEPARATOR)));
    return organisation.isEmpty() ? Optional.empty() : Optional.of(organisation);
  }

  private static String text(Map<?, ?> fields, String key) {
    Object value = fields.get(key);
    return value == null ? null : value.toString();
  }

  private record Cached(Optional<SenderOrganisation> organisation, long expiresAtNanos) {}
}
