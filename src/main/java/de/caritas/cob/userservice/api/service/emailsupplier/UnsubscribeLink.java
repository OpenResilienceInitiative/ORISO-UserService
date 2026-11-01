package de.caritas.cob.userservice.api.service.emailsupplier;

import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * The footer link of a mail sent through the upstream MailService.
 *
 * <p>It names the email settings screen and the mail's occasion ({@code ?mail=…}), so the screen
 * can focus the switch for exactly that mail (ORISO-Frontend#872). It is built on the {@code url}
 * the mail already carries, so it always points at the recipient's own tenant.
 */
final class UnsubscribeLink {

  static final String KEY = "unsubscribe_url";
  private static final String EMAIL_SETTINGS_PATH = "/profile/einstellungen/email";

  private UnsubscribeLink() {}

  /** Empty when the mail carries no {@code url}: no link beats a relative one. */
  static Optional<TemplateDataDTO> forOccasion(List<TemplateDataDTO> attributes, String occasion) {
    return attributes.stream()
        .filter(data -> "url".equals(data.getKey()) && StringUtils.isNotBlank(data.getValue()))
        .map(data -> StringUtils.stripEnd(data.getValue(), "/"))
        .findFirst()
        .map(
            base ->
                new TemplateDataDTO()
                    .key(KEY)
                    .value(base + EMAIL_SETTINGS_PATH + "?mail=" + occasion));
  }
}
