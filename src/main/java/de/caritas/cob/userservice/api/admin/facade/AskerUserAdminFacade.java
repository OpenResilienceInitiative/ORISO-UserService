package de.caritas.cob.userservice.api.admin.facade;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AskerFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerReactivationRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AskerSearchResultDTO;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationRepairService;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationSagaStore;
import de.caritas.cob.userservice.api.admin.service.IdentityReactivationUnmutatedException;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.asker.AskerAdminSearchService;
import de.caritas.cob.userservice.api.admin.service.asker.GroupChatSystemAccounts;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.IdentityDeactivator;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.delete.service.AskerEnquiryCloser;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Wrapper facade to provide admin operations on asker accounts. */
@Service
@RequiredArgsConstructor
public class AskerUserAdminFacade {
  private final @NonNull de.caritas.cob.userservice.api.service.livechat.LiveChatAccountClaimService
      liveChatClaims;

  private final @NonNull IdentityDeactivator identityDeactivator;
  private final @NonNull IdentityReactivationSagaStore identityReactivationSagaStore;
  private final @NonNull IdentityReactivationRepairService identityReactivationRepairService;
  private final @NonNull UserService userService;
  private final @NonNull UsernameTranscoder usernameTranscoder;
  private final @NonNull DeletionLifecycleService deletionLifecycleService;
  private final @NonNull AdminScope adminScope;
  private final @NonNull AskerAdminSearchService askerAdminSearchService;
  private final @NonNull AskerEnquiryCloser askerEnquiryCloser;

  /** Advice seekers within the caller's reach, including those already marked for deletion. */
  public AskerSearchResultDTO findAskers(Integer page, Integer perPage, AskerFilter filter) {
    return askerAdminSearchService.findAskers(page, perPage, filter);
  }

  /**
   * Marks the asker with the given id for deletion.
   *
   * @param userId the id of the asker
   */
  public void markAskerForDeletion(String userId) {
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));

    if (nonNull(user.getDeleteDate())) {
      // Its own X-Reason, so a caller can tell it from other 409s (e.g. a live-chat transition).
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.ASKER_ALREADY_MARKED_FOR_DELETION, HttpStatus.CONFLICT);
    }
    assertNotTechnicalAccount(user);

    liveChatClaims.claimIntentionalDeletion(userId);
    this.identityDeactivator.deactivateUser(userId);
    this.deletionLifecycleService.beginUserDeletion(user, null);
    this.userService.saveUser(user);
    this.askerEnquiryCloser.closeOpenEnquiries(user);
  }

  /** A group-chat system account owns its Träger's group chats and only goes with the Träger. */
  private static void assertNotTechnicalAccount(User user) {
    if (GroupChatSystemAccounts.isTechnical(user.getUserId())) {
      throw new CustomValidationHttpStatusException(
          HttpStatusExceptionReason.TECHNICAL_GROUP_CHAT_ACCOUNT, HttpStatus.CONFLICT);
    }
  }

  public void pauseAskerDeletion(String userId, String reason, Integer months, String pausedBy) {
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .or(() -> userService.findDeletedById(userId))
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));
    if (user.getDeleteDate() == null) {
      throw new ConflictException(
          String.format("Asker with id %s is not marked for deletion", userId));
    }
    deletionLifecycleService.pauseUserDeletion(user, reason, months, pausedBy);
    userService.saveUser(user);
  }

  /**
   * Reactivates one exact soft-deleted asker identity for a privileged operator.
   *
   * <p>Username alone is deliberately insufficient because historic data permits duplicates.
   */
  public void reactivateAsker(AskerReactivationRequestDTO request) {
    assertCallerMayAccessTenant(request.getTenantId());
    var operation =
        identityReactivationSagaStore.begin(
            request.getUsername(), request.getEmail(), request.getTenantId());
    try {
      identityReactivationSagaStore.reactivateAndCommit(operation, request.getPassword());
    } catch (IdentityReactivationUnmutatedException unmutatedFailure) {
      throw unmutatedFailure.originalFailure();
    } catch (RuntimeException reactivationFailure) {
      identityReactivationRepairService.compensate(operation, reactivationFailure);
      throw reactivationFailure;
    }
  }

  private void assertCallerMayAccessTenant(Long requestedTenantId) {
    Long callerTenantId = TenantContext.getCurrentTenant();
    if (callerTenantId == null
        || (!TenantContext.TECHNICAL_TENANT_ID.equals(callerTenantId)
            && !callerTenantId.equals(requestedTenantId))) {
      throw new AccessDeniedException("Caller is not authorized for the requested tenant");
    }
  }

  public AskerResponseDTO getAsker(String userId) {
    adminScope.assertMay(Target.adviceSeeker(userId));
    User user =
        userService
            .getUser(userId)
            .orElseThrow(() -> new NotFoundException("Asker with id %s does not exist", userId));
    AskerResponseDTO asker = new AskerResponseDTO();
    asker.setId(user.getUserId());
    asker.setUsername(this.usernameTranscoder.decodeUsername(user.getUsername()));
    asker.setEmail(user.getEmail());
    return asker;
  }
}
