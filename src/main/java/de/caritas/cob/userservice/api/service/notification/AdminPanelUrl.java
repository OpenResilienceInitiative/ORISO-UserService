package de.caritas.cob.userservice.api.service.notification;

import static org.apache.commons.lang3.StringUtils.isBlank;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place that derives the Admin panel URL used in transactional mail.
 *
 * <p>It exists because two paths need the same answer: the DPA_SIGNED_NOTICE that is delivered, and
 * the preview an operator reads before approving the wording. When each computed it for itself, the
 * preview could show a different destination than delivery actually carried — the same shape of
 * defect as a component deciding what to render while a gate decides what is acceptable. Deriving
 * it once means they cannot drift apart, rather than both happening to be right today.
 *
 * <p>No baked-in default: a fallback silently mails people into the wrong environment. The shape is
 * validated at startup too, because "https://", "/admin" and "mailto:…" all pass a blank check and
 * then produce links nobody can use.
 */
@Component
public class AdminPanelUrl {

  private final String adminUrl;

  public AdminPanelUrl(
      @Value("${account.invite.admin.frontend.base-url}") String adminFrontendBaseUrl) {
    this.adminUrl = normalizeBaseUrl(adminFrontendBaseUrl) + "/admin";
  }

  /** Absolute URL of the Admin panel; identical for delivery and preview. */
  public String value() {
    return adminUrl;
  }

  private static String normalizeBaseUrl(String value) {
    if (isBlank(value)) {
      throw new IllegalStateException(
          "account.invite.admin.frontend.base-url must be configured; transactional mail links"
              + " administrators into the Admin panel and must not guess an environment");
    }
    var base = value.trim();
    URI parsed;
    try {
      parsed = URI.create(base);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalStateException(
          "account.invite.admin.frontend.base-url is not a valid URL: " + base, malformed);
    }
    if (parsed.getScheme() == null
        || !(parsed.getScheme().equalsIgnoreCase("http")
            || parsed.getScheme().equalsIgnoreCase("https"))
        || isBlank(parsed.getHost())) {
      throw new IllegalStateException(
          "account.invite.admin.frontend.base-url must be an absolute http(s) URL with a host,"
              + " but was: "
              + base);
    }
    // A query or fragment would swallow the path we append: "https://host?source=x" + "/admin"
    // becomes "…?source=x/admin", which is a wrong-but-plausible link — exactly the kind this
    // class exists to stop reaching a recipient.
    if (parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
      throw new IllegalStateException(
          "account.invite.admin.frontend.base-url must carry no query or fragment, because the"
              + " Admin panel path is appended to it, but was: "
              + base);
    }
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base;
  }
}
