package de.caritas.cob.userservice.api.admin.service.session;

import de.caritas.cob.userservice.api.adapters.web.dto.SessionAdminResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.admin.service.admin.AdminCallerScope;
import de.caritas.cob.userservice.api.admin.service.session.pageprovider.AgencyScopedSessionPageProvider;
import de.caritas.cob.userservice.api.admin.service.session.pageprovider.PageProviderFactory;
import de.caritas.cob.userservice.api.admin.service.session.pageprovider.SessionPageProvider;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service class to handle administrative operations on sessions. */
@Service
@RequiredArgsConstructor
public class SessionAdminService {

  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull AdminCallerScope adminCallerScope;

  /**
   * Like {@link #findSessions(Integer, Integer, SessionFilter)}, narrowed to what the calling admin
   * may see: a Beratungsstellen admin only gets the sessions of their own agencies, a Träger admin
   * the sessions of their own tenant (tenant filter), the platform admin everything. This is the
   * entry point of {@code GET /useradmin/sessions}.
   *
   * @param page the current page
   * @param perPage number of items per page
   * @param sessionFilter criteria to filter on sessions
   * @return a generated {@link SessionAdminResultDTO} containing the results
   */
  @Transactional(readOnly = true)
  public SessionAdminResultDTO findSessionsInCallerScope(
      Integer page, Integer perPage, SessionFilter sessionFilter) {
    var agencyRestriction = adminCallerScope.agencyRestriction();
    if (agencyRestriction.isEmpty()) {
      return findSessions(page, perPage, sessionFilter);
    }
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(perPage, 1));
    SessionPageProvider sessionPageProvider =
        new AgencyScopedSessionPageProvider(
            this.sessionRepository, sessionFilter, agencyRestriction.get());
    return SessionAdminResultDTOBuilder.getInstance()
        .withPage(page)
        .withPerPage(perPage)
        .withFilter(sessionFilter)
        .withResultPage(sessionPageProvider.executeQuery(pageable))
        .build();
  }

  /**
   * Finds existing sessions filtered by {@link SessionFilter} and retrieves all sessions if no
   * filter is set.
   *
   * @param page the current page
   * @param perPage number of items per page
   * @param sessionFilter criteria to filter on sessions
   * @return a generated {@link SessionAdminResultDTO} containing the results
   */
  // Read-only transaction: one Hibernate session for the whole page. The tenant filter itself no
  // longer depends on it (it is auto-enabled on every session, see TenantFilter).
  @Transactional(readOnly = true)
  public SessionAdminResultDTO findSessions(
      Integer page, Integer perPage, SessionFilter sessionFilter) {
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.max(perPage, 1));

    var sessionPageProvider =
        PageProviderFactory.getInstance(this.sessionRepository, sessionFilter)
            .retrieveFirstSupportedSessionPageProvider();

    return SessionAdminResultDTOBuilder.getInstance()
        .withPage(page)
        .withPerPage(perPage)
        .withFilter(sessionFilter)
        .withResultPage(sessionPageProvider.executeQuery(pageable))
        .build();
  }
}
