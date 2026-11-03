package de.caritas.cob.userservice.api.helper;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.web.dto.NotificationsSettingsDTO;
import de.caritas.cob.userservice.api.helper.json.JsonSerializationUtils;
import de.caritas.cob.userservice.api.model.NotificationSettings;
import de.caritas.cob.userservice.api.model.NotificationsAware;

public class EmailNotificationUtils {

  private EmailNotificationUtils() {}

  // Blank counts as missing: the nullable column defaults to an empty string.

  public static NotificationSettings deserializeNotificationSettingsOrDefaultIfNull(
      NotificationsAware notificationsAware) {
    return isBlank(notificationsAware.getNotificationsSettings())
        ? new NotificationSettings()
        : JsonSerializationUtils.deserializeFromJsonString(
            notificationsAware.getNotificationsSettings(), NotificationSettings.class);
  }

  public static NotificationsSettingsDTO deserializeNotificationSettingsDTOOrDefaultIfNull(
      NotificationsAware notificationsAware) {
    return isBlank(notificationsAware.getNotificationsSettings())
        ? new NotificationsSettingsDTO()
        : JsonSerializationUtils.deserializeFromJsonString(
            notificationsAware.getNotificationsSettings(), NotificationsSettingsDTO.class);
  }
}
