package de.caritas.cob.userservice.api.service.handshake;

import static de.caritas.cob.userservice.api.helper.CustomLocalDateTime.nowInUtc;

import de.caritas.cob.userservice.api.adapters.keycloak.KeycloakAuthClient;
import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.GoneException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.HandshakeAuditEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent;
import de.caritas.cob.userservice.api.model.HandshakeOutboxEvent.OutboxStatus;
import de.caritas.cob.userservice.api.model.HandshakeSession;
import de.caritas.cob.userservice.api.model.HandshakeSession.HandshakeStatus;
import de.caritas.cob.userservice.api.model.SupportAccessSession.SupportAccessSessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeAuditEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeOutboxEventRepository;
import de.caritas.cob.userservice.api.port.out.HandshakeSessionRepository;
import de.caritas.cob.userservice.api.port.out.SupportAccessSessionRepository;
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
 * credentials before it executes. The Global Support Admin re-authenticates with password + OTP for
 * one consultant at one concrete agency; that consultant confirms with their password inside a
 * five-minute window.
 *
 * <p>Two properties carry the security weight. Confirmation is a conditional update, so exactly one
 * of two concurrent confirmations may go on to create a session. And a lapsed handshake leaves no
 * operational row at all — only one {@code SESSION_NOT_ESTABLISHED} audit entry.
 *
 * <p>Version 1 exposes only {@link HandshakePurpose#SUPPORT_ACCESS}; recovery and identity grants
 * reuse this core later but have no reachable endpoint yet.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HandshakeService {

  private static final String EVENT_INITIATED = "INITIATED";
  private static final String EVENT_CONFIRMED = "CONFIRMED";
  private static final String EVENT_DECLINED = "DECLINED";
  private static final String EVENT_CONFIRM_REJECTED = "CONFIRM_REJECTED";
  private static final String EVENT_NOT_ESTABLISHED = "SESSION_NOT_ESTABLISHED";

  private final @NonNull HandshakeSessionRepository handshakeSessionRepository;
  private final @NonNull HandshakeAuditEventRepository handshakeAuditEventRepository;
  private final @NonNull KeycloakAuthClient keycloakAuthClient;
  private final @NonNull HandshakeOutboxEventRepository handshakeOutboxEventRepository;
  private final @NonNull GlobalSupportAdminUserService globalSupportAdminUserService;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull SupportAccessSessionRepository supportAccessSessionRepository;

  @Value("${handshake.ttl-seconds:300}")
  private long ttlSeconds;

  @Value("${handshake.audit-retention-months:12}")
  private long auditRetentionMonths;

  @Value("${handshake.max-confirm-attempts:5}")
  private int maxConfirmAttempts;

  @Value("${support-access.enabled:false}")
  private boolean supportAccessEnabled;

  @Transactional
  public HandshakeItem initiate(AuthenticatedUser initiator, InitiateHandshakeRequest request) {
    if (!supportAccessEnabled) {
      throw new ForbiddenException("Support access is disabled");
    }
    var purpose = purposeOf(request.getPurpose());

    if (!purpose.mayInitiate(initiator)) {
      throw new ForbiddenException(
          String.format("User %s may not initiate a %s handshake", initiator.getUserId(), purpose));
    }
    if (request.getConsultantId() == null || request.getConsultantId().isBlank()) {
      throw new BadRequestException("Handshake consultant must be provided");
    }
    if (request.getAgencyId() == null) {
      throw new BadRequestException("Handshake agency must be provided");
    }
    if (initiator.getUserId().equals(request.getConsultantId())) {
      throw new BadRequestException("A handshake requires two distinct people");
    }
    globalSupportAdminUserService.requireOperationalSupportAdmin();
    if (!keycloakAuthClient.verifyWithOtp(
        initiator.getUsername(), request.getPassword(), request.getOtp())) {
      throw new ForbiddenException(
          String.format(
              "Fresh credential verification failed for handshake initiator %s",
              initiator.getUserId()));
    }
    var consultant =
        consultantRepository
            .findActiveByIdForUpdate(request.getConsultantId())
            .orElseThrow(() -> new BadRequestException("Handshake consultant not found"));
    // Scope comes from the persisted relation, never from the request body or the token.
    if (!consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
        consultant.getId(), request.getAgencyId())) {
      throw new BadRequestException("Consultant is not assigned to the requested agency");
    }
    if (handshakeSessionRepository
        .existsByInitiatorIdAndCounterpartIdAndAgencyIdAndPurposeAndStatusIn(
            initiator.getUserId(),
            consultant.getId(),
            request.getAgencyId(),
            purpose,
            List.of(HandshakeStatus.PENDING, HandshakeStatus.CONFIRMED))) {
      throw new ConflictException("A support request for this consultant is already open");
    }
    if (supportAccessSessionRepository.existsBySupportAdminIdAndConsultantIdAndStatusIn(
        initiator.getUserId(),
        consultant.getId(),
        List.of(
            SupportAccessSessionStatus.PROVISIONING,
            SupportAccessSessionStatus.ACTIVE,
            SupportAccessSessionStatus.REVOCATION_PENDING))) {
      throw new ConflictException("A support session is already running for this consultant");
    }

    var now = nowInUtc();
    var session =
        HandshakeSession.builder()
            .id(UUID.randomUUID().toString())
            .purpose(purpose)
            .initiatorId(initiator.getUserId())
            .counterpartId(consultant.getId())
            .status(HandshakeStatus.PENDING)
            .createDate(now)
            .expiryDate(now.plusSeconds(ttlSeconds))
            .tenantId(consultant.getTenantId())
            .agencyId(request.getAgencyId())
            .build();
    session = handshakeSessionRepository.save(session);
    audit(session, EVENT_INITIATED, initiator.getUserId());

    return HandshakeItem.of(session);
  }

  /**
   * Confirmation by the addressed consultant. The transition is a conditional update: only the
   * caller that actually changed the row may create the support session and its outbox job, which
   * is what keeps two simultaneous confirmations from producing two sessions.
   */
  @Transactional(
      noRollbackFor = {ForbiddenException.class, BadRequestException.class, GoneException.class})
  public HandshakeItem confirm(AuthenticatedUser counterpart, String handshakeId, String password) {
    var session = requirePendingFor(counterpart, handshakeId);

    if (!keycloakAuthClient.verifyIgnoringOtp(counterpart.getUsername(), password)) {
      registerFailedAttempt(session, counterpart);
      throw new ForbiddenException(
          String.format(
              "Fresh credential verification failed for handshake counterpart %s",
              counterpart.getUserId()));
    }

    var now = nowInUtc();
    if (handshakeSessionRepository.confirmIfStillPending(session.getId(), now) != 1) {
      // Someone else already decided this handshake between our read and our write.
      throw new ConflictException(
          String.format("Handshake %s was already decided", session.getId()));
    }
    var confirmed =
        handshakeSessionRepository
            .findById(session.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Confirmed handshake %s vanished".formatted(session.getId())));
    handshakeOutboxEventRepository.save(
        HandshakeOutboxEvent.builder()
            .aggregateId(confirmed.getId())
            .eventType(SupportAccessJobHandler.PROVISION_ROOM)
            .status(OutboxStatus.PENDING)
            .attempts(0)
            .createDate(now)
            .nextAttemptDate(now)
            .build());
    audit(confirmed, EVENT_CONFIRMED, counterpart.getUserId());

    return HandshakeItem.of(confirmed);
  }

  /** Explicit refusal. Declining is a decision and is audited; ignoring the popup is not. */
  @Transactional
  public HandshakeItem decline(AuthenticatedUser counterpart, String handshakeId) {
    var session = requirePendingFor(counterpart, handshakeId);
    session.setStatus(HandshakeStatus.DECLINED);
    var declined = handshakeSessionRepository.saveAndFlush(session);
    audit(declined, EVENT_DECLINED, counterpart.getUserId());
    return HandshakeItem.of(declined);
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

  /** Lapse sweep — a quiet no-answer leaves exactly one audit entry and no operational row. */
  @Scheduled(fixedDelayString = "${handshake.sweep-delay-ms:60000}")
  @Transactional
  public void sweepExpired() {
    handshakeSessionRepository
        .findAllByStatusAndExpiryDateBefore(HandshakeStatus.PENDING, nowInUtc())
        .forEach(this::lapse);
  }

  /** ADR-018 §3: audit retention 12 months, automatic deletion. */
  @Scheduled(cron = "${handshake.audit-purge-cron:0 30 3 * * *}")
  @Transactional
  public void purgeOldAuditEvents() {
    handshakeAuditEventRepository.deleteAllByCreateDateBefore(
        nowInUtc().minusMonths(auditRetentionMonths));
  }

  private HandshakeSession requirePendingFor(AuthenticatedUser counterpart, String handshakeId) {
    var session =
        handshakeSessionRepository
            .findById(handshakeId)
            .orElseThrow(
                () -> new BadRequestException(String.format("Unknown handshake %s", handshakeId)));

    // Ownership before state, so a stranger cannot probe which requests exist.
    if (!session.getCounterpartId().equals(counterpart.getUserId())) {
      throw new ForbiddenException(
          String.format(
              "User %s is not the counterpart of handshake %s",
              counterpart.getUserId(), handshakeId));
    }
    if (!session.getPurpose().mayConfirm(counterpart)) {
      throw new ForbiddenException(
          String.format(
              "User %s may not decide a %s handshake",
              counterpart.getUserId(), session.getPurpose()));
    }
    if (session.getStatus() != HandshakeStatus.PENDING) {
      throw new ConflictException(String.format("Handshake %s was already decided", handshakeId));
    }
    if (!session.getExpiryDate().isAfter(nowInUtc())) {
      lapse(session);
      throw new GoneException(String.format("Handshake %s has expired", handshakeId));
    }
    return session;
  }

  /**
   * A wrong password is counted on the live row. The configured attempt is terminal: the row is
   * removed, so the request can never be confirmed afterwards.
   */
  private void registerFailedAttempt(HandshakeSession session, AuthenticatedUser counterpart) {
    session.setConfirmAttempts(session.getConfirmAttempts() + 1);
    if (session.getConfirmAttempts() >= maxConfirmAttempts) {
      audit(session, EVENT_CONFIRM_REJECTED, counterpart.getUserId());
      lapse(session);
      return;
    }
    handshakeSessionRepository.saveAndFlush(session);
    audit(session, EVENT_CONFIRM_REJECTED, counterpart.getUserId());
  }

  /** Removes the operational row and leaves exactly one audit entry behind. */
  private void lapse(HandshakeSession session) {
    audit(session, EVENT_NOT_ESTABLISHED, null);
    handshakeSessionRepository.delete(session);
    handshakeSessionRepository.flush();
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
            .agencyId(session.getAgencyId())
            .createDate(nowInUtc())
            .build());
  }

  private HandshakePurpose purposeOf(String value) {
    // Version 1 offers SUPPORT_ACCESS only; anything else is rejected before any credential work.
    if (value == null || value.isBlank()) {
      return HandshakePurpose.SUPPORT_ACCESS;
    }
    try {
      var purpose = HandshakePurpose.valueOf(value);
      if (!purpose.isPubliclyOffered()) {
        throw new BadRequestException(String.format("Handshake purpose %s is not offered", value));
      }
      return purpose;
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BadRequestException(String.format("Unknown handshake purpose %s", value));
    }
  }

  @Getter
  @Setter
  public static class InitiateHandshakeRequest {
    @jakarta.validation.constraints.Size(max = 40)
    private String purpose;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 36)
    private String consultantId;

    @jakarta.validation.constraints.NotNull private Long agencyId;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 255)
    private String password;

    @jakarta.validation.constraints.NotBlank
    @jakarta.validation.constraints.Size(max = 16)
    private String otp;

    /** Never let credentials reach a log line, however this object is rendered. */
    @Override
    public String toString() {
      return "InitiateHandshakeRequest{purpose=%s, consultantId=%s, agencyId=%s, password=[REDACTED], otp=[REDACTED]}"
          .formatted(purpose, consultantId, agencyId);
    }
  }

  @Getter
  public static class HandshakeItem {
    private String id;
    private String purpose;
    private String initiatorId;
    private String counterpartId;
    private Long agencyId;
    private String status;
    private LocalDateTime expiryDate;

    static HandshakeItem of(HandshakeSession session) {
      var item = new HandshakeItem();
      item.id = session.getId();
      item.purpose = session.getPurpose().name();
      item.initiatorId = session.getInitiatorId();
      item.counterpartId = session.getCounterpartId();
      item.agencyId = session.getAgencyId();
      item.status = session.getStatus().name();
      item.expiryDate = session.getExpiryDate();
      return item;
    }
  }
}
