package de.caritas.cob.userservice.api.adapters.keycloak.commands;

import de.caritas.cob.userservice.api.actions.registry.ActionsRegistry;
import de.caritas.cob.userservice.api.port.out.*;
import de.caritas.cob.userservice.api.workflow.delete.action.asker.*;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.*;
import de.caritas.cob.userservice.api.workflow.delete.model.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local cleanup is authorized only by a provider-owned unfinished creation recovery receipt. */
@Service
@RequiredArgsConstructor
public class IdentityCreationLocalCleanup {
  private final IdentityCreationJournalWriter journal;
  private final UserRepository users;
  private final ConsultantRepository consultants;
  private final AdminRepository admins;
  private final AdminAgencyRepository adminAgencies;
  private final ActionsRegistry actions;
  private final JdbcTemplate jdbc;
  private final IdentityCreationEffects effects;

  @Transactional
  public void clean(UUID attemptId) {
    var row =
        journal.cleanupAttempt(attemptId); // lock spans every deletion and the durable next intent
    effects.clean(attemptId); // rooms, then Matrix identity/restoration, before local/native finish
    switch (row.getRegistrationKind()) {
      case "ASKER", "ANONYMOUS" ->
          users
              .findById(row.getAccountId())
              .ifPresent(
                  user -> {
                    sameTenant(row.getTenantId(), user.getTenantId());
                    var target = new AskerDeletionWorkflowDTO(user, new ArrayList<>());
                    actions
                        .buildContainerForType(AskerDeletionWorkflowDTO.class)
                        .addActionToExecute(DeleteAskerRoomsAndSessionsAction.class)
                        .addActionToExecute(DeleteDatabaseAskerAgencyAction.class)
                        .addActionToExecute(DeleteAnonymousRegistryIdAction.class)
                        .addActionToExecute(DeleteAskerDraftMessagesAction.class)
                        .addActionToExecute(DeleteAskerEventNotificationsAction.class)
                        .addActionToExecute(DeleteAskerReplyEmailDeliveriesAction.class)
                        .addActionToExecute(DeleteDatabaseAskerAction.class)
                        .executeActions(target);
                    if (!target.getDeletionWorkflowErrors().isEmpty()
                        || users.existsById(row.getAccountId())) throw incomplete();
                  });
      case "CONSULTANT", "CONSULTANT_AGENCY_ADMIN" ->
          consultants
              .findById(row.getAccountId())
              .ifPresent(
                  consultant -> {
                    sameTenant(row.getTenantId(), consultant.getTenantId());
                    var target = new ConsultantDeletionWorkflowDTO(consultant, new ArrayList<>());
                    actions
                        .buildContainerForType(ConsultantDeletionWorkflowDTO.class)
                        .addActionToExecute(DeleteDatabaseConsultantAgencyAction.class)
                        .addActionToExecute(DeleteCaseHandoverRequestsForConsultantAction.class)
                        .addActionToExecute(DeleteConsultantDraftMessagesAction.class)
                        .addActionToExecute(DeleteConsultantEventNotificationsAction.class)
                        .addActionToExecute(DeleteConsultantMessageEmailDeliveriesAction.class)
                        .addActionToExecute(DeleteDatabaseConsultantAction.class)
                        .executeActions(target);
                    if (!target.getDeletionWorkflowErrors().isEmpty()
                        || consultants.existsById(row.getAccountId())) throw incomplete();
                  });
      case "AGENCY_ADMIN", "TENANT_ADMIN" -> deleteOwnedAdmin(row);
      default -> throw new AccessDeniedException("Unsupported owned creation kind");
    }
    if ("CONSULTANT_AGENCY_ADMIN".equals(row.getRegistrationKind())) deleteOwnedAdmin(row);
    // A created account was never activated. An already-running lifecycle cannot be adopted.
    var snapshots =
        jdbc.queryForList(
            "SELECT tenant_id,status,attempts FROM account_inactivity WHERE identity_id=?",
            row.getAccountId());
    if (!snapshots.isEmpty()) {
      var snapshot = snapshots.get(0);
      var tenant = snapshot.get("tenant_id");
      sameTenant(row.getTenantId(), tenant == null ? null : ((Number) tenant).longValue());
      if (!"ACTIVE".equals(snapshot.get("status"))
          || ((Number) snapshot.get("attempts")).intValue() != 0) throw incomplete();
      jdbc.update(
          "DELETE FROM account_inactivity WHERE identity_id=? AND status='ACTIVE' AND attempts=0",
          row.getAccountId());
    }
    journal.cleanedLocally(attemptId);
  }

  private void deleteOwnedAdmin(de.caritas.cob.userservice.api.model.IdentityCreationAttempt row) {
    admins
        .findById(row.getAccountId())
        .ifPresent(
            admin -> {
              sameTenant(row.getTenantId(), admin.getTenantId());
              adminAgencies.deleteByAdminId(admin.getId());
              admins.delete(admin);
              admins.flush();
            });
  }

  private static void sameTenant(Long expected, Long actual) {
    if (!Objects.equals(expected, actual))
      throw new AccessDeniedException("Recovery target exceeds its captured tenant");
  }

  private static IllegalStateException incomplete() {
    return new IllegalStateException("Owned local creation cleanup remains pending");
  }
}
