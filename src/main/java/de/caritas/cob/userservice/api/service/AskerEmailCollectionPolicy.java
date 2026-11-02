package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.EnumSet;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** One account address; restrictive collection requires a real, owned seeker context. */
@Service
@RequiredArgsConstructor
public class AskerEmailCollectionPolicy {
  private final @NonNull TenantService tenants;
  private final @NonNull SessionRepository sessions;
  private final @NonNull UserChatRepository userChats;
  private final @NonNull PlatformTransactionManager transactionManager;

  public void requireForRegistration(Long tenantId, ConversationType verifiedType) {
    Settings settings = AskerIdentityPolicy.currentSettings(tenants, tenantId);
    if (!masterAllowed(settings) || !typeAllowed(settings, verifiedType)) denied();
  }

  public void requireForAccount(User user) {
    if (!allowedForAccount(user)) denied();
  }

  /** A read-only capability must not make a healthy account/login depend on policy availability. */
  public boolean capabilityForAccount(User user) {
    try {
      return allowedForAccount(user);
    } catch (ServiceUnavailableException unavailable) {
      return false;
    }
  }

  public boolean allowedForAccount(User user) {
    Objects.requireNonNull(user, "Actual asker required");
    if (user.getUserId() == null)
      throw new ServiceUnavailableException("Asker identity unavailable");
    // Complete the fresh HTTP request before acquiring a database connection for context reads.
    var authority = AskerIdentityPolicy.currentTenant(tenants, user.getTenantId());
    Settings settings = authority.getSettings();
    if (!masterAllowed(settings)) return false;
    if (typeAllowed(settings, ConversationType.AGENCY_COUNSELLING)
        && typeAllowed(settings, ConversationType.LIVE_CHAT)
        && typeAllowed(settings, ConversationType.SELF_HELP)) return true;
    var transaction = new TransactionTemplate(transactionManager);
    transaction.setReadOnly(true);
    try {
      return transaction.execute(
          ignored ->
              ownedTypes(user, authority.getId()).stream()
                  .anyMatch(type -> typeAllowed(settings, type)));
    } catch (DataAccessException | TransactionException unavailable) {
      throw new ServiceUnavailableException("Current asker collection contexts are unavailable");
    }
  }

  private EnumSet<ConversationType> ownedTypes(User user, Long resolvedTenantId) {
    var types = EnumSet.noneOf(ConversationType.class);
    for (Session session : sessions.findByUser(user)) {
      if (session.getUser() == null
          || !Objects.equals(user.getUserId(), session.getUser().getUserId())) continue;
      // A legacy null tenant is corroborated by its actual owner; an explicit foreign or invalid
      // tenant must not supply a context for this account's collection policy.
      if (session.getTenantId() != null
          && (session.getTenantId() <= 0
              || !Objects.equals(resolvedTenantId, session.getTenantId()))) continue;
      ConversationType type = session.getConversationType();
      if (type == null) {
        type =
            session.getRegistrationType() == Session.RegistrationType.ANONYMOUS
                ? ConversationType.LIVE_CHAT
                : session.getRegistrationType() == Session.RegistrationType.REGISTERED
                    ? ConversationType.AGENCY_COUNSELLING
                    : null;
      }
      if (type == ConversationType.AGENCY_COUNSELLING || type == ConversationType.LIVE_CHAT)
        types.add(type);
    }
    for (var membership : userChats.findByUser(user)) {
      if (membership.getUser() != null
          && Objects.equals(user.getUserId(), membership.getUser().getUserId())
          && membership.getChat() != null
          && membership.getChat().getConversationType() == ConversationType.SELF_HELP) {
        types.add(ConversationType.SELF_HELP);
      }
    }
    return types;
  }

  private boolean masterAllowed(Settings settings) {
    return !Boolean.FALSE.equals(settings.getFeatureAskerEmailEnabled());
  }

  private boolean typeAllowed(Settings settings, ConversationType type) {
    if (type == null) return false;
    return switch (type) {
      case AGENCY_COUNSELLING ->
          !Boolean.FALSE.equals(settings.getFeatureAskerEmailCollectionAgencyCounsellingEnabled());
      case LIVE_CHAT ->
          !Boolean.FALSE.equals(settings.getFeatureAskerEmailCollectionLiveChatEnabled());
      case SELF_HELP ->
          !Boolean.FALSE.equals(settings.getFeatureAskerEmailCollectionSelfHelpEnabled());
      case INTERNAL_GROUP -> false;
    };
  }

  public static boolean sameAddress(String existing, String target) {
    return existing != null
        && !existing.isBlank()
        && target != null
        && existing.trim().equalsIgnoreCase(target.trim());
  }

  private void denied() {
    throw new ForbiddenException(
        "The tenant does not allow collecting an asker email address in the available contexts");
  }
}
