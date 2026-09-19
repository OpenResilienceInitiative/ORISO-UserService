package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.adapters.web.dto.UserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.helper.UserHelper;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.GuestJoinAttemptRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.ChatRecoveryEnrollmentPolicyService.RecoveryPolicySnapshot;
import de.caritas.cob.userservice.api.service.session.SessionService;
import de.caritas.cob.userservice.api.service.user.UserService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.util.Objects;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Local user, anonymous session and attempt association are one atomic commit. */
@Service
public class GuestJoinSessionFinalizer {
  private final GuestJoinAttemptRepository attempts;
  private final UserRepository users;
  private final SessionRepository sessions;
  private final UserService userService;
  private final SessionService sessionService;
  private final UserHelper userHelper;
  private final GuestJoinEligibility eligibility;
  private final TransactionTemplate transaction;

  public GuestJoinSessionFinalizer(
      GuestJoinAttemptRepository attempts,
      UserRepository users,
      SessionRepository sessions,
      UserService userService,
      SessionService sessionService,
      UserHelper userHelper,
      GuestJoinEligibility eligibility,
      PlatformTransactionManager manager) {
    this.attempts = attempts;
    this.users = users;
    this.sessions = sessions;
    this.userService = userService;
    this.sessionService = sessionService;
    this.userHelper = userHelper;
    this.eligibility = eligibility;
    this.transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Enrollment inputs must be resolved from server policy, never from the public request. */
  public JoinedSession finish(GuestJoinCapability capability, RecoveryPolicySnapshot policy) {
    var binding =
        attempts
            .findByKeyHash(capability.attemptHash())
            .orElseThrow(() -> new NotFoundException("Guest Join attempt not found"));
    var previous = TenantContext.getCurrentTenantData();
    try {
      TenantContext.setCurrentTenantData(new TenantData(binding.getTenantId(), null));
      return Objects.requireNonNull(
          transaction.execute(
              status -> {
                var attempt =
                    attempts
                        .findByKeyHashForUpdate(capability.attemptHash())
                        .orElseThrow(() -> new NotFoundException("Guest Join attempt not found"));
                eligibility.requireActiveTarget(attempt);
                if (attempt.getPhase() == GuestJoinAttempt.Phase.COMPLETE) {
                  return readActive(attempt);
                }
                if (attempt.getPhase() != GuestJoinAttempt.Phase.MATRIX_READY) {
                  throw new ConflictException("Guest identities are not ready for a session");
                }
                if (policy == null
                    || policy.revision() < 0
                    || !("LOGIN_PASSWORD".equals(policy.mode())
                        || "RECOVERY_KEY".equals(policy.mode()))) {
                  throw new IllegalArgumentException("Valid server recovery policy is required");
                }
                if (users.findById(attempt.getIdentityUserId()).isPresent()) {
                  throw new ConflictException(
                      "Guest identity already has an unrelated local account");
                }
                var user =
                    userService.createNewUser(
                        attempt.getIdentityUserId(),
                        attempt.actualUsername(),
                        userHelper.getDummyEmail(attempt.getIdentityUserId()),
                        attempt.isLanguageFormal(),
                        policy);
                user.setMatrixUserId(attempt.getMatrixUserId());
                user.setTermsAndConditionsConfirmation(null);
                user.setDataPrivacyConfirmation(null);
                var dto =
                    UserDTO.builder()
                        .username(attempt.actualUsername())
                        .consultingType(attempt.getConsultingTypeId().toString())
                        .postcode("00000")
                        .mainTopicId(attempt.getTopicId())
                        .build();
                var session =
                    sessionService.initializeSession(
                        user,
                        dto,
                        false,
                        Session.RegistrationType.ANONYMOUS,
                        Session.SessionStatus.NEW);
                attempt.complete(session.getId());
                return new JoinedSession(
                    session.getId(), user.getUserId(), attempt.actualUsername(), true);
              }));
    } catch (PessimisticLockingFailureException busy) {
      throw new ServiceUnavailableException(
          "Guest Join is being completed; retry the same request");
    } finally {
      // Keep the selected scope through transaction commit/rollback and flush callbacks.
      if (previous == null) TenantContext.clear();
      else TenantContext.setCurrentTenantData(previous);
    }
  }

  private JoinedSession readActive(GuestJoinAttempt attempt) {
    var session =
        sessions
            .findByIdForUpdate(attempt.getSessionId())
            .orElseThrow(() -> new ForbiddenException("Guest session is no longer available"));
    var user = session.getUser();
    if (user == null
        || !attempt.getIdentityUserId().equals(user.getUserId())
        || user.getDeleteDate() != null
        || user.getDeletionLifecycleState() != DeletionLifecycleState.ACTIVE
        || session.getRegistrationType() != Session.RegistrationType.ANONYMOUS
        || (session.getStatus() != Session.SessionStatus.NEW
            && session.getStatus() != Session.SessionStatus.IN_PROGRESS)) {
      throw new ForbiddenException("Guest session has ended");
    }
    return new JoinedSession(session.getId(), user.getUserId(), attempt.actualUsername(), false);
  }

  public record JoinedSession(Long sessionId, String userId, String username, boolean created) {}
}
