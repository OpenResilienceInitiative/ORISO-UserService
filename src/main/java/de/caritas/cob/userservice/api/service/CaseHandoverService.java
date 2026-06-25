package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CaseHandoverService {

  private static final String POLICY_AUTHORITY = "platform-admin-default-case-handover-policy";
  private static final String OUTCOME_ACTIVE_OWNER = "ACTIVE_OWNER";
  private static final String OUTCOME_ACCESS_GRANTED = "ACCESS_GRANTED";
  private static final String OUTCOME_ACCESS_DENIED = "ACCESS_DENIED";
  private static final String OUTCOME_PENDING_CLIENT_CONSENT = "PENDING_CLIENT_CONSENT";
  private static final String OUTCOME_CLIENT_CONSENT_DECLINED = "CLIENT_CONSENT_DECLINED";
  private static final String OUTCOME_ALREADY_ANSWERED = "ALREADY_ANSWERED";
  private static final String OUTCOME_NOT_REQUESTED = "NOT_REQUESTED";

  private static final List<CaseHandoverReason> DEFAULT_REASONS =
      List.of(
          CaseHandoverReason.builder()
              .code("COUNSELLOR_ASKED_FOR_ADVICE")
              .label("Counsellor asked for advice")
              .clientConsentRequired(true)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(10)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_ON_HOLIDAY")
              .label("Counsellor is on holiday")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(20)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("OTHER_EMERGENCY")
              .label("Other emergency")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(30)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_IS_ILL")
              .label("Counsellor is ill")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(40)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_LEFT")
              .label("Counsellor does not work here anymore")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(50)
              .policyAuthority(POLICY_AUTHORITY)
              .build());

  private final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;
  private final @NonNull CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull EventNotificationService eventNotificationService;

  public List<CaseHandoverReason> listReasons() {
    List<CaseHandoverReasonPolicy> policies =
        caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc();
    return policies.isEmpty()
        ? DEFAULT_REASONS
        : policies.stream().map(this::toReason).collect(Collectors.toList());
  }

  @Transactional(readOnly = true)
  public List<CaseHandoverReason> listReasonPolicies() {
    List<CaseHandoverReasonPolicy> policies =
        caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc();
    return policies.isEmpty()
        ? DEFAULT_REASONS
        : policies.stream().map(this::toReason).collect(Collectors.toList());
  }

  @Transactional
  public List<CaseHandoverReason> updateReasonPolicies(List<CaseHandoverReason> requestedReasons) {
    if (requestedReasons == null || requestedReasons.isEmpty()) {
      throw new BadRequestException("At least one handover reason policy is required");
    }

    Map<String, CaseHandoverReasonPolicy> existingPolicies =
        caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
            .collect(Collectors.toMap(CaseHandoverReasonPolicy::getCode, Function.identity()));
    LocalDateTime now = LocalDateTime.now();
    List<CaseHandoverReasonPolicy> policiesToSave =
        requestedReasons.stream()
            .map(reason -> toPolicy(reason, existingPolicies.get(reason.getCode()), now))
            .collect(Collectors.toList());

    caseHandoverReasonPolicyRepository.saveAll(policiesToSave);
    return listReasonPolicies();
  }

  @Transactional(readOnly = true)
  public CaseHandoverStatus getStatus(Long sessionId) {
    Consultant requester = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyEligibleSameAgency(session, requester);

    if (isActiveOwner(session, requester)) {
      return CaseHandoverStatus.builder()
          .sessionId(sessionId)
          .status(Status.GRANTED.name())
          .canViewContent(true)
          .clientConsentRequired(false)
          .policyAuthority(POLICY_AUTHORITY)
          .auditOutcome(OUTCOME_ACTIVE_OWNER)
          .build();
    }

    return latestFor(sessionId, requester)
        .map(this::toStatus)
        .orElse(
            CaseHandoverStatus.builder()
                .sessionId(sessionId)
                .status(OUTCOME_NOT_REQUESTED)
                .canViewContent(false)
                .clientConsentRequired(false)
                .policyAuthority(POLICY_AUTHORITY)
                .auditOutcome(OUTCOME_NOT_REQUESTED)
                .build());
  }

  @Transactional
  public CaseHandoverStatus requestAccess(Long sessionId, String reasonCode, String explanation) {
    Consultant requester = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyEligibleSameAgency(session, requester);

    if (isActiveOwner(session, requester)) {
      return getStatus(sessionId);
    }

    String normalizedExplanation = normalizeExplanation(explanation);
    CaseHandoverReason reason = findReason(reasonCode);

    Optional<CaseHandoverRequest> existing = latestFor(sessionId, requester);
    if (existing.filter(this::isOpenOrGranted).isPresent()) {
      return toStatus(existing.get());
    }

    LocalDateTime now = LocalDateTime.now();
    if (latestGrantedForOtherRequester(sessionId, requester).isPresent()) {
      return denyRequest(
          session, requester, reason, normalizedExplanation, OUTCOME_ALREADY_ANSWERED, now);
    }
    if (!isAccessAllowed(reason)) {
      return denyRequest(
          session, requester, reason, normalizedExplanation, OUTCOME_ACCESS_DENIED, now);
    }

    Status status =
        reason.isClientConsentRequired() ? Status.PENDING_CLIENT_CONSENT : Status.GRANTED;
    String auditOutcome =
        reason.isClientConsentRequired() ? OUTCOME_PENDING_CLIENT_CONSENT : OUTCOME_ACCESS_GRANTED;

    CaseHandoverRequest request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(session.getConsultant())
            .reasonCode(reason.getCode())
            .reasonLabel(reason.getLabel())
            .explanation(normalizedExplanation)
            .status(status)
            .clientConsentRequired(reason.isClientConsentRequired())
            .policyAuthority(reason.getPolicyAuthority())
            .auditOutcome(auditOutcome)
            .createdAt(now)
            .resolvedAt(status == Status.GRANTED ? now : null)
            .tenantId(session.getTenantId())
            .build();

    CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);

    if (status == Status.GRANTED) {
      session.setConsultant(requester);
      session.setUpdateDate(now);
      sessionRepository.save(session);
      notifyGranted(saved);
    } else {
      notifyPendingConsent(saved);
    }

    return toStatus(saved);
  }

  @Transactional
  public CaseHandoverStatus resolveClientConsent(Long sessionId, Long requestId, boolean approved) {
    User user = userAccountService.retrieveValidatedUser();
    CaseHandoverRequest request =
        caseHandoverRequestRepository
            .findByIdAndSessionId(requestId, sessionId)
            .orElseThrow(() -> new NotFoundException("Case handover request not found"));
    Session session = request.getSession();

    if (session.getUser() == null || !user.getUserId().equals(session.getUser().getUserId())) {
      throw new ForbiddenException("Current user is not allowed to decide this request");
    }

    if (request.getStatus() != Status.PENDING_CLIENT_CONSENT) {
      return toStatus(request);
    }

    LocalDateTime now = LocalDateTime.now();
    request.setResolvedAt(now);
    if (approved) {
      if (hasAlreadyGrantedOrTakenOver(session, request)) {
        request.setStatus(Status.DENIED);
        request.setAuditOutcome(OUTCOME_ALREADY_ANSWERED);
        CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
        return toStatus(saved);
      }

      request.setStatus(Status.GRANTED);
      request.setAuditOutcome(OUTCOME_ACCESS_GRANTED);
      session.setConsultant(request.getRequesterConsultant());
      session.setUpdateDate(now);
      sessionRepository.save(session);
      CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
      notifyGranted(saved);
      return toStatus(saved);
    }

    request.setStatus(Status.CLIENT_CONSENT_DECLINED);
    request.setAuditOutcome(OUTCOME_CLIENT_CONSENT_DECLINED);
    CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
    notifyConsentDeclined(saved);
    return toStatus(saved);
  }

  private Consultant retrieveCurrentConsultant() {
    Consultant consultant = userAccountService.retrieveValidatedConsultant();
    if (consultant == null) {
      throw new ForbiddenException("Current user is not a consultant");
    }
    return consultant;
  }

  private Session getSession(Long sessionId) {
    return sessionRepository
        .findById(sessionId)
        .orElseThrow(() -> new NotFoundException("Session not found: " + sessionId));
  }

  private void verifyEligibleSameAgency(Session session, Consultant consultant) {
    if (isActiveOwner(session, consultant)) {
      return;
    }
    if (session.getAgencyId() == null
        || !consultantAgencyIds(consultant).contains(session.getAgencyId())) {
      throw new ForbiddenException("Consultant is not eligible for this case");
    }
  }

  private Set<Long> consultantAgencyIds(Consultant consultant) {
    Set<ConsultantAgency> loadedAgencies = consultant.getConsultantAgencies();
    if (loadedAgencies != null && !loadedAgencies.isEmpty()) {
      return loadedAgencies.stream().map(ConsultantAgency::getAgencyId).collect(Collectors.toSet());
    }
    return consultantAgencyRepository
        .findByConsultantIdAndDeleteDateIsNull(consultant.getId())
        .stream()
        .map(ConsultantAgency::getAgencyId)
        .collect(Collectors.toSet());
  }

  private boolean isActiveOwner(Session session, Consultant consultant) {
    return session.getConsultant() != null
        && consultant != null
        && session.getConsultant().getId().equals(consultant.getId());
  }

  private Optional<CaseHandoverRequest> latestFor(Long sessionId, Consultant requester) {
    return caseHandoverRequestRepository
        .findBySessionIdAndRequesterConsultantIdOrderByCreatedAtDesc(sessionId, requester.getId())
        .stream()
        .findFirst();
  }

  private boolean isOpenOrGranted(CaseHandoverRequest request) {
    return List.of(Status.PENDING, Status.PENDING_CLIENT_CONSENT, Status.GRANTED)
        .contains(request.getStatus());
  }

  private Optional<CaseHandoverRequest> latestGrantedForOtherRequester(
      Long sessionId, Consultant requester) {
    return caseHandoverRequestRepository
        .findBySessionIdAndStatusOrderByCreatedAtDesc(sessionId, Status.GRANTED)
        .stream()
        .filter(
            request ->
                request.getRequesterConsultant() != null
                    && requester != null
                    && requester.getId() != null
                    && !requester.getId().equals(request.getRequesterConsultant().getId()))
        .findFirst();
  }

  private boolean hasAlreadyGrantedOrTakenOver(Session session, CaseHandoverRequest request) {
    return latestGrantedForOtherRequester(session.getId(), request.getRequesterConsultant())
            .isPresent()
        || isTakenOverByAnotherCounsellor(session, request);
  }

  private boolean isTakenOverByAnotherCounsellor(Session session, CaseHandoverRequest request) {
    Consultant currentConsultant = session.getConsultant();
    Consultant requester = request.getRequesterConsultant();
    if (currentConsultant == null
        || requester == null
        || currentConsultant.getId() == null
        || requester.getId() == null) {
      return false;
    }
    if (currentConsultant.getId().equals(requester.getId())) {
      return false;
    }

    Consultant previousConsultant = request.getPreviousConsultant();
    return previousConsultant == null
        || previousConsultant.getId() == null
        || !currentConsultant.getId().equals(previousConsultant.getId());
  }

  private CaseHandoverReason findReason(String reasonCode) {
    String normalized = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
    return listReasons().stream()
        .filter(reason -> reason.getCode().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown handover reason"));
  }

  private CaseHandoverReason toReason(CaseHandoverReasonPolicy policy) {
    return CaseHandoverReason.builder()
        .code(policy.getCode())
        .label(policy.getLabel())
        .clientConsentRequired(Boolean.TRUE.equals(policy.getClientConsentRequired()))
        .accessAllowed(!Boolean.FALSE.equals(policy.getAccessAllowed()))
        .enabled(Boolean.TRUE.equals(policy.getEnabled()))
        .displayOrder(policy.getDisplayOrder())
        .policyAuthority(policy.getPolicyAuthority())
        .build();
  }

  private CaseHandoverReasonPolicy toPolicy(
      CaseHandoverReason reason, CaseHandoverReasonPolicy existingPolicy, LocalDateTime now) {
    String code = normalizeReasonCode(reason.getCode());
    String label = reason.getLabel() == null ? "" : reason.getLabel().trim();
    if (code.isBlank() || label.isBlank()) {
      throw new BadRequestException("Handover reason code and label are required");
    }
    CaseHandoverReasonPolicy policy =
        existingPolicy != null ? existingPolicy : new CaseHandoverReasonPolicy();
    policy.setCode(code);
    policy.setLabel(label);
    policy.setClientConsentRequired(reason.isClientConsentRequired());
    policy.setAccessAllowed(isAccessAllowed(reason));
    policy.setEnabled(reason.isEnabled());
    policy.setDisplayOrder(reason.getDisplayOrder() != null ? reason.getDisplayOrder() : 100);
    policy.setPolicyAuthority(
        reason.getPolicyAuthority() == null || reason.getPolicyAuthority().isBlank()
            ? POLICY_AUTHORITY
            : reason.getPolicyAuthority().trim());
    policy.setUpdatedAt(now);
    return policy;
  }

  private String normalizeReasonCode(String reasonCode) {
    return reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
  }

  private String normalizeExplanation(String explanation) {
    String normalized = explanation == null ? "" : explanation.trim();
    if (normalized.isEmpty()) {
      throw new BadRequestException("Explanation is required");
    }
    return normalized;
  }

  private boolean isAccessAllowed(CaseHandoverReason reason) {
    return !Boolean.FALSE.equals(reason.getAccessAllowed());
  }

  private CaseHandoverStatus denyRequest(
      Session session,
      Consultant requester,
      CaseHandoverReason reason,
      String explanation,
      String auditOutcome,
      LocalDateTime now) {
    CaseHandoverRequest request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(session.getConsultant())
            .reasonCode(reason.getCode())
            .reasonLabel(reason.getLabel())
            .explanation(explanation)
            .status(Status.DENIED)
            .clientConsentRequired(reason.isClientConsentRequired())
            .policyAuthority(reason.getPolicyAuthority())
            .auditOutcome(auditOutcome)
            .createdAt(now)
            .resolvedAt(now)
            .tenantId(session.getTenantId())
            .build();

    return toStatus(caseHandoverRequestRepository.save(request));
  }

  private CaseHandoverStatus toStatus(CaseHandoverRequest request) {
    return CaseHandoverStatus.builder()
        .requestId(request.getId())
        .sessionId(request.getSession().getId())
        .status(request.getStatus().name())
        .canViewContent(request.getStatus() == Status.GRANTED)
        .reasonCode(request.getReasonCode())
        .reasonLabel(request.getReasonLabel())
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .policyAuthority(request.getPolicyAuthority())
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .build();
  }

  private void notifyGranted(CaseHandoverRequest request) {
    Session session = request.getSession();
    Consultant requester = request.getRequesterConsultant();
    String requesterName = resolveConsultantName(requester);
    String text =
        String.format(
            "%s took over your case. Reason: %s. Explanation: %s",
            requesterName, request.getReasonLabel(), request.getExplanation());

    if (session.getUser() != null && session.getUser().getUserId() != null) {
      eventNotificationService.createEvent(
          session.getUser().getUserId(),
          "case.handover.granted",
          EventNotificationService.CATEGORY_SYSTEM,
          "New counsellor took over your case",
          text,
          buildAskerSessionActionPath(session),
          session.getId(),
          session.getTenantId());
    }

    Consultant previousConsultant = request.getPreviousConsultant();
    if (previousConsultant != null && previousConsultant.getId() != null) {
      eventNotificationService.createEvent(
          previousConsultant.getId(),
          "case.handover.granted",
          EventNotificationService.CATEGORY_SYSTEM,
          "Case handover completed",
          String.format(
              "%s took over case #%s. Reason: %s",
              requesterName, session.getId(), request.getReasonLabel()),
          buildConsultantSessionActionPath(session),
          session.getId(),
          session.getTenantId());
    }
  }

  private void notifyPendingConsent(CaseHandoverRequest request) {
    Session session = request.getSession();
    if (session.getUser() == null || session.getUser().getUserId() == null) {
      return;
    }
    eventNotificationService.createEvent(
        session.getUser().getUserId(),
        "case.handover.consent.requested",
        EventNotificationService.CATEGORY_SYSTEM,
        "Counsellor access request",
        String.format(
            "%s requested access to your case. Reason: %s. Explanation: %s",
            resolveConsultantName(request.getRequesterConsultant()),
            request.getReasonLabel(),
            request.getExplanation()),
        buildAskerSessionActionPath(session) + "?caseHandoverRequestId=" + request.getId(),
        session.getId(),
        session.getTenantId());
  }

  private void notifyConsentDeclined(CaseHandoverRequest request) {
    Consultant requester = request.getRequesterConsultant();
    Session session = request.getSession();
    if (requester == null || requester.getId() == null) {
      return;
    }
    eventNotificationService.createEvent(
        requester.getId(),
        "case.handover.consent.declined",
        EventNotificationService.CATEGORY_SYSTEM,
        "Case handover declined",
        String.format(
            "Client consent was declined for case #%s. Reason: %s",
            session.getId(), request.getReasonLabel()),
        buildConsultantSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  private String buildAskerSessionActionPath(Session session) {
    return "/sessions/user/view/session/" + session.getId();
  }

  private String buildConsultantSessionActionPath(Session session) {
    String roomRef =
        session.getMatrixRoomId() != null && !session.getMatrixRoomId().isBlank()
            ? session.getMatrixRoomId()
            : session.getGroupId();
    return roomRef != null
        ? "/sessions/consultant/sessionView/" + roomRef + "/" + session.getId()
        : null;
  }

  private String resolveConsultantName(Consultant consultant) {
    if (consultant == null) {
      return "A counsellor";
    }
    if (consultant.getDisplayName() != null && !consultant.getDisplayName().isBlank()) {
      return consultant.getDisplayName();
    }
    if (consultant.getFullName() != null && !consultant.getFullName().isBlank()) {
      return consultant.getFullName();
    }
    if (consultant.getUsername() != null && !consultant.getUsername().isBlank()) {
      return consultant.getUsername();
    }
    return "A counsellor";
  }

  @Data
  @Builder
  public static class CaseHandoverReason {
    private String code;
    private String label;
    private boolean clientConsentRequired;
    private Boolean accessAllowed;
    private boolean enabled;
    private Integer displayOrder;
    private String policyAuthority;
  }

  @Data
  @Builder
  public static class CaseHandoverStatus {
    private Long requestId;
    private Long sessionId;
    private String status;
    private boolean canViewContent;
    private String reasonCode;
    private String reasonLabel;
    private boolean clientConsentRequired;
    private String policyAuthority;
    private String auditOutcome;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
  }
}
