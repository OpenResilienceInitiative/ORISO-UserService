package de.caritas.cob.userservice.api.service.accountinvite;

import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
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
import org.springframework.transaction.annotation.Transactional;

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

  /** How many invites were revoked and links switched off. */
  public record Revoked(int invites, int links) {
    static final Revoked NOTHING = new Revoked(0, 0);
  }

  @Transactional
  public Revoked revokeForAgency(Long agencyId) {
    if (agencyId == null) {
      return Revoked.NOTHING;
    }
    var now = LocalDateTime.now();
    int revoked = invites.revokeOpenInvitesOfUnit(null, agencyId, OPEN, AGENCY_DELETED, now);
    int switchedOff = switchOff(links.findByAgencyIdAndStatus(agencyId, active()), now);
    return logged(new Revoked(revoked, switchedOff), "Beratungsstelle", agencyId);
  }

  @Transactional
  public Revoked revokeForTenant(Long tenantId) {
    if (tenantId == null || tenantId == PLATFORM_TENANT) {
      return Revoked.NOTHING;
    }
    var now = LocalDateTime.now();
    int revoked = invites.revokeOpenInvitesOfUnit(tenantId, null, OPEN, TENANT_DELETED, now);
    int switchedOff = switchOff(links.findByTenantIdAndStatus(tenantId, active()), now);
    return logged(new Revoked(revoked, switchedOff), "Träger", tenantId);
  }

  /**
   * For a reported deletion; never fails the caller, whose deletion has already happened. A
   * cancelled or partly failed deletion revokes nothing.
   */
  public void onDeletionReported(
      DeletionAuditTargetType targetType, String targetId, DeletionAuditOutcome outcome) {
    if (outcome != DeletionAuditOutcome.SCHEDULED && outcome != DeletionAuditOutcome.EXECUTED) {
      return;
    }
    try {
      Long id = Long.valueOf(targetId);
      if (targetType == DeletionAuditTargetType.AGENCY) {
        revokeForAgency(id);
      } else if (targetType == DeletionAuditTargetType.TENANT) {
        revokeForTenant(id);
      }
    } catch (RuntimeException failure) {
      log.warn(
          "Could not revoke the invitations of deleted {} {}: {}",
          targetType,
          targetId,
          failure.getClass().getSimpleName());
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
