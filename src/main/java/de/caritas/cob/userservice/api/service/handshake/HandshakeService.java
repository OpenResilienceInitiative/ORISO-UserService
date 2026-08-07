package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live-Handshake core primitive (ADR-018 §1): two people confirm a privileged action with fresh
 * credentials before it executes. The initiator re-authenticates with password + OTP, the
 * counterpart confirms with their password inside a ~5-minute window; a lapsed window leaves
 * nothing but one audit entry ("session was not established"). Confirmation triggers the purpose's
 * {@link HandshakeCompletionHandler} inside the same transaction.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HandshakeService {

  private static final String EVENT_INITIATED = "INITIATED";
  private static final String EVENT_CONFIRMED = "CONFIRMED";
  private static final String EVENT_CONFIRM_REJECTED = "CONFIRM_REJECTED";
  private static final String EVENT_NOT_ESTABLISHED = "SESSION_NOT_ESTABLISHED";
  private static final String EVENT_CONFIRM_LOCKED = "CONFIRM_LOCKED";

  private final @NonNull HandshakeSessionRepository handshakeSessionRepository;
  private final @NonNull HandshakeAuditEventRepository handshakeAuditEventRepository;
  private final @NonNull KeycloakAuthClient keycloakAuthClient;
  private final @NonNull de.caritas.cob.userservice.api.port.out.IdentityClient identityClient;

  private final @NonNull de.caritas.cob.userservice.api.port.out.IdentitySecondFactor
      identitySecondFactor;

  private final @NonNull de.caritas.cob.userservice.api.port.out.IdentityClientConfig
      identityClientConfig;

  private final @NonNull List<HandshakeCompletionHandler> completionHandlers;

  private final de.caritas.cob.userservice.api.helper.UsernameTranscoder usernameTranscoder =
      new de.caritas.cob.userservice.api.helper.UsernameTranscoder();

  @Value("${handshake.ttl-seconds:300}")
  private long ttlSeconds;

  @Value("${handshake.audit-retention-months:12}")
  private long auditRetentionMonths;

  @Value("${handshake.max-confirm-attempts:5}")
  private int maxConfirmAttempts;

  @Value("${handshake.sweep-batch-size:200}")
  private int sweepBatchSize;

  @Transactional
  public HandshakeItem initiate(AuthenticatedUser initiator, InitiateHandshakeRequest request) {
    var purpose = purposeOf(request.getPurpose());

    if (!purpose.mayInitiate(initiator)) {
      throw new ForbiddenException(
          String.format("User %s may not initiate a %s handshake", initiator.getUserId(), purpose));
    }
    if (request.getCounterpartId() == null || request.getCounterpartId().isBlank()) {
      throw new BadRequestException("Handshake counterpart must be provided");
    }
    if (initiator.getUserId().equals(request.getCounterpartId())) {
      throw new BadRequestException("A handshake requires two distinct people");
    }
    requireActiveSecondFactorForSupportAdmin(initiator);
    if (!keycloakAuthClient.verifyWithOtp(
        initiator.getUsername(), request.getPassword(), request.getOtp())) {
      throw new ForbiddenException(
          String.format(
              "Fresh credential verification failed for handshake initiator %s",
              initiator.getUserId()));
    }

    var now = nowInUtc();
    var session =
        HandshakeSession.builder()
            .id(UUID.randomUUID().toString())
            .purpose(purpose)
            .initiatorId(initiator.getUserId())
            .counterpartId(request.getCounterpartId())
            .status(HandshakeStatus.PENDING)
            .createDate(now)
            .expiryDate(now.plusSeconds(ttlSeconds))
            .tenantId(initiator.getTenantId())
            .build();
    session = handshakeSessionRepository.save(session);
    audit(session, EVENT_INITIATED, initiator.getUserId());

    return HandshakeItem.of(session);
  }

  @Transactional
  public HandshakeItem confirm(AuthenticatedUser counterpart, String handshakeId, String password) {
    var session =
        handshakeSessionRepository
            .findById(handshakeId)
            .orElseThrow(
                () -> new BadRequestException(String.format("Unknown handshake %s", handshakeId)));

    if (session.getStatus() != HandshakeStatus.PENDING) {
      throw new BadRequestException(String.format("Handshake %s is not pending", handshakeId));
    }
    if (session.getExpiryDate().isBefore(nowInUtc())) {
      expire(session);
      throw new BadRequestException(String.format("Handshake %s has expired", handshakeId));
    }
    if (!session.getCounterpartId().equals(counterpart.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "User %s is not the counterpart of handshake %s",
              counterpart.getUserId(), handshakeId));
    }
    enforceTenantPolicy(session, counterpart);
    if (!session.getPurpose().mayConfirm(counterpart)) {
      audit(session, EVENT_CONFIRM_REJECTED, counterpart.getUserId());
      throw new ForbiddenException(
          String.format(
              "User %s may not confirm a %s handshake",
              counterpart.getUserId(), session.getPurpose()));
    }
    if (!keycloakAuthClient.verifyIgnoringOtp(counterpart.getUsername(), password)) {
      registerFailedConfirmAttempt(session, counterpart);
      throw new ForbiddenException(
          String.format(
              "Fresh credential verification failed for handshake counterpart %s",
              counterpart.getUserId()));
    }

    session.setStatus(HandshakeStatus.CONFIRMED);
    session.setConfirmedDate(nowInUtc());
    try {
      handshakeSessionRepository.save(session);
    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
      throw new BadRequestException(
          String.format("Handshake %s was modified concurrently", handshakeId));
    }
    audit(session, EVENT_CONFIRMED, counterpart.getUserId());

    for (var handler : completionHandlers) {
      if (handler.supports(session.getPurpose())) {
        handler.onConfirmed(session);
      }
    }

    return HandshakeItem.of(session);
  }

  @Transactional(readOnly = true)
  public List<HandshakeItem> pendingForCounterpart(AuthenticatedUser counterpart) {
    return handshakeSessionRepository
        .findAllByCounterpartIdAndStatusAndExpiryDateAfter(
            counterpart.getUserId(), HandshakeStatus.PENDING, nowInUtc())
        .stream()
        .map(HandshakeItem::of)
        .toList();
  }

  /** Lapse sweep — a quiet no-answer leaves exactly one audit entry per session. */
  @Scheduled(fixedDelayString = "${handshake.sweep-delay-ms:60000}")
  @Transactional
  public void sweepExpired() {
    handshakeSessionRepository
        .findAllByStatusAndExpiryDateBefore(
            HandshakeStatus.PENDING,
            nowInUtc(),
            org.springframework.data.domain.PageRequest.of(0, sweepBatchSize))
        .forEach(this::expire);
  }

  /** ADR-018 §3: audit retention 12 months, automatic deletion. */
  @Scheduled(cron = "${handshake.audit-purge-cron:0 30 3 * * *}")
  @Transactional
  public void purgeOldAuditEvents() {
    handshakeAuditEventRepository.deleteAllByCreateDateBefore(
        nowInUtc().minusMonths(auditRetentionMonths));
  }

  /**
   * Tenant isolation: SUPPORT_ACCESS crosses tenants by design; every other purpose requires the
   * confirming counterpart to belong to the session (initiator) tenant or be platform-scoped.
   */
  private void enforceTenantPolicy(HandshakeSession session, AuthenticatedUser counterpart) {
    if (!session.getPurpose().isTenantScoped()) {
      return;
    }
    var counterpartTenant = counterpart.getTenantId();
    var sessionTenant = session.getTenantId();
    var platformScoped = counterpartTenant != null && counterpartTenant == 0L;
    if (sessionTenant != null && !platformScoped && !sessionTenant.equals(counterpartTenant)) {
      audit(session, EVENT_CONFIRM_REJECTED, counterpart.getUserId());
      throw new ForbiddenException(
          String.format(
              "User %s belongs to another tenant than handshake %s",
              counterpart.getUserId(), session.getId()));
    }
  }

  /** Durable brute-force guard: at the attempt limit the session locks terminally. */
  private void registerFailedConfirmAttempt(
      HandshakeSession session, AuthenticatedUser counterpart) {
    session.setConfirmAttempts(session.getConfirmAttempts() + 1);
    if (session.getConfirmAttempts() >= maxConfirmAttempts) {
      session.setStatus(HandshakeStatus.EXPIRED);
      handshakeSessionRepository.save(session);
      audit(session, EVENT_CONFIRM_LOCKED, counterpart.getUserId());
      return;
    }
    handshakeSessionRepository.save(session);
    audit(session, EVENT_CONFIRM_REJECTED, counterpart.getUserId());
  }

  /**
   * ADR-018: a Global Support Admin cannot become active without completed 2FA enrollment. Fails
   * closed — an unreachable OTP state never lets a support admin through.
   */
  private void requireActiveSecondFactorForSupportAdmin(AuthenticatedUser initiator) {
    var roles = initiator.getRoles();
    if (roles == null
        || !roles.contains(
            de.caritas.cob.userservice.api.config.auth.UserRole.GLOBAL_SUPPORT_ADMIN.getValue())) {
      return;
    }
    // A role policy that denies OTP for support admins makes this gate unsatisfiable —
    // the same deadlock class fixed for platform admins on pre-dev (adadd471). Fail
    // closed (privileged access without 2FA is never granted), but name it as a
    // deployment misconfiguration instead of telling the admin to do the impossible.
    if (!identityClientConfig.isOtpAllowed(roles)) {
      throw new ForbiddenException(
          String.format(
              "Support access is unavailable: the OTP policy denies OTP for the support-admin role,"
                  + " so support admin %s cannot satisfy the mandatory 2FA gate."
                  + " This is a deployment configuration error (identity.otp-allowed-*).",
              initiator.getUserId()));
    }
    boolean otpActive;
    try {
      var otpInfo =
          identitySecondFactor.getOtpCredential(
              usernameTranscoder.encodeUsername(initiator.getUsername()));
      otpActive = otpInfo != null && Boolean.TRUE.equals(otpInfo.setup());
    } catch (Exception e) {
      log.warn(
          "Could not read OTP state for support admin {}; failing closed",
          initiator.getUserId(),
          e);
      otpActive = false;
    }
    if (!otpActive) {
      throw new ForbiddenException(
          String.format(
              "Support admin %s must complete 2FA enrollment before initiating a handshake",
              initiator.getUserId()));
    }
  }

  private void expire(HandshakeSession session) {
    session.setStatus(HandshakeStatus.EXPIRED);
    handshakeSessionRepository.save(session);
    audit(session, EVENT_NOT_ESTABLISHED, null);
  }

  private void audit(HandshakeSession session, String event, String actorId) {
    handshakeAuditEventRepository.save(
        HandshakeAuditEvent.builder()
            .handshakeId(session.getId())
            .purpose(session.getPurpose().name())
            .event(event)
            .actorId(actorId)
            .counterpartId(session.getCounterpartId())
            .tenantId(session.getTenantId())
            .createDate(nowInUtc())
            .build());
  }

  private HandshakePurpose purposeOf(String value) {
    try {
      return HandshakePurpose.valueOf(value);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BadRequestException(String.format("Unknown handshake purpose %s", value));
    }
  }

  @Getter
  @Setter
  public static class InitiateHandshakeRequest {
    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 40)
    private String purpose;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 36)
    private String counterpartId;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 255)
    private String password;

    @jakarta.validation.constraints.Size(max = 16)
    private String otp;
  }

  @Getter
  public static class HandshakeItem {
    private String id;
    private String purpose;
    private String initiatorId;
    private String counterpartId;
    private String status;
    private LocalDateTime expiryDate;

    static HandshakeItem of(HandshakeSession session) {
      var item = new HandshakeItem();
      item.id = session.getId();
      item.purpose = session.getPurpose().name();
      item.initiatorId = session.getInitiatorId();
      item.counterpartId = session.getCounterpartId();
      item.status = session.getStatus().name();
      item.expiryDate = session.getExpiryDate();
      return item;
    }
  }
}
