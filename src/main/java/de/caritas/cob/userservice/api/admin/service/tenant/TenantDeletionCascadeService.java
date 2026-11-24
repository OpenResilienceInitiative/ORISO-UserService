package de.caritas.cob.userservice.api.admin.service.tenant;

import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionFailureDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionFailureDTO.KindEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionResultDTO;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Marks everything that belongs to a Träger for deletion (ORISO-Admin#1525, rule 4 of the deletion
 * epic ORISO-Admin#1520: cascade instead of blocking).
 *
 * <p>The order matters. Open invitations go first, so no account appears in the Träger while it is
 * emptied. Then each Beratungsstelle is deleted through AgencyService, which runs the
 * Beratungsstelle cascade (ORISO-Admin#1524): its enquiries are closed, its group chats stopped,
 * and the counsellors and advice seekers it alone held are marked. Whatever no Beratungsstelle
 * covered is marked after that: counsellors and advice seekers without one, then the agency and
 * Träger admins. Each child goes through the same path an admin uses to delete it one by one, so
 * logins stop at once and the nightly jobs remove the rest.
 *
 * <p>Not one transaction: every child is marked on its own, and a child that fails is reported and
 * left for the next call. Every step only picks up what is not marked yet, so TenantService can
 * repeat the call every night until nothing is left and the Träger itself can go.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDeletionCascadeService {

  /** The platform's own tenant; it is never emptied by a Träger deletion. */
  private static final long PLATFORM_TENANT = 0L;

  private final @NonNull DeletedUnitInviteRevocation inviteRevocation;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAdminFacade consultantAdminFacade;
  private final @NonNull UserRepository userRepository;
  private final @NonNull AskerUserAdminFacade askerUserAdminFacade;
  private final @NonNull AdminRepository adminRepository;
  private final @NonNull DeleteAdminService deleteAdminService;
  private final @NonNull AgencyAdminService agencyAdminService;

  /**
   * @param tenantId the Träger being deleted
   * @return what this call marked, and what it could not
   * @throws BadRequestException for the platform's own tenant or an invalid id
   */
  public TenantDeletionResultDTO cascade(Long tenantId) {
    if (tenantId == null || tenantId <= PLATFORM_TENANT) {
      throw new BadRequestException("Only a Träger can be emptied for deletion");
    }
    var failures = new ArrayList<TenantDeletionFailureDTO>();
    var result = new TenantDeletionResultDTO();

    result.invites(revokeInvites(tenantId, failures));
    result.agencies(
        markEach(
            KindEnum.AGENCY,
            () -> agencyAdminService.findActiveAgencyIdsOfTenant(tenantId),
            String::valueOf,
            id -> agencyAdminService.markAgencyForDeletion(Long.valueOf(id)),
            failures));

    result.consultants(
        markEach(
            KindEnum.CONSULTANT,
            () -> consultantRepository.findByTenantIdAndDeleteDateIsNull(tenantId),
            Consultant::getId,
            id -> consultantAdminFacade.markConsultantForDeletion(id, true),
            failures));
    result.adviceSeekers(
        markEach(
            KindEnum.ADVICE_SEEKER,
            () -> userRepository.findActiveAdviceSeekersOfTenant(tenantId),
            User::getUserId,
            askerUserAdminFacade::markAskerForDeletion,
            failures));
    result.agencyAdmins(
        markEach(
            KindEnum.AGENCY_ADMIN,
            () -> adminRepository.findByTenantIdAndType(tenantId, AdminType.AGENCY),
            Admin::getId,
            deleteAdminService::deleteAgencyAdmin,
            failures));
    result.tenantAdmins(
        markEach(
            KindEnum.TENANT_ADMIN,
            () -> adminRepository.findByTenantIdAndType(tenantId, AdminType.TENANT),
            Admin::getId,
            deleteAdminService::deleteTenantAdmin,
            failures));
    if (!failures.isEmpty()) {
      log.warn(
          "Träger {}: {} children could not be marked for deletion; retried on the next run",
          tenantId,
          failures.size());
    }
    return result.failures(failures).complete(failures.isEmpty());
  }

  private long revokeInvites(Long tenantId, List<TenantDeletionFailureDTO> failures) {
    try {
      var revoked = inviteRevocation.revokeForTenant(tenantId);
      return (long) revoked.invites() + revoked.links();
    } catch (RuntimeException e) {
      failures.add(failure(KindEnum.INVITES, null, e));
      return 0L;
    }
  }

  private <T> long markEach(
      KindEnum kind,
      Supplier<List<T>> children,
      Function<T, String> idOf,
      Consumer<String> mark,
      List<TenantDeletionFailureDTO> failures) {
    List<T> found;
    try {
      found = children.get();
    } catch (RuntimeException e) {
      failures.add(failure(kind, null, e));
      return 0L;
    }
    long marked = 0;
    for (T child : found) {
      var id = idOf.apply(child);
      try {
        mark.accept(id);
        marked++;
      } catch (RuntimeException e) {
        // Logged with ids only: the privacy filter would drop a message carrying personal data.
        log.warn("Could not mark {} {} for deletion: {}", kind, id, e.getClass().getSimpleName());
        failures.add(failure(kind, id, e));
      }
    }
    return marked;
  }

  private static TenantDeletionFailureDTO failure(KindEnum kind, String id, RuntimeException e) {
    return new TenantDeletionFailureDTO().kind(kind).id(id).reason(e.getClass().getSimpleName());
  }
}
