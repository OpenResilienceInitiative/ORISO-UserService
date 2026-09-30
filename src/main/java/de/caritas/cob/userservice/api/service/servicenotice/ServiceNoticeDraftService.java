package de.caritas.cob.userservice.api.service.servicenotice;

import de.caritas.cob.userservice.api.model.ServiceNoticeCampaign;
import de.caritas.cob.userservice.api.port.out.ServiceNoticeCampaignRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import de.caritas.cob.userservice.api.service.email.TenantEmailBrandValues;
import de.caritas.cob.userservice.api.service.email.layout.EmailBrandingResolver;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Stores only planned-notice drafts and previews the installed design; it cannot dispatch mail. */
@Service
@RequiredArgsConstructor
public class ServiceNoticeDraftService {

  private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,79}");
  private static final Pattern FQDN =
      Pattern.compile(
          "(?i)(?=.{4,253}\\.?$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
              + "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?\\.?");
  private static final Pattern UNRESOLVED =
      Pattern.compile("\\{\\{[a-zA-Z0-9.]+}}", Pattern.MULTILINE);
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

  private final ServiceNoticeCampaignRepository campaigns;
  private final OrisoEmailRenderer renderer;
  private final EmailBrandingResolver branding;
  private final TenantEmailBrandValues brandValues;

  public record DraftInput(
      LocalDate maintenanceDate,
      LocalTime maintenanceStart,
      LocalTime maintenanceEnd,
      String statusUrl) {}

  public record DraftView(
      String campaignKey,
      String status,
      LocalDate maintenanceDate,
      LocalTime maintenanceStart,
      LocalTime maintenanceEnd,
      String statusUrl) {
    static DraftView from(ServiceNoticeCampaign draft) {
      return new DraftView(
          draft.getCampaignKey(),
          draft.getStatus(),
          draft.getMaintenanceDate(),
          draft.getMaintenanceStart(),
          draft.getMaintenanceEnd(),
          draft.getStatusUrl());
    }
  }

  public record Preview(
      String campaignKey,
      String variant,
      String subject,
      String preheader,
      String html,
      String text) {}

  public static class DraftConflict extends RuntimeException {
    public DraftConflict() {
      super("The campaign key belongs to a different draft");
    }
  }

  public DraftView save(String key, DraftInput input, String operatorUserId) {
    validate(key, input, operatorUserId);
    String statusUrl = input.statusUrl().trim();
    var existing = campaigns.findByCampaignKey(key);
    if (existing.isPresent()) {
      return sameDraft(existing.get(), input, statusUrl, operatorUserId)
          ? DraftView.from(existing.get())
          : conflict();
    }

    var draft = new ServiceNoticeCampaign();
    draft.setCampaignKey(key);
    draft.setMaintenanceDate(input.maintenanceDate());
    draft.setMaintenanceStart(input.maintenanceStart());
    draft.setMaintenanceEnd(input.maintenanceEnd());
    draft.setStatusUrl(statusUrl);
    draft.setCreatedByUserId(operatorUserId);
    draft.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
    try {
      return DraftView.from(campaigns.saveAndFlush(draft));
    } catch (DataIntegrityViolationException duplicate) {
      // A second replica may have saved the same key since the first read. Only an identical
      // draft is an idempotent success; a different payload remains a conflict.
      var concurrent = campaigns.findByCampaignKey(key);
      if (concurrent.isEmpty()) {
        throw duplicate;
      }
      return sameDraft(concurrent.get(), input, statusUrl, operatorUserId)
          ? DraftView.from(concurrent.get())
          : conflict();
    }
  }

  public DraftView get(String key) {
    requireKey(key);
    return DraftView.from(requireDraft(key));
  }

  public Preview preview(String key, String variant) {
    requireKey(key);
    var draft = requireDraft(key);
    var requestedTone =
        Arrays.stream(OrisoEmailRenderer.Tone.values())
            .filter(candidate -> candidate.directory().equals(variant))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported email language variant"));
    var tone = renderer.deliveryTone(requestedTone);
    // A platform notice has no recipient tenant yet. The platform's actual configured branding
    // is resolved once and the same generated plain-dialect renderer used by delivered mail.
    var values = new LinkedHashMap<>(brandValues.values(branding.resolve(0L), null));
    values.put("maintenanceDate", draft.getMaintenanceDate().toString());
    values.put("maintenanceStart", draft.getMaintenanceStart().format(TIME));
    values.put("maintenanceEnd", draft.getMaintenanceEnd().format(TIME));
    values.put("statusUrl", draft.getStatusUrl());
    var rendered = renderer.render("systemhinweis", tone, values);
    var preheader =
        renderer
            .preheaderOf("systemhinweis", tone)
            .replace("{{maintenanceDate}}", values.get("maintenanceDate"))
            .replace("{{maintenanceStart}}", values.get("maintenanceStart"))
            .replace("{{maintenanceEnd}}", values.get("maintenanceEnd"));
    if (UNRESOLVED.matcher(rendered.subject()).find()
        || UNRESOLVED.matcher(preheader).find()
        || UNRESOLVED.matcher(rendered.html()).find()
        || UNRESOLVED.matcher(rendered.text()).find()) {
      throw new IllegalStateException("Service notice template is incomplete");
    }
    return new Preview(
        key, tone.directory(), rendered.subject(), preheader, rendered.html(), rendered.text());
  }

  private ServiceNoticeCampaign requireDraft(String key) {
    return campaigns
        .findByCampaignKey(key)
        .orElseThrow(() -> new NoSuchElementException("Service notice draft not found"));
  }

  private static DraftView conflict() {
    throw new DraftConflict();
  }

  private static boolean sameDraft(
      ServiceNoticeCampaign existing, DraftInput input, String url, String operatorUserId) {
    return existing.getStatus().equals("DRAFT")
        && existing.getCreatedByUserId().equals(operatorUserId)
        && existing.getMaintenanceDate().equals(input.maintenanceDate())
        && existing.getMaintenanceStart().equals(input.maintenanceStart())
        && existing.getMaintenanceEnd().equals(input.maintenanceEnd())
        && existing.getStatusUrl().equals(url);
  }

  private static void validate(String key, DraftInput input, String operatorUserId) {
    requireKey(key);
    if (operatorUserId == null
        || operatorUserId.isBlank()
        || operatorUserId.length() > 100
        || input == null
        || input.maintenanceDate() == null
        || input.maintenanceStart() == null
        || input.maintenanceEnd() == null
        || !input.maintenanceStart().isBefore(input.maintenanceEnd())) {
      throw new IllegalArgumentException("A same-day maintenance window and operator are required");
    }
    validateStatusUrl(input.statusUrl());
  }

  private static void requireKey(String key) {
    if (key == null || !KEY.matcher(key).matches()) {
      throw new IllegalArgumentException("Campaign key is invalid");
    }
  }

  private static void validateStatusUrl(String raw) {
    if (raw == null || raw.isBlank() || raw.length() > 2048) {
      throw new IllegalArgumentException("A public HTTPS status URL is required");
    }
    try {
      URI url = URI.create(raw.trim());
      String host = url.getHost();
      if (!"https".equalsIgnoreCase(url.getScheme())
          || host == null
          || !FQDN.matcher(host).matches()
          || url.getRawUserInfo() != null
          || url.getRawFragment() != null
          || url.getRawQuery() != null
          || url.getPort() >= 0
          || isReservedHost(host)) {
        throw new IllegalArgumentException("Status URL must be an absolute public HTTPS page");
      }
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Status URL must be an absolute public HTTPS page");
    }
  }

  private static boolean isReservedHost(String host) {
    String normalized = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
    return normalized.contains("your-domain")
        || Arrays.stream(
                new String[] {
                  ".local",
                  ".internal",
                  ".test",
                  ".invalid",
                  ".example",
                  "example.com",
                  "example.org",
                  "example.net"
                })
            .anyMatch(
                suffix ->
                    normalized.equals(suffix.replaceFirst("^\\.", ""))
                        || normalized.endsWith(suffix.startsWith(".") ? suffix : "." + suffix));
  }
}
