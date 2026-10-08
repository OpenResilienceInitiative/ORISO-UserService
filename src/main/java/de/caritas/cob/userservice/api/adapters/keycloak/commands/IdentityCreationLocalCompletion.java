package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.model.*;
import de.caritas.cob.userservice.api.port.out.*;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The final local domain write and durable commit intent share one transaction. */
@Service
@RequiredArgsConstructor
@Transactional
public class IdentityCreationLocalCompletion {
  private final IdentityCreationJournalWriter journal;
  private final IdentityAccountProvisioning provisioning;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final AdminRepository admins;
  private final ConsultantRepository consultants;

  public void user(User user) {
    require(user.getUserId(), user.getTenantId(), Set.of("ASKER"));
    users.save(user);
    provisioning.completeCreatedAccount(user.getUserId());
  }

  public void anonymousSession(String accountId, Session session) {
    if (session == null
        || session.getUser() == null
        || session.getId() == null
        || !accountId.equals(session.getUser().getUserId())) throw denied();
    var row = require(accountId, session.getUser().getTenantId(), Set.of("ANONYMOUS"));
    sessions.save(session);
    journal.captureAnonymousBootstrap(java.util.UUID.fromString(row.getId()), session.getId());
    provisioning.completeCreatedAccount(accountId);
  }

  public void admin(Admin admin) {
    require(admin.getId(), admin.getTenantId(), Set.of("AGENCY_ADMIN", "TENANT_ADMIN"));
    admins.saveAndFlush(admin);
    provisioning.completeCreatedAccount(admin.getId());
  }

  public void importedConsultant(Consultant consultant) {
    var row = require(consultant.getId(), consultant.getTenantId(), Set.of("CONSULTANT"));
    if (!"IMPORT".equals(row.getOriginKind())) throw denied();
    consultants.saveAndFlush(consultant);
    provisioning.completeCreatedAccount(consultant.getId());
  }

  private IdentityCreationAttempt require(String accountId, Long tenant, Set<String> kinds) {
    var row = journal.ownedAttempt(accountId);
    if (!CreationStatus.in(row, CreationStatus.OPEN)
        || !Objects.equals(row.getTenantId(), tenant)
        || !kinds.contains(row.getRegistrationKind())) throw denied();
    return row;
  }

  private static AccessDeniedException denied() {
    return new AccessDeniedException("Local saga completion exceeds its owned creation attempt");
  }
}
