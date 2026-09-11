package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.notification.SystemNotificationEmailSettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The mail an advice seeker gets once, when their account is created.
 *
 * <p>Its job is the user name. Registration is anonymous, the name is generated, and ORISO cannot
 * recover it — so a recipient who loses it loses the account and the counselling with it. The mail
 * exists to put that name somewhere outside the browser session that created it.
 *
 * <p>ADR-019 puts this in the personal class, sent once at account creation and not switchable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WelcomeEmailService {

  private final @NonNull SystemNotificationEmailSettingsService emailSettingsService;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull OrisoEmailDispatcher dispatcher;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  /**
   * Sends the welcome mail, if there is anywhere to send it.
   *
   * <p>Asynchronous and failure-tolerant on purpose: registration must not depend on SMTP. A
   * recipient without a mail address is the normal case for an anonymous registration, not an error
   * — the user name is shown on screen at the end of registration either way.
   */
  @Async
  public void sendWelcomeEmail(User user, String plainUsername) {
    if (user == null || !hasUsableEmail(user)) {
      return;
    }
    if (!isNotBlank(plainUsername)) {
      // Without the user name the mail has no reason to exist: it would say
      // "keep this safe" and then show nothing.
      log.warn("Skipping welcome mail: no plain user name available");
      return;
    }

    var smtp =
        user.getTenantId() == null
            ? null
            : emailSettingsService
                .resolveSupervisorAddedEmailSettings(user.getTenantId(), null)
                .orElse(null);
    if (smtp == null) {
      log.debug("Skipping welcome mail: no SMTP settings for tenant {}", user.getTenantId());
      return;
    }

    Map<String, String> values = new LinkedHashMap<>(emailBrand.values(applicationBaseUrl, null));
    values.put("username", plainUsername);
    values.put("loginUrl", values.get("appUrl"));

    var email =
        emailRenderer.render(
            "willkommen", OrisoEmailRenderer.Tone.of(user.getLanguageCode()), values);
    dispatcher.send(smtp, user.getEmail(), email);
  }

  /**
   * Anonymous accounts get a synthetic address ending in the configured dummy suffix. Sending there
   * would bounce into a mailbox nobody reads.
   */
  private boolean hasUsableEmail(User user) {
    return isNotBlank(user.getEmail())
        && (!isNotBlank(emailDummySuffix) || !user.getEmail().endsWith(emailDummySuffix));
  }
}
