package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.NotificationsAware;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.email.OrisoEmailBrand;
import de.caritas.cob.userservice.api.service.email.OrisoEmailDispatcher;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Sends the privacy-neutral call lifecycle templates after the matching feed event was claimed. */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallLifecycleEmailNotificationService {

  private final @NonNull UserRepository userRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull DoNotDisturbService doNotDisturbService;
  private final @NonNull SystemNotificationEmailSettingsService emailSettingsService;
  private final @NonNull OrisoEmailRenderer emailRenderer;
  private final @NonNull OrisoEmailBrand emailBrand;
  private final @NonNull OrisoEmailDispatcher dispatcher;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  @Async
  public void sendInvitation(String recipientUserId, Long tenantId) {
    send(Occasion.INVITATION, recipientUserId, tenantId);
  }

  @Async
  public void sendReminder(String recipientUserId, Long tenantId) {
    send(Occasion.REMINDER, recipientUserId, tenantId);
  }

  @Async
  public void sendMissed(String recipientUserId, Long tenantId) {
    send(Occasion.MISSED, recipientUserId, tenantId);
  }

  void send(Occasion occasion, String recipientUserId, Long tenantId) {
    if (!isNotBlank(recipientUserId) || doNotDisturbService.isInDoNotDisturb(recipientUserId)) {
      return;
    }
    try {
      Optional<Recipient> recipient = resolveRecipient(recipientUserId);
      if (recipient.isEmpty() || !mayReceiveCallEmail(recipient.get())) {
        return;
      }
      OrisoEmailRenderer.Tone tone =
          OrisoEmailRenderer.Tone.of(
              recipient.get().languageCode(), recipient.get().formalGerman());
      if (!emailRenderer.isReleasedForSending(tone)) {
        log.info(
            "Skipping {} email for recipient {}: locale {} is pending human review",
            occasion.templateId,
            recipientUserId,
            tone.directory());
        return;
      }
      Long resolvedTenantId = tenantId != null ? tenantId : recipient.get().tenantId();
      var smtp =
          emailSettingsService
              .resolveSupervisorAddedEmailSettings(resolvedTenantId, null)
              .orElse(null);
      if (smtp == null) {
        return;
      }
      Map<String, String> values =
          new LinkedHashMap<>(emailBrand.values(applicationBaseUrl, smtp.getEmailThemeColor()));
      values.put("callUrl", values.get("appUrl"));
      dispatcher.send(
          smtp, recipient.get().email(), emailRenderer.render(occasion.templateId, tone, values));
    } catch (RuntimeException exception) {
      log.warn(
          "Could not prepare {} email for recipient {}",
          occasion.templateId,
          recipientUserId,
          exception);
    }
  }

  private Optional<Recipient> resolveRecipient(String recipientUserId) {
    var user = userRepository.findByUserIdAndDeleteDateIsNull(recipientUserId);
    if (user.isPresent()) {
      var value = user.get();
      return Optional.of(
          new Recipient(
              value,
              value.getEmail(),
              value.getTenantId(),
              value.getLanguageCode(),
              value.isLanguageFormal()));
    }
    return consultantRepository
        .findByIdAndDeleteDateIsNull(recipientUserId)
        .map(
            value ->
                new Recipient(
                    value,
                    value.getEmail(),
                    value.getTenantId(),
                    value.getLanguageCode(),
                    value.isLanguageFormal()));
  }

  private boolean mayReceiveCallEmail(Recipient recipient) {
    if (!isNotBlank(recipient.email())
        || (isNotBlank(emailDummySuffix) && recipient.email().endsWith(emailDummySuffix))
        || !recipient.preferences().isNotificationsEnabled()) {
      return false;
    }
    return deserializeNotificationSettingsOrDefaultIfNull(recipient.preferences())
        .isAppointmentNotificationEnabled();
  }

  enum Occasion {
    INVITATION("anruf-einladung"),
    REMINDER("anruf-erinnerung"),
    MISSED("anruf-verpasst");

    private final String templateId;

    Occasion(String templateId) {
      this.templateId = templateId;
    }
  }

  private record Recipient(
      NotificationsAware preferences,
      String email,
      Long tenantId,
      LanguageCode languageCode,
      boolean formalGerman) {}
}
