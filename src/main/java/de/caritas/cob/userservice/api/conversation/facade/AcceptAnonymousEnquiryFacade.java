package de.caritas.cob.userservice.api.conversation.facade;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.assignsession.AssignEnquiryFacade;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import jakarta.transaction.Transactional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Facade to encapsulate necessary steps to accept an anonymous enquiry. */
@Service
@RequiredArgsConstructor
public class AcceptAnonymousEnquiryFacade {

  private final @NonNull AssignEnquiryFacade assignEnquiryFacade;
  private final @NonNull SessionService sessionService;
  private final @NonNull UserAccountService userAccountProvider;

  /**
   * Accepts the anonymous enquiry with the given session id and assigns the session to the current
   * authenticated consultant.
   *
   * <p>#774: anonymous Live Chat enquiries are deliberately cross-tenant — the asker's session
   * lives in its own tenant while the accepting consultant is in another (the queue makes them
   * visible across tenants). The assignment therefore runs with the Hibernate tenant filter
   * disabled (technical context), the same mechanism {@link
   * de.caritas.cob.userservice.api.conversation.provider.AnonymousEnquiryConversationListProvider}
   * uses to list them. Without it {@code getSessionForUpdate} cannot find the cross-tenant session
   * and the accept fails with 404. The caller's tenant is always restored afterwards so no other
   * work in the request leaks.
   *
   * @param sessionId the id of the anonymous session
   */
  @Transactional
  public void acceptAnonymousEnquiry(Long sessionId) {
    var callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);

      var session =
          sessionService
              .getSessionForUpdate(sessionId)
              .orElseThrow(
                  () -> new NotFoundException("Session with id %s does not exist", sessionId));

      var consultant = this.userAccountProvider.retrieveValidatedConsultant();
      this.assignEnquiryFacade.assignAnonymousEnquiry(session, consultant);
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }
}
