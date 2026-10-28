package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionDataDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.helper.SessionDataKeyRegistration;
import de.caritas.cob.userservice.api.helper.SessionDataProvider;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.SessionData;
import de.caritas.cob.userservice.api.port.out.SessionDataRepository;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.session.SessionService;
import java.util.List;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    Long tenantId = session.getTenantId();
    if (tenantId == null && session.getUser() != null) tenantId = session.getUser().getTenantId();
    boolean customAllowed = AskerIdentityPolicy.customNamesAllowed(tenantService, tenantId);
    if (!customAllowed && !generatedPseudonyms.contains(alias)) {
      throw new ForbiddenException(
          "Choose a generated pseudonym when custom session aliases are disabled");
    }
  }
}
