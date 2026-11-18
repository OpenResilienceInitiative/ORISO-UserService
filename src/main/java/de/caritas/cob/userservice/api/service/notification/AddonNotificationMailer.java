package de.caritas.cob.userservice.api.service.notification;

import static de.caritas.cob.userservice.api.helper.EmailNotificationUtils.deserializeNotificationSettingsDTOOrDefaultIfNull;
import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.donotdisturb.DoNotDisturbService;
import de.caritas.cob.userservice.api.service.emailsupplier.EmailSupplier;
import de.caritas.cob.userservice.api.service.emailsupplier.TenantTemplateSupplier;
import de.caritas.cob.userservice.api.service.helper.MailService;
import de.caritas.cob.userservice.api.service.notification.AddonNotificationService.RecipientKind;
import de.caritas.cob.userservice.mailservice.generated.web.model.Dialect;
import de.caritas.cob.userservice.mailservice.generated.web.model.LanguageCode;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.MailsDTO;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * E-mail for an important add-on notification. Uses the recipient's "new message" switch (ADR-024:
 * {@code newChatMessageNotificationEnabled} for advice seekers, the counsellor's new-message toggle
 * and settings for counsellors) and sends the neutral catalogue mail {@code mitteilung}: add-on
 * text never enters a mail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddonNotificationMailer {

  private static final Set<LanguageCode> INSTALLED_LANGUAGES =
      Set.of(
          LanguageCode.DE,
          LanguageCode.EN,
          LanguageCode.FR,
          LanguageCode.RU,
          LanguageCode.TI,
          LanguageCode.TR);

  private final UserRepository userRepository;
  private final ConsultantRepository consultantRepository;
  private final NotificationAccountEligibility accountEligibility;
  private final DoNotDisturbService doNotDisturb;
  private final TenantService tenants;
  private final TenantTemplateSupplier tenantTemplates;
  private final MailService mailService;

  @Value("${identity.email-dummy-suffix:}")
  private String emailDummySuffix;

  @Value("${multitenancy.enabled}")
  private boolean multitenancyEnabled;

  @Value("${app.base.url}")
  private String applicationBaseUrl;

  @Async
  public void sendIfWanted(RecipientKind kind, String recipientUserId, Long tenantId) {
    try {
      mailFor(kind, recipientUserId, tenantId)
          .ifPresent(
              mail -> mailService.sendEmailNotification(new MailsDTO().mails(List.of(mail))));
    } catch (RuntimeException failure) {
      log.error("Add-on notification mail failed for tenant {}", tenantId, failure);
    }
  }

  private record Recipient(
      String email,
      com.neovisionaries.i18n.LanguageCode language,
      Dialect dialect,
      boolean adviceSeeker) {}

  Optional<MailDTO> mailFor(RecipientKind kind, String recipientUserId, Long tenantId) {
    var recipient =
        kind == RecipientKind.ASKER
            ? userRepository
                .findByUserIdAndDeleteDateIsNull(recipientUserId)
                .filter(this::wantsMail)
                .map(
                    user ->
                        new Recipient(
                            user.getEmail(), user.getLanguageCode(), user.getDialect(), true))
            : consultantRepository
                .findByIdAndDeleteDateIsNull(recipientUserId)
                .filter(this::wantsMail)
                .map(
                    consultant ->
                        new Recipient(
                            consultant.getEmail(),
                            consultant.getLanguageCode(),
                            consultant.getDialect(),
                            false));
    return recipient.flatMap(wanted -> mail(wanted, tenantId));
  }

  private boolean wantsMail(User user) {
    return user.isNotificationsEnabled()
        && !Boolean.FALSE.equals(
            deserializeNotificationSettingsDTOOrDefaultIfNull(user)
                .getNewChatMessageNotificationEnabled())
        && hasUsableAddress(user.getEmail())
        && accountEligibility.allows(user.getUserId());
  }

  private boolean wantsMail(Consultant consultant) {
    return !Boolean.FALSE.equals(consultant.getNotifyNewChatMessageFromAdviceSeeker())
        && (isBlank(consultant.getNotificationsSettings())
            || (consultant.isNotificationsEnabled()
                && Boolean.TRUE.equals(
                    deserializeNotificationSettingsDTOOrDefaultIfNull(consultant)
                        .getNewChatMessageNotificationEnabled())))
        && !doNotDisturb.isInDoNotDisturb(consultant.getId())
        && hasUsableAddress(consultant.getEmail())
        && accountEligibility.allows(consultant);
  }

  private Optional<MailDTO> mail(Recipient recipient, Long tenantId) {
    var tenant = tenants.getRestrictedTenantDataFresh(tenantId);
    if (tenant == null || !tenantId.equals(tenant.getId())) {
      throw new IllegalStateException("Add-on notification tenant is unavailable");
    }
    if (recipient.adviceSeeker()
        && !AskerNotificationChannelPolicy.emailAllowed(
            ConversationType.AGENCY_COUNSELLING, tenant.getSettings())) {
      return Optional.empty();
    }
    var baseUrl =
        multitenancyEnabled ? tenantTemplates.getTenantBaseUrl(tenant) : applicationBaseUrl;
    return Optional.of(
        new MailDTO()
            .template(EmailSupplier.TEMPLATE_ADDON_NOTIFICATION)
            .email(recipient.email())
            .language(installedLanguage(recipient.language()))
            .dialect(recipient.dialect() == null ? Dialect.FORMAL : recipient.dialect())
            .templateData(
                List.of(
                    new TemplateDataDTO().key("tenantId").value(tenantId.toString()),
                    new TemplateDataDTO().key("recipientTenantId").value(tenantId.toString()),
                    new TemplateDataDTO().key("url").value(baseUrl))));
  }

  private static LanguageCode installedLanguage(com.neovisionaries.i18n.LanguageCode code) {
    if (code == null) {
      return LanguageCode.DE;
    }
    try {
      var language = LanguageCode.fromValue(code.toString());
      return INSTALLED_LANGUAGES.contains(language) ? language : LanguageCode.DE;
    } catch (IllegalArgumentException notInstalled) {
      return LanguageCode.DE;
    }
  }

  private boolean hasUsableAddress(String address) {
    return !isBlank(address) && (isBlank(emailDummySuffix) || !address.endsWith(emailDummySuffix));
  }
}
