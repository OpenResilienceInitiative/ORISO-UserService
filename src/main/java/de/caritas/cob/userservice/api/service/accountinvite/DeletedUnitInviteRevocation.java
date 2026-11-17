package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.DeletionAuditLogRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationExpiryService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkStatus;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import java.time.LocalDateTime;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A deleted Träger or Beratungsstelle keeps no open invitation and no working invite link
 * (ORISO-Admin#1522, ORISO-UserService#1617 part 2). Open invites are revoked, which also frees
 * their e-mail address; active links are switched off, which ends the access of an invitation with
 * an appointment as when an admin switches it off. Settled invites and links stay as they are, so
 * every call can be repeated.
 *
 * <p>Runs when UserService learns of the deletion: the deletion report of AgencyService and
 * TenantService, and again for every Beratungsstelle the nightly agency purge reports.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeletedUnitInviteRevocation {

  static final String AGENCY_DELETED = "AGENCY_DELETED";
  static final String TENANT_DELETED = "TENANT_DELETED";

  /** The platform's own tenant: its admins are never revoked by a Träger deletion. */
  private static final long PLATFORM_TENANT = 0L;

  private static final List<AccountInviteStatus> OPEN =
      List.of(
          AccountInviteStatus.WAITING_FOR_UNIT,
          AccountInviteStatus.DRAFT,
          AccountInviteStatus.EMAIL_SENT);

  private final @NonNull AccountInviteRepository invites;
  private final @NonNull AgencyInviteLinkRepository links;
  private final @NonNull ObjectProvider<DirectInvitationExpiryService> invitationEnd;
  private final @NonNull PlatformTransactionManager transactionManager;
  private final @NonNull DeletionAuditLogRepository deletionLog;

  private static final List<DeletionAuditOutcome> DELETED =
      List.of(DeletionAuditOutcome.SCHEDULED, DeletionAuditOutcome.EXECUTED);

  /** How many invites were revoked and links switched off. */
  public record Revoked(int invites, int links) {
    static final Revoked NOTHING = new Revoked(0, 0);
  }

  /**
   * Invites and links of one deletion commit or roll back together, on every path: the transaction
   * is opened here, not by an annotation a call through {@code this} would bypass.
   */
  public Revoked revokeForAgency(Long agencyId) {
    if (agencyId == null) {
      return Revoked.NOTHING;
    }
    return inOneTransaction(
        now -> {
          int revoked = invites.revokeOpenInvitesOfUnit(null, agencyId, OPEN, AGENCY_DELETED, now);
          int switchedOff = switchOff(links.findByAgencyIdAndStatus(agencyId, active()), now);
          return logged(new Revoked(revoked, switchedOff), "Beratungsstelle", agencyId);
        });
  }

  /** As {@link #revokeForAgency(Long)}, for a Träger. */
  public Revoked revokeForTenant(Long tenantId) {
    if (tenantId == null || tenantId == PLATFORM_TENANT) {
      return Revoked.NOTHING;
    }
    return inOneTransaction(
        now -> {
          int revoked = invites.revokeOpenInvitesOfUnit(tenantId, null, OPEN, TENANT_DELETED, now);
          int switchedOff = switchOff(links.findByTenantIdAndStatus(tenantId, active()), now);
          return logged(new Revoked(revoked, switchedOff), "Träger", tenantId);
        });
  }

  private Revoked inOneTransaction(java.util.function.Function<LocalDateTime, Revoked> work) {
    var now = LocalDateTime.now();
    Revoked result = new TransactionTemplate(transactionManager).execute(status -> work.apply(now));
    return result == null ? Revoked.NOTHING : result;
  }

  /**
   * For a reported deletion; never throws, because the caller's deletion has already happened. A
   * cancelled or partly failed deletion revokes nothing.
   *
   * @return {@code false} only when the revocation failed and was rolled back, so a caller that can
   *     retry (the nightly agency pull) keeps its row for the next run
   */
  public boolean onDeletionReported(
      DeletionAuditTargetType targetType, String targetId, DeletionAuditOutcome outcome) {
    if (outcome != DeletionAuditOutcome.SCHEDULED && outcome != DeletionAuditOutcome.EXECUTED) {
      return true;
    }
    Long id;
    try {
      id = Long.valueOf(targetId);
    } catch (NumberFormatException unreadable) {
      log.warn("Ignoring a deletion report with an unreadable {} id", targetType);
      return true;
    }
    try {
      if (targetType == DeletionAuditTargetType.AGENCY) {
        revokeForAgency(id);
      } else if (targetType == DeletionAuditTargetType.TENANT) {
        revokeForTenant(id);
      }
      return true;
    } catch (RuntimeException failure) {
      log.warn(
          "Could not revoke the invitations of deleted {} {}: {}",
          targetType,
          targetId,
          failure.getClass().getSimpleName());
      return false;
    }
  }

  /**
   * The durable retry (review of ORISO-UserService#1675): every Träger and Beratungsstelle the
   * deletion log reports as deleted that still holds an open invite or an active link is revoked
   * again. A report-time revocation that failed is thus completed by the next run; nothing is
   * written when nothing is left. Run by {@code DeletedUnitInviteRetryScheduler}.
   *
   * @return how many units had something left that is now revoked
   */
  public int retryUnfinished() {
    int completed = 0;
    for (String id :
        deletionLog.findDeletedTenantsStillInviting(
            DeletionAuditTargetType.TENANT, DELETED, OPEN, active())) {
      completed += retried(DeletionAuditTargetType.TENANT, id);
    }
    for (String id :
        deletionLog.findDeletedAgenciesStillInviting(
            DeletionAuditTargetType.AGENCY, DELETED, OPEN, active())) {
      completed += retried(DeletionAuditTargetType.AGENCY, id);
    }
    return completed;
  }

  /** 1 when something was revoked, 0 when nothing was left or the retry failed again. */
  private int retried(DeletionAuditTargetType type, String targetId) {
    try {
      Long id = Long.valueOf(targetId);
      Revoked result =
          type == DeletionAuditTargetType.AGENCY ? revokeForAgency(id) : revokeForTenant(id);
      return result.invites() + result.links() > 0 ? 1 : 0;
    } catch (RuntimeException failure) {
      log.warn(
          "Retry of the invitation revocation for deleted {} {} failed again: {}",
          type,
          targetId,
          failure.getClass().getSimpleName());
      return 0;
    }
  }

  private int switchOff(List<AgencyInviteLink> activeLinks, LocalDateTime now) {
    var end = invitationEnd.getIfAvailable();
    for (AgencyInviteLink link : activeLinks) {
      link.setStatus(InviteLinkStatus.DISABLED.name());
      link.setUpdateDate(now);
      links.save(link);
      if (end != null && link.getAppointmentId() != null) {
        end.revoke(link);
      }
    }
    return activeLinks.size();
  }

  private static String active() {
    return InviteLinkStatus.ACTIVE.name();
  }

  private static Revoked logged(Revoked result, String unit, Long id) {
    if (result.invites() > 0 || result.links() > 0) {
      log.info(
          "Deleted {} {}: revoked {} invitations, switched off {} invite links",
          unit,
          id,
          result.invites(),
          result.links());
    }
    return result;
  }
}
