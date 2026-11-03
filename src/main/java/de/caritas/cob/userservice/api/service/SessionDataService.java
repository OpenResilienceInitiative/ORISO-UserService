package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.SessionDataKeyRegistration;
import de.caritas.cob.userservice.api.helper.SessionDataProvider;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.notification.AskerNotificationChannelPolicy;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Service for session data. */
@Service
@RequiredArgsConstructor
public class SessionDataService {

  private final @NonNull SessionDataRepository sessionDataRepository;
  private final @NonNull SessionDataProvider sessionDataProvider;
  private final @NonNull SessionService sessionService;
  private final @NonNull AuthenticatedUser authenticatedUser;
  private final @NonNull TenantService tenantService;
  private final @NonNull GeneratedPseudonyms generatedPseudonyms;
  private final @NonNull MembersOnlyChatAccess membersOnlyChatAccess;
  private final @NonNull PlatformTransactionManager transactionManager;

  /**
   * Saves additional registration information in session data for the given session ID.
   *
   * <p>Runs in a transaction so the loaded {@link Session} stays managed while {@link
   * SessionDataProvider} reads its lazily fetched {@code sessionData} collection. Without it the
   * session is detached as soon as {@link SessionService#getSession(Long)} returns and the lazy
   * access fails with a {@code LazyInitializationException} (the service runs with {@code
   * spring.jpa.open-in-view=false}).
   *
   * @param sessionId the session ID
   * @param sessionData {@link SessionData}
   */
  @Transactional
  public void saveSessionData(Long sessionId, SessionDataDTO sessionData) {
    Session session =
        authenticatedUser.isConsultant()
            ? sessionService.assertUserHasAccess(sessionId, authenticatedUser)
            : sessionService.assertAskerHasAccess(sessionId, authenticatedUser);
    membersOnlyChatAccess.requireOrdinary(
        session, "The session of a members-only chat has no session data.");
    this.saveSessionData(session, sessionData);
  }

  /**
   * Saves additional registration information in session data for the given session.
   *
   * @param session the {@link Session}
   * @param sessionData {@link SessionData}
   */
  public void saveSessionData(Session session, SessionDataDTO sessionData) {
    validateAliasChange(session, sessionData);
    List<SessionData> sessionDataList =
        sessionDataProvider.createSessionDataList(session, sessionData);

    sessionDataRepository.saveAll(sessionDataList);
  }

  /** Validates registration input before identity, account or session effects. */
  public String validateInitialAlias(String alias, Long tenantId) {
    return validateInitialAlias(alias, tenantId, ConversationType.AGENCY_COUNSELLING);
  }

  public String validateInitialAlias(String alias, Long tenantId, ConversationType type) {
    String normalized = StringUtils.stripToNull(alias);
    if (normalized != null) validateAliasValue(normalized, tenantId, type);
    return normalized;
  }

  public String validateRegistrationNames(
      String username, String alias, Long tenantId, ConversationType type) {
    boolean customNames = AskerIdentityPolicy.customNamesAllowed(tenantService, tenantId, type);
    if (!customNames
        && (username == null
            || !generatedPseudonyms.containsHandle(
                new UsernameTranscoder().decodeUsername(username)))) {
      throw new ForbiddenException("Choose a generated username when custom names are disabled");
    }
    String normalizedAlias = StringUtils.stripToNull(alias);
    if (normalizedAlias != null) validateAliasValue(normalizedAlias, customNames);
    return normalizedAlias;
  }

  /** Internal creation path: the trusted creator supplies the actual new owner, not a login. */
  public void saveInitialSessionData(Session session, User newOwner, SessionDataDTO data) {
    assertInitialOwner(session, newOwner);
    Long tenantId = aliasTenant(session);
    data.setDisplayName(
        validateInitialAlias(
            data.getDisplayName(),
            tenantId,
            AskerNotificationChannelPolicy.conversationType(session)));
    var transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(
        ignored -> {
          Session stored =
              sessionService
                  .getSession(session.getId())
                  .orElseThrow(() -> new NotFoundException("Initial session not found"));
          assertInitialOwner(stored, newOwner);
          if (!Objects.equals(tenantId, aliasTenant(stored))) {
            throw new ForbiddenException("The initial session identity changed");
          }
          membersOnlyChatAccess.requireOrdinary(
              stored, "A members-only chat has no personal alias.");
          sessionDataRepository.saveAll(sessionDataProvider.createSessionDataList(stored, data));
        });
  }

  private void assertInitialOwner(Session session, User owner) {
    if (session == null
        || owner == null
        || owner.getUserId() == null
        || session.getUser() == null
        || !Objects.equals(owner.getUserId(), session.getUser().getUserId())) {
      throw new ForbiddenException("The initial session does not belong to the registered asker");
    }
  }

  private Long aliasTenant(Session session) {
    return session.getTenantId() != null
        ? session.getTenantId()
        : session.getUser() == null ? null : session.getUser().getTenantId();
  }

  private void validateAliasValue(String alias, Long tenantId, ConversationType type) {
    validateAliasLength(alias);
    validateGeneratedAlias(
        alias, AskerIdentityPolicy.customNamesAllowed(tenantService, tenantId, type));
  }

  private void validateAliasValue(String alias, boolean customNames) {
    validateAliasLength(alias);
    validateGeneratedAlias(alias, customNames);
  }

  private void validateAliasLength(String alias) {
    if (alias.isBlank() || alias.length() > 255) {
      throw new BadRequestException("A session alias must contain 1 to 255 characters");
    }
  }

  private void validateGeneratedAlias(String alias, boolean customNames) {
    if (!customNames && !generatedPseudonyms.contains(alias)) {
      throw new ForbiddenException(
          "Choose a generated pseudonym when custom session aliases are disabled");
    }
  }

  private void validateAliasChange(Session session, SessionDataDTO update) {
    String alias = update.getDisplayName();
    if (alias == null || alias.isEmpty()) return;
    if (alias.isBlank() || alias.length() > 255) {
      throw new BadRequestException("A session alias must contain 1 to 255 characters");
    }
    if (session.getUser() == null
        || !Objects.equals(session.getUser().getUserId(), authenticatedUser.getUserId())
        || authenticatedUser.getUserId() == null) {
      throw new ForbiddenException("Only the asker may change their session alias");
    }
    // An unchanged legacy alias remains valid after the tenant disables custom names.
    if (session.getSessionData() != null
        && session.getSessionData().stream()
            .anyMatch(
                value ->
                    SessionDataKeyRegistration.DISPLAY_NAME.getValue().equals(value.getKey())
                        && alias.equals(value.getValue()))) return;
    validateAliasValue(
        alias, aliasTenant(session), AskerNotificationChannelPolicy.conversationType(session));
  }
}
