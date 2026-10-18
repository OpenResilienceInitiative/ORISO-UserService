package de.caritas.cob.userservice.api.service.notification;

import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;

/** Uses the persisted session modality, including the server's legacy registration fallback. */
public final class AskerNotificationChannelPolicy {
  private AskerNotificationChannelPolicy() {}

  public static ConversationType conversationType(Session session) {
    if (session == null) return ConversationType.AGENCY_COUNSELLING;
    if (session.getConversationType() != null) return session.getConversationType();
    return session.getRegistrationType() == RegistrationType.ANONYMOUS
        ? ConversationType.LIVE_CHAT
        : ConversationType.AGENCY_COUNSELLING;
  }

  public static boolean emailAllowed(Session session, Settings settings) {
    if (settings != null && Boolean.FALSE.equals(settings.getFeatureAskerEmailEnabled()))
      return false;
    return switch (conversationType(session)) {
      case AGENCY_COUNSELLING ->
          settings == null
              || !Boolean.FALSE.equals(settings.getFeatureAskerEmailAgencyCounsellingEnabled());
      case LIVE_CHAT ->
          settings != null && Boolean.TRUE.equals(settings.getFeatureAskerEmailLiveChatEnabled());
      case SELF_HELP ->
          settings == null || !Boolean.FALSE.equals(settings.getFeatureAskerEmailSelfHelpEnabled());
      case INTERNAL_GROUP -> false;
    };
  }
}
