package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionUserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.facade.SessionSupervisorFacade;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.ConsultantTopic;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.SessionTopic;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.CaseHandoverEmailNotification;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionMapper;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.tenant.TenantData;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverService {

  private static final String ADVICE_NEEDED = "ADVICE_REQUESTED";
  private static final int DEFAULT_ADVICE_ACCESS_DURATION_MINUTES = 180;
  private static final String POLICY_AUTHORITY = "platform-admin-default-case-handover-policy";
  private static final String OUTCOME_ACTIVE_OWNER = "ACTIVE_OWNER";
  private static final String OUTCOME_ACCESS_GRANTED = "ACCESS_GRANTED";
  private static final String OUTCOME_ACCESS_DENIED = "ACCESS_DENIED";
  private static final String OUTCOME_PENDING_CLIENT_CONSENT = "PENDING_CLIENT_CONSENT";
  private static final String OUTCOME_CLIENT_CONSENT_DECLINED = "CLIENT_CONSENT_DECLINED";
  private static final String OUTCOME_ACCESS_EXPIRED = "ACCESS_EXPIRED";
  private static final String OUTCOME_ALREADY_ANSWERED = "ALREADY_ANSWERED";
  private static final String OUTCOME_NOT_REQUESTED = "NOT_REQUESTED";
  private static final String CO_ACCESS_EXPIRY_TASK = "case-handover-co-access-expiry";
  private static final String OUTCOME_OFFER_PENDING = "OFFER_PENDING_RECIPIENT";
  private static final String OUTCOME_RECIPIENT_DECLINED = "RECIPIENT_DECLINED";
  private static final String OUTCOME_OFFER_WITHDRAWN = "OFFER_WITHDRAWN";
  private static final String OUTCOME_OFFER_EXPIRED = "OFFER_EXPIRED";

  private record ClientHandoverCopy(
      String grantedTitle,
      String grantedDescription,
      String pendingTitle,
      String pendingDescription) {}

  /**
   * Built-in client-safe copy used until the tenant-scoped effective policy cache in
   * ORISO-UserService#201 becomes the canonical source. No entry contains reason-derived wording;
   * internal illness, absence, emergency, and staffing details stay on staff and audit surfaces.
   * Unknown languages deliberately fall back to German, while every currently supported language
   * has an explicit entry. fr/ru/tr/uk/ti copy requires native-speaker review before final release.
   */
  private static final Map<String, ClientHandoverCopy> CLIENT_SAFE_HANDOVER_COPY =
      Map.of(
          "de",
          new ClientHandoverCopy(
              "Neue Beratungsperson hat deinen Fall übernommen",
              "{{newAdvisor}} hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.",
              "Zugriffsanfrage einer Beratungsperson",
              "{{newAdvisor}} bittet um Zugriff auf deinen Fall. Deine Zustimmung ist erforderlich."),
          "en",
          new ClientHandoverCopy(
              "New counsellor took over your case",
              "{{newAdvisor}} has taken over your case and will continue your counselling from now on.",
              "Counsellor access request",
              "{{newAdvisor}} requested access to your case. Your consent is required."),
          "fr",
          new ClientHandoverCopy(
              "Un nouveau conseiller ou une nouvelle conseillère a repris votre dossier",
              "{{newAdvisor}} a repris votre dossier et poursuivra désormais votre accompagnement.",
              "Demande d’accès d’un conseiller ou d’une conseillère",
              "{{newAdvisor}} demande l’accès à votre dossier. Votre consentement est requis."),
          "ru",
          new ClientHandoverCopy(
              "Новый консультант принял ваше дело",
              "{{newAdvisor}} принял(а) ваше дело и с этого момента продолжит консультирование.",
              "Запрос консультанта на доступ",
              "{{newAdvisor}} запросил(а) доступ к вашему делу. Требуется ваше согласие."),
          "tr",
          new ClientHandoverCopy(
              "Yeni bir danışman vakanızı devraldı",
              "{{newAdvisor}} vakanızı devraldı ve bundan sonra danışmanlığınıza devam edecek.",
              "Danışman erişim talebi",
              "{{newAdvisor}} vakanıza erişim istedi. Onayınız gerekiyor."),
          "uk",
          new ClientHandoverCopy(
              "Новий консультант перейняв вашу справу",
              "{{newAdvisor}} перейняв(-ла) вашу справу й відтепер продовжуватиме консультування.",
              "Запит консультанта на доступ",
              "{{newAdvisor}} запитує доступ до вашої справи. Потрібна ваша згода."),
          "ti",
          new ClientHandoverCopy(
              "ሓድሽ ኣማኻሪ ጉዳይካ ተረኪቡ",
              "{{newAdvisor}} ጉዳይካ ተረኪቡ ካብ ሕጂ ንደሓር ምኽሪ ክቕጽል እዩ።",
              "ናይ ኣማኻሪ ናይ ምእታው ሕቶ",
              "{{newAdvisor}} ናብ ጉዳይካ ክኣቱ ሓቲቱ። ፍቓድካ የድሊ።"));

  /**
   * Default client-facing notification templates per reason and language (de/en/tr/uk). Source:
   * vault doc "Case Handover — System-Benachrichtigungen & Rechtstexte (Entwurf)"; tr/uk are
   * machine-drafted pending native review. {@code {{newAdvisor}}} is substituted at send time.
   */
  private static final Map<String, String> ABSENCE_NOTIFICATION_TEMPLATE =
      Map.of(
          "de",
              "Deine bisherige Berater:in ist derzeit nicht erreichbar. {{newAdvisor}} betreut dich weiter.",
          "en",
              "Your previous counsellor is currently unavailable. {{newAdvisor}} will continue to look after you.",
          "tr",
              "Önceki danışmanınıza şu anda ulaşılamıyor. {{newAdvisor}} size destek olmaya devam edecek.",
          "uk",
              "Ваш попередній консультант наразі недоступний. {{newAdvisor}} продовжить вас супроводжувати.");

  private static final Map<String, Map<String, String>> DEFAULT_CLIENT_NOTIFICATION_TEMPLATES =
      Map.of(
          "ADVICE_REQUESTED",
          Map.of(
              "de",
              "{{newAdvisor}} kann diese Sitzung für {{duration}} mitlesen. Deine bisherige Berater:in bleibt für dich zuständig.",
              "en",
              "{{newAdvisor}} can read this session for {{duration}}. Your current counsellor remains responsible for you.",
              "tr",
              "{{newAdvisor}} bu oturumu {{duration}} boyunca okuyabilir. Mevcut danışmanınız sizden sorumlu olmaya devam eder.",
              "uk",
              "{{newAdvisor}} може читати цю сесію протягом {{duration}}. Ваш поточний консультант залишається відповідальним за вас."),
          "PLANNED_ABSENCE",
          ABSENCE_NOTIFICATION_TEMPLATE,
          "OTHER_EMERGENCY",
          ABSENCE_NOTIFICATION_TEMPLATE,
          "UNPLANNED_ABSENCE",
          ABSENCE_NOTIFICATION_TEMPLATE,
          "ASSIGNMENT_ENDED",
          Map.of(
              "de",
              "Deine bisherige Berater:in ist nicht mehr in dieser Beratungsstelle tätig. Deine Beratung führt ab jetzt {{newAdvisor}} weiter.",
              "en",
              "Your previous counsellor no longer works at this counselling centre. From now on, {{newAdvisor}} will continue your counselling.",
              "tr",
              "Önceki danışmanınız artık bu danışma merkezinde çalışmıyor. Danışmanlığınıza bundan sonra {{newAdvisor}} devam edecek.",
              "uk",
              "Ваш попередній консультант більше не працює в цьому консультаційному центрі. Відтепер ваше консультування продовжить {{newAdvisor}}."));

  private static final List<CaseHandoverReason> DEFAULT_REASONS =
      List.of(
          CaseHandoverReason.builder()
              .code("ADVICE_REQUESTED")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("ADVICE_REQUESTED"))
              .label("Advice needed")
              .clientConsentRequired(true)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(10)
              .maxAccessDurationMinutes(DEFAULT_ADVICE_ACCESS_DURATION_MINUTES)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("PLANNED_ABSENCE")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("PLANNED_ABSENCE"))
              .label("Leave")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(20)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("OTHER_EMERGENCY")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("OTHER_EMERGENCY"))
              .label("Other emergency")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(30)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("UNPLANNED_ABSENCE")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("UNPLANNED_ABSENCE"))
              .label("Absence")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(40)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("ASSIGNMENT_ENDED")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("ASSIGNMENT_ENDED"))
              .label("Counsellor does not work here anymore")
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(50)
              .policyAuthority(POLICY_AUTHORITY)
              .build());

  private final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;

  /**
   * ADR-008 "Supervision (auto-assigned)": a takeover hands the case to a new owner, so the new
   * owner's standing supervisor must attach. Invoked after commit — see {@link
   * #attachStandingSupervisorAfterCommit}.
   */
  private final @NonNull SessionSupervisorFacade sessionSupervisorFacade;

  private final @NonNull CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  private final @NonNull CaseHandoverPolicyCacheService caseHandoverPolicyCacheService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull CaseHandoverEmailNotification caseHandoverEmailNotification;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  private final @NonNull ScheduledTaskClaimService scheduledTaskClaimService;
  private final @NonNull Clock clock;

  @Value("${case.handover.co-access-claim-duration:PT2M}")
  private Duration coAccessClaimDuration = Duration.ofMinutes(2);

  private final @NonNull ConsultantRepository consultantRepository;

  /**
   * When topics are off, a department degenerates to the agency. When they are on, an empty topic
   * set must not widen the search to every case in the agency (#202).
   */
  @Value("${feature.topics.enabled:false}")
  private boolean topicsEnabled;

  /**
   * How long an unanswered handover offer stays open before it falls back to the offering
   * counsellor. A case must never be parked indefinitely in a colleague's inbox.
   */
  @Value("${case.handover.offer.validity:PT72H}")
  private Duration offerValidity = Duration.ofHours(72);

  public List<CaseHandoverReason> listReasons() {
    return listReasons(TenantContext.getCurrentTenant());
  }

  public List<CaseHandoverReason> listReasons(Long tenantId) {
    return listReasons(tenantId, "de", false);
  }

  private List<CaseHandoverReason> listReasons(
      Long tenantId, String language, boolean includeDisabled) {
    if (tenantId != null && tenantId > 0) {
      de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverPolicies cached;
      try {
        cached = caseHandoverPolicyCacheService.getEffective(tenantId);
      } catch (RuntimeException exception) {
        var legacy = legacyReasons(includeDisabled);
        if (!legacy.isEmpty()) {
          log.warn(
              "Tenant {} Case Handover policy unavailable; using explicitly configured legacy policy during migration: {}",
              tenantId,
              exception.getMessage());
          return legacy;
        }
        throw exception;
      }
      if (cached != null && cached.getReasons() != null && !cached.getReasons().isEmpty()) {
        return cached.getReasons().values().stream()
            .map(policy -> toReason(policy, language))
            .filter(reason -> includeDisabled || reason.isEnabled())
            .sorted(
                java.util.Comparator.comparing(
                        CaseHandoverReason::getDisplayOrder,
                        java.util.Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(CaseHandoverReason::getCode))
            .collect(Collectors.toList());
      }
    }
    return legacyOrDefaults(includeDisabled);
  }

  private List<CaseHandoverReason> legacyReasons(boolean includeDisabled) {
    List<CaseHandoverReasonPolicy> policies =
        includeDisabled
            ? caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc()
            : caseHandoverReasonPolicyRepository.findByEnabledTrueOrderByDisplayOrderAscCodeAsc();
    return policies.stream().map(this::toReason).collect(Collectors.toList());
  }

  private List<CaseHandoverReason> legacyOrDefaults(boolean includeDisabled) {
    var legacy = legacyReasons(includeDisabled);
    return legacy.isEmpty()
        ? (includeDisabled
            ? DEFAULT_REASONS
            : DEFAULT_REASONS.stream()
                .filter(CaseHandoverReason::isEnabled)
                .collect(Collectors.toList()))
        : legacy;
  }

  @Transactional(readOnly = true)
  public List<CaseHandoverReason> listReasonPolicies() {
    return listReasons(TenantContext.getCurrentTenant(), "de", true);
  }

  @Transactional
  public List<CaseHandoverReason> updateReasonPolicies(List<CaseHandoverReason> requestedReasons) {
    if (requestedReasons == null || requestedReasons.isEmpty()) {
      throw new BadRequestException("At least one handover reason policy is required");
    }

    // Keep the first row per code: a plain toMap throws IllegalStateException (-> 500)
    // if the table ever holds duplicate codes, e.g. after a hand-applied seed on an
    // environment where the guarded 0057 changeset was skipped. Note that code is the
    // table's PRIMARY KEY in both the 0057 and 0059 schemas, so the DB already enforces
    // uniqueness; no extra UNIQUE constraint is needed and this merge is defense-in-depth
    // for rows created outside Liquibase (review note on #324).
    Map<String, CaseHandoverReasonPolicy> existingPolicies =
        caseHandoverReasonPolicyRepository.findAllByOrderByDisplayOrderAscCodeAsc().stream()
            .collect(
                Collectors.toMap(
                    policy -> canonicalReasonCode(policy.getCode()),
                    Function.identity(),
                    (first, ignored) -> first));
    LocalDateTime now = LocalDateTime.now(clock);
    List<CaseHandoverReasonPolicy> policiesToSave =
        requestedReasons.stream()
            .map(
                reason ->
                    toPolicy(
                        reason, existingPolicies.get(canonicalReasonCode(reason.getCode())), now))
            .collect(Collectors.toList());

    caseHandoverReasonPolicyRepository.saveAll(policiesToSave);
    return listReasonPolicies();
  }

  @Transactional(readOnly = true)
  public CaseHandoverStatus getStatus(Long sessionId) {
    Consultant requester = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyEligibleForSession(session, requester);

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

  @Transactional(readOnly = true)
  public ConsultantSessionListResponseDTO searchCandidates(
      String query, int offset, int count, boolean archived) {
    Consultant requester = retrieveCurrentConsultant();
    List<Long> agencyIds = new ArrayList<>(consultantAgencyIds(requester));
    int safeOffset = Math.max(0, offset);
    int safeCount = Math.max(1, Math.min(count, 200));

    if (agencyIds.isEmpty()) {
      return emptyCandidateResponse(safeOffset);
    }

    List<SessionStatus> statuses =
        archived
            ? List.of(SessionStatus.IN_ARCHIVE)
            : List.of(SessionStatus.IN_PROGRESS, SessionStatus.DONE);
    List<Session> candidates =
        sessionRepository.findByAgencyIdInAndConsultantNotAndStatusInOrderByUpdateDateDesc(
            agencyIds, requester, statuses);

    List<Session> matchingCandidates =
        candidates.stream()
            .filter(session -> isInRequesterDepartment(session, requester))
            .filter(session -> matchesCandidateQuery(session, query))
            .collect(Collectors.toList());
    List<ConsultantSessionResponseDTO> page =
        matchingCandidates.stream()
            .skip(safeOffset)
            .limit(safeCount)
            .map(this::toCandidateDto)
            .collect(Collectors.toList());

    return new ConsultantSessionListResponseDTO()
        .sessions(page)
        .offset(safeOffset)
        .count(page.size())
        .total(matchingCandidates.size());
  }

  @Transactional
  public CaseHandoverStatus requestAccess(Long sessionId, String reasonCode, String explanation) {
    Consultant requester = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyEligibleForSession(session, requester);

    if (isActiveOwner(session, requester)) {
      return getStatus(sessionId);
    }

    String normalizedExplanation = normalizeExplanation(explanation);
    CaseHandoverReason reason = findReason(session, reasonCode);

    Optional<CaseHandoverRequest> existing = latestFor(sessionId, requester);
    if (existing.filter(this::isOpenOrGranted).isPresent()) {
      return toStatus(existing.get());
    }

    LocalDateTime now = LocalDateTime.now(clock);
    if (latestGrantedForOtherRequester(sessionId, requester).isPresent()) {
      return denyRequest(
          session, requester, reason, normalizedExplanation, OUTCOME_ALREADY_ANSWERED, now);
    }
    if (!isAccessAllowed(reason)) {
      return denyRequest(
          session, requester, reason, normalizedExplanation, OUTCOME_ACCESS_DENIED, now);
    }

    boolean clientConsentRequired = reason.isClientConsentRequired();
    Status status = clientConsentRequired ? Status.PENDING_CLIENT_CONSENT : Status.GRANTED;
    String auditOutcome =
        clientConsentRequired ? OUTCOME_PENDING_CLIENT_CONSENT : OUTCOME_ACCESS_GRANTED;

    CaseHandoverRequest request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(session.getConsultant())
            .reasonCode(reason.getCode())
            .reasonLabel(reason.getLabel())
            .explanation(normalizedExplanation)
            .status(status)
            .clientConsentRequired(clientConsentRequired)
            .policyAuthority(reason.getPolicyAuthority())
            .auditOutcome(auditOutcome)
            .createdAt(now)
            .resolvedAt(status == Status.GRANTED ? now : null)
            .accessType(accessType(reason.getCode()))
            .maxAccessDurationMinutes(maxAccessDurationMinutes(reason))
            .expiresAt(expiresAt(reason, status, now))
            .tenantId(session.getTenantId())
            .build();

    CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);

    if (status == Status.GRANTED) {
      ensureRequesterJoinedMatrixRoom(session, requester, session.getConsultant());
      if (request.getAccessType() == AccessType.TAKEOVER) {
        session.setConsultant(requester);
        session.setUpdateDate(now);
        sessionRepository.save(session);
        attachStandingSupervisorAfterCommit(session.getId(), requester);
      }
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
      return toClientStatus(request);
    }

    LocalDateTime now = LocalDateTime.now(clock);
    request.setResolvedAt(now);
    if (approved) {
      if (hasAlreadyGrantedOrTakenOver(session, request)) {
        request.setStatus(Status.DENIED);
        request.setAuditOutcome(OUTCOME_ALREADY_ANSWERED);
        CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
        return toClientStatus(saved);
      }

      request.setStatus(Status.GRANTED);
      request.setAuditOutcome(OUTCOME_ACCESS_GRANTED);
      request.setAccessType(effectiveAccessType(request));
      if (request.getAccessType() == AccessType.CO_ACCESS) {
        Integer capturedDuration = request.getMaxAccessDurationMinutes();
        if (capturedDuration == null) {
          capturedDuration =
              maxAccessDurationMinutes(findReason(session, request.getReasonCode(), true));
        }
        request.setMaxAccessDurationMinutes(
            validateMaxAccessDuration(request.getReasonCode(), capturedDuration));
        request.setExpiresAt(now.plusMinutes(request.getMaxAccessDurationMinutes()));
      } else {
        request.setMaxAccessDurationMinutes(null);
        request.setExpiresAt(null);
      }
      ensureRequesterJoinedMatrixRoom(
          session, request.getRequesterConsultant(), request.getPreviousConsultant());
      if (request.getAccessType() == AccessType.TAKEOVER) {
        session.setConsultant(request.getRequesterConsultant());
        session.setUpdateDate(now);
        sessionRepository.save(session);
        attachStandingSupervisorAfterCommit(session.getId(), request.getRequesterConsultant());
      }
      CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
      notifyGranted(saved);
      return toClientStatus(saved);
    }

    request.setStatus(Status.CLIENT_CONSENT_DECLINED);
    request.setAuditOutcome(OUTCOME_CLIENT_CONSENT_DECLINED);
    CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
    notifyConsentDeclined(saved);
    return toClientStatus(saved);
  }

  /**
   * PUSH entry point: the counsellors this case may be offered to.
   *
   * <p>Scoped to the AGENCY of the case, not to the offering counsellor's department. The
   * department filter that narrows the PULL candidate search (agency × topic, #202) is wrong here:
   * in a team agency it hides exactly the colleagues a counsellor wants to hand over to
   * (FE#1152/#1105, plan E10). Pull asks "whose cases may I look into" — least privilege. Push asks
   * "who in this counselling centre can take my case" — the case is already mine to give.
   */
  @Transactional(readOnly = true)
  public CaseHandoverColleagueList listColleagues(
      Long sessionId, String query, int offset, int count) {
    Consultant owner = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyIsActiveOwner(session, owner);

    int safeOffset = Math.max(0, offset);
    int safeCount = Math.max(1, Math.min(count, 200));
    if (session.getAgencyId() == null) {
      return CaseHandoverColleagueList.builder()
          .colleagues(List.of())
          .total(0)
          .offset(safeOffset)
          .count(0)
          .build();
    }

    List<Consultant> colleagues =
        consultantAgencyRepository
            .findByAgencyIdAndDeleteDateIsNullOrderByConsultantFirstNameAsc(session.getAgencyId())
            .stream()
            .map(ConsultantAgency::getConsultant)
            .filter(Objects::nonNull)
            .filter(candidate -> candidate.getDeleteDate() == null)
            .filter(candidate -> !candidate.getId().equals(owner.getId()))
            .filter(distinctById())
            .filter(candidate -> matchesColleagueQuery(candidate, query))
            .collect(Collectors.toList());

    List<CaseHandoverColleague> page =
        colleagues.stream()
            .skip(safeOffset)
            .limit(safeCount)
            .map(this::toColleague)
            .collect(Collectors.toList());

    return CaseHandoverColleagueList.builder()
        .colleagues(page)
        .total(colleagues.size())
        .offset(safeOffset)
        .count(page.size())
        .build();
  }

  /**
   * The owner offers their own case to a named colleague. Nothing changes hands yet — the case
   * stays with the owner until the colleague accepts, which is the whole point of the direction: a
   * counsellor going on leave should not be able to drop a case on someone who never agreed.
   */
  @Transactional
  public CaseHandoverOffer createOffer(
      Long sessionId, String targetConsultantId, String reasonCode, String explanation) {
    Consultant owner = retrieveCurrentConsultant();
    Session session = getSession(sessionId);
    verifyIsActiveOwner(session, owner);

    Consultant target = findColleague(targetConsultantId);
    if (target.getId().equals(owner.getId())) {
      throw new BadRequestException("A case cannot be handed over to its current counsellor");
    }
    verifyEligibleColleague(session, target);

    String normalizedExplanation = normalizeExplanation(explanation);
    CaseHandoverReason reason = findReason(session, reasonCode);
    if (!isAccessAllowed(reason)) {
      throw new BadRequestException("This handover reason does not allow access");
    }

    if (!openOffersFor(sessionId).isEmpty()) {
      throw new ConflictException("This case already has an open handover offer");
    }

    LocalDateTime now = LocalDateTime.now(clock);
    CaseHandoverRequest offer =
        CaseHandoverRequest.builder()
            .session(session)
            .direction(CaseHandoverRequest.Direction.PUSH)
            // The target is stored as the REQUESTER as well: accepting makes them the new owner,
            // and the shared grant path (see acceptOffer) reads requesterConsultant. Keeping the
            // two in sync here is what lets push and pull run through one code path.
            .requesterConsultant(target)
            .targetConsultant(target)
            .previousConsultant(owner)
            .reasonCode(reason.getCode())
            .reasonLabel(reason.getLabel())
            .explanation(normalizedExplanation)
            .status(Status.PENDING_RECIPIENT_ACCEPT)
            .clientConsentRequired(reason.isClientConsentRequired())
            .policyAuthority(reason.getPolicyAuthority())
            .auditOutcome(OUTCOME_OFFER_PENDING)
            .createdAt(now)
            .offerExpiresAt(now.plus(offerValidity))
            .accessType(accessType(reason.getCode()))
            .maxAccessDurationMinutes(maxAccessDurationMinutes(reason))
            .tenantId(session.getTenantId())
            .build();

    CaseHandoverRequest saved = caseHandoverRequestRepository.save(offer);
    notifyOfferCreated(saved);
    return toOffer(saved);
  }

  /**
   * The recipient accepts. From here on this is the ordinary grant: the same policy gate, the same
   * client-consent state, the same Matrix join and standing-supervisor attach as {@link
   * #requestAccess}. ADR-022 allows exactly two consent gates, so a push must not invent a third —
   * the advice seeker is asked when, and only when, the reason policy says so.
   */
  @Transactional
  public CaseHandoverStatus acceptOffer(Long offerId) {
    Consultant recipient = retrieveCurrentConsultant();
    CaseHandoverRequest offer = openOfferFor(offerId, recipient);
    Session session = offer.getSession();
    LocalDateTime now = LocalDateTime.now(clock);

    if (hasAlreadyGrantedOrTakenOver(session, offer)) {
      offer.setStatus(Status.DENIED);
      offer.setAuditOutcome(OUTCOME_ALREADY_ANSWERED);
      offer.setResolvedAt(now);
      return toStatus(caseHandoverRequestRepository.save(offer));
    }

    // The consent requirement is the one frozen into the offer, not today's policy. The colleague
    // accepted a specific promise ("the client will/will not be asked"); a policy edit in between
    // must not silently change what they agreed to.
    if (Boolean.TRUE.equals(offer.getClientConsentRequired())) {
      offer.setStatus(Status.PENDING_CLIENT_CONSENT);
      offer.setAuditOutcome(OUTCOME_PENDING_CLIENT_CONSENT);
      CaseHandoverRequest saved = caseHandoverRequestRepository.save(offer);
      notifyPendingConsent(saved);
      notifyOfferAccepted(saved);
      return toStatus(saved);
    }

    offer.setStatus(Status.GRANTED);
    offer.setAuditOutcome(OUTCOME_ACCESS_GRANTED);
    offer.setResolvedAt(now);
    ensureRequesterJoinedMatrixRoom(session, recipient, offer.getPreviousConsultant());
    offer.setAccessType(effectiveAccessType(offer));
    if (offer.getAccessType() == AccessType.TAKEOVER) {
      session.setConsultant(recipient);
      session.setUpdateDate(now);
      sessionRepository.save(session);
      attachStandingSupervisorAfterCommit(session.getId(), recipient);
    } else {
      offer.setExpiresAt(
          now.plusMinutes(
              validateMaxAccessDuration(
                  offer.getReasonCode(), offer.getMaxAccessDurationMinutes())));
    }
    CaseHandoverRequest saved = caseHandoverRequestRepository.save(offer);
    notifyGranted(saved);
    notifyOfferAccepted(saved);
    return toStatus(saved);
  }

  /** The recipient says no. The case never moved, so there is nothing to roll back. */
  @Transactional
  public CaseHandoverOffer declineOffer(Long offerId) {
    Consultant recipient = retrieveCurrentConsultant();
    CaseHandoverRequest offer = openOfferFor(offerId, recipient);
    offer.setStatus(Status.RECIPIENT_DECLINED);
    offer.setAuditOutcome(OUTCOME_RECIPIENT_DECLINED);
    offer.setResolvedAt(LocalDateTime.now(clock));
    CaseHandoverRequest saved = caseHandoverRequestRepository.save(offer);
    notifyOfferDeclined(saved);
    return toOffer(saved);
  }

  /** The offering counsellor takes the offer back before it was answered. */
  @Transactional
  public CaseHandoverOffer withdrawOffer(Long offerId) {
    Consultant owner = retrieveCurrentConsultant();
    CaseHandoverRequest offer = findOffer(offerId);
    Consultant offeringConsultant = offer.getPreviousConsultant();
    if (offeringConsultant == null || !offeringConsultant.getId().equals(owner.getId())) {
      throw new ForbiddenException("Only the offering counsellor can withdraw this offer");
    }
    verifyOfferIsOpen(offer);
    offer.setStatus(Status.WITHDRAWN);
    offer.setAuditOutcome(OUTCOME_OFFER_WITHDRAWN);
    offer.setResolvedAt(LocalDateTime.now(clock));
    return toOffer(caseHandoverRequestRepository.save(offer));
  }

  @Transactional(readOnly = true)
  public List<CaseHandoverOffer> listOffers(boolean incoming) {
    Consultant consultant = retrieveCurrentConsultant();
    List<CaseHandoverRequest> offers =
        incoming
            ? caseHandoverRequestRepository.findByTargetConsultantIdAndStatusOrderByCreatedAtDesc(
                consultant.getId(), Status.PENDING_RECIPIENT_ACCEPT)
            : caseHandoverRequestRepository
                .findByDirectionAndPreviousConsultantIdAndStatusOrderByCreatedAtDesc(
                    CaseHandoverRequest.Direction.PUSH,
                    consultant.getId(),
                    Status.PENDING_RECIPIENT_ACCEPT);
    return offers.stream()
        .filter(offer -> !isExpired(offer, LocalDateTime.now(clock)))
        .map(this::toOffer)
        .collect(Collectors.toList());
  }

  /**
   * Moves offers nobody answered to EXPIRED and tells the offering counsellor. Without this an
   * offer would stay "pending" forever and the offering counsellor would keep believing the case
   * was on its way to someone.
   */
  @Transactional
  public int expireOffers() {
    LocalDateTime now = LocalDateTime.now(clock);
    List<CaseHandoverRequest> expired =
        caseHandoverRequestRepository.findByStatusAndOfferExpiresAtBefore(
            Status.PENDING_RECIPIENT_ACCEPT, now);
    expired.forEach(
        offer -> {
          offer.setStatus(Status.EXPIRED);
          offer.setAuditOutcome(OUTCOME_OFFER_EXPIRED);
          offer.setResolvedAt(now);
          notifyOfferExpired(caseHandoverRequestRepository.save(offer));
        });
    return expired.size();
  }

  private List<CaseHandoverRequest> openOffersFor(Long sessionId) {
    LocalDateTime now = LocalDateTime.now(clock);
    return caseHandoverRequestRepository
        .findBySessionIdAndDirectionAndStatus(
            sessionId, CaseHandoverRequest.Direction.PUSH, Status.PENDING_RECIPIENT_ACCEPT)
        .stream()
        // An offer past its window is not an obstacle even if the sweep has not run yet;
        // otherwise a stale row would block the case until the next scheduler tick.
        .filter(offer -> !isExpired(offer, now))
        .collect(Collectors.toList());
  }

  private boolean isExpired(CaseHandoverRequest offer, LocalDateTime now) {
    return offer.getOfferExpiresAt() != null && offer.getOfferExpiresAt().isBefore(now);
  }

  private CaseHandoverRequest findOffer(Long offerId) {
    CaseHandoverRequest offer =
        caseHandoverRequestRepository
            .findById(offerId)
            .orElseThrow(() -> new NotFoundException("Case handover offer not found"));
    if (offer.getDirection() != CaseHandoverRequest.Direction.PUSH) {
      throw new NotFoundException("Case handover offer not found");
    }
    return offer;
  }

  private CaseHandoverRequest openOfferFor(Long offerId, Consultant recipient) {
    CaseHandoverRequest offer = findOffer(offerId);
    Consultant target = offer.getTargetConsultant();
    if (target == null || !target.getId().equals(recipient.getId())) {
      throw new ForbiddenException("This handover offer was not made to the current consultant");
    }
    verifyOfferIsOpen(offer);
    return offer;
  }

  private void verifyOfferIsOpen(CaseHandoverRequest offer) {
    if (offer.getStatus() != Status.PENDING_RECIPIENT_ACCEPT) {
      throw new ConflictException("This handover offer is no longer open");
    }
    if (isExpired(offer, LocalDateTime.now(clock))) {
      // Resolve it here rather than leaving it for the sweep: the caller learns the real reason,
      // and the row cannot be accepted a second later by a slower request.
      offer.setStatus(Status.EXPIRED);
      offer.setAuditOutcome(OUTCOME_OFFER_EXPIRED);
      offer.setResolvedAt(LocalDateTime.now(clock));
      caseHandoverRequestRepository.save(offer);
      throw new ConflictException("This handover offer has expired");
    }
  }

  private Consultant findColleague(String consultantId) {
    if (consultantId == null || consultantId.isBlank()) {
      throw new BadRequestException("A target consultant is required");
    }
    return consultantRepository
        .findByIdAndDeleteDateIsNull(consultantId)
        .orElseThrow(() -> new NotFoundException("Consultant not found: " + consultantId));
  }

  private void verifyIsActiveOwner(Session session, Consultant consultant) {
    if (!isActiveOwner(session, consultant)) {
      throw new ForbiddenException("Only the active counsellor of this case can hand it over");
    }
  }

  private void verifyEligibleColleague(Session session, Consultant target) {
    if (session.getAgencyId() == null
        || !consultantAgencyIds(target).contains(session.getAgencyId())) {
      throw new ForbiddenException(
          "The target consultant does not work in the counselling centre of this case");
    }
  }

  private java.util.function.Predicate<Consultant> distinctById() {
    Set<String> seen = new HashSet<>();
    return consultant -> seen.add(consultant.getId());
  }

  private boolean matchesColleagueQuery(Consultant consultant, String query) {
    String normalizedQuery = normalizeSearchText(query);
    if (normalizedQuery.isBlank()) {
      return true;
    }
    return List.of(
            nullable(decodeUsername(consultant.getUsername())),
            nullable(decodeUsername(consultant.getInternalDisplayNameOrFallback())),
            nullable(consultant.getFirstName()),
            nullable(consultant.getLastName()))
        .stream()
        .map(this::normalizeSearchText)
        .anyMatch(value -> value.contains(normalizedQuery));
  }

  private CaseHandoverColleague toColleague(Consultant consultant) {
    return CaseHandoverColleague.builder()
        .consultantId(consultant.getId())
        .username(decodeUsername(consultant.getUsername()))
        // Colleague pickers are an internal surface, so the internal name wins (#996).
        .displayName(decodeUsername(consultant.getInternalDisplayNameOrFallback()))
        .firstName(consultant.getFirstName())
        .lastName(consultant.getLastName())
        .absent(consultant.isAbsent())
        .build();
  }

  private CaseHandoverOffer toOffer(CaseHandoverRequest offer) {
    return CaseHandoverOffer.builder()
        .offerId(offer.getId())
        .accessType(effectiveAccessType(offer).name())
        .sessionId(offer.getSession().getId())
        .status(offer.getStatus().name())
        .direction(
            offer.getDirection() == null
                ? CaseHandoverRequest.Direction.PULL.name()
                : offer.getDirection().name())
        .reasonCode(offer.getReasonCode())
        .reasonLabel(offer.getReasonLabel())
        .explanation(offer.getExplanation())
        .clientConsentRequired(Boolean.TRUE.equals(offer.getClientConsentRequired()))
        .fromConsultantId(
            offer.getPreviousConsultant() == null ? null : offer.getPreviousConsultant().getId())
        .fromConsultantName(resolveConsultantName(offer.getPreviousConsultant()))
        .targetConsultantId(
            offer.getTargetConsultant() == null ? null : offer.getTargetConsultant().getId())
        .targetConsultantName(resolveConsultantName(offer.getTargetConsultant()))
        .createdAt(offer.getCreatedAt())
        .offerExpiresAt(offer.getOfferExpiresAt())
        .resolvedAt(offer.getResolvedAt())
        .build();
  }

  private void notifyOfferCreated(CaseHandoverRequest offer) {
    notifyOfferParty(
        offer,
        offer.getTargetConsultant(),
        "case.handover.offered",
        "A colleague wants to hand a case over to you",
        String.format(
            "%s offers you case #%s. Reason: %s",
            resolveConsultantName(offer.getPreviousConsultant()),
            offer.getSession().getId(),
            offer.getReasonLabel()));
  }

  private void notifyOfferAccepted(CaseHandoverRequest offer) {
    notifyOfferParty(
        offer,
        offer.getPreviousConsultant(),
        "case.handover.accepted",
        "Your handover offer was accepted",
        String.format(
            "%s accepted case #%s.",
            resolveConsultantName(offer.getTargetConsultant()), offer.getSession().getId()));
  }

  private void notifyOfferDeclined(CaseHandoverRequest offer) {
    notifyOfferParty(
        offer,
        offer.getPreviousConsultant(),
        "case.handover.declined",
        "Your handover offer was declined",
        String.format(
            "%s declined case #%s. The case stays with you.",
            resolveConsultantName(offer.getTargetConsultant()), offer.getSession().getId()));
  }

  private void notifyOfferExpired(CaseHandoverRequest offer) {
    notifyOfferParty(
        offer,
        offer.getPreviousConsultant(),
        "case.handover.expired",
        "Your handover offer expired",
        String.format(
            "%s did not answer your offer for case #%s. The case stays with you.",
            resolveConsultantName(offer.getTargetConsultant()), offer.getSession().getId()));
  }

  /**
   * Offer notifications go to counsellors only. The advice seeker learns nothing about an offer —
   * they are told when the case actually changes hands, or when their consent is needed, which is
   * exactly the two moments ADR-022 provides for.
   */
  private void notifyOfferParty(
      CaseHandoverRequest offer,
      Consultant recipient,
      String eventType,
      String title,
      String text) {
    if (recipient == null || recipient.getId() == null) {
      return;
    }
    Session session = offer.getSession();
    eventNotificationService.createEvent(
        recipient.getId(),
        eventType,
        EventNotificationService.CATEGORY_SYSTEM,
        title,
        text,
        eventNotificationService.buildCaseHandoverOfferParams(
            session,
            resolveConsultantName(offer.getPreviousConsultant()),
            resolveConsultantName(offer.getTargetConsultant()),
            offer.getReasonCode(),
            offer.getReasonLabel(),
            offer.getId(),
            offer.getAccessType()),
        buildConsultantSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  /**
   * ADR-008 "Supervision (auto-assigned)": attach the new owner's standing supervisor once the
   * takeover has actually committed.
   *
   * <p>Deliberately NOT a direct call. {@code addSupervisor} is itself {@code @Transactional}, so
   * called from inside this service's transaction it would join it — and any exception it raises
   * (client opted out, that supervisor is already on the case, no Matrix user id) marks the shared
   * transaction rollback-only. The facade swallowing the exception would not save us: the handover
   * would still fail at commit with an UnexpectedRollbackException. Running after commit keeps the
   * facade's contract intact — a supervision problem leaves the case unsupervised, it never undoes
   * the handover.
   *
   * <p>Outside a transaction (unit tests, future non-transactional callers) it runs inline, which
   * carries the same guarantee because the facade never throws.
   *
   * <p>The PREVIOUS counsellor's supervisor is deliberately left attached. ADR-008 leaves this
   * open, and stripping oversight from a live case is not something a handover should do silently;
   * removing a supervisor stays an explicit act.
   */
  private void attachStandingSupervisorAfterCommit(Long sessionId, Consultant newOwner) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      sessionSupervisorFacade.attachStandingSupervisorIfAssigned(sessionId, newOwner);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              // Must be the REQUIRES_NEW entry point: at afterCommit the committed transaction's
              // resources are still bound, so a write through the plain method would join a
              // transaction that can no longer commit and the SessionSupervisor row would be lost.
              sessionSupervisorFacade.attachStandingSupervisorInNewTransaction(sessionId, newOwner);
            } catch (RuntimeException supervisionFailure) {
              // The catch has to sit OUTSIDE the proxied boundary. The facade swallows its own
              // exceptions, but a swallowed persistence failure has already marked the new
              // transaction rollback-only, so the commit the proxy attempts afterwards throws
              // UnexpectedRollbackException — after this method returned. Escaping here would
              // surface as a 500 on a handover that has already committed, which is exactly the
              // guarantee this indirection exists to protect.
              log.warn(
                  "Standing supervisor attach failed after the handover of session {} committed;"
                      + " the case stays unsupervised",
                  sessionId,
                  supervisionFailure);
            }
          }
        });
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

  private ConsultantSessionListResponseDTO emptyCandidateResponse(int offset) {
    return new ConsultantSessionListResponseDTO()
        .sessions(List.of())
        .offset(offset)
        .count(0)
        .total(0);
  }

  private boolean matchesCandidateQuery(Session session, String query) {
    String normalizedQuery = normalizeSearchText(query);
    if (normalizedQuery.isBlank()) {
      return true;
    }

    List<String> haystack =
        List.of(
            String.valueOf(session.getId()),
            nullable(session.getAgencyId()),
            nullable(session.getPostcode()),
            nullable(session.getMainTopicId()),
            nullable(session.getUser() != null ? session.getUser().getUsername() : null),
            nullable(
                session.getUser() != null ? decodeUsername(session.getUser().getUsername()) : null),
            nullable(
                session.getConsultant() != null ? session.getConsultant().getUsername() : null),
            nullable(
                session.getConsultant() != null
                    ? decodeUsername(session.getConsultant().getUsername())
                    : null),
            nullable(
                session.getConsultant() != null
                    ? decodeUsername(session.getConsultant().getDisplayName())
                    : null),
            // The candidate list renders the internal name with fallback (#996), so the search
            // must cover it too — otherwise an internal-name-only query filters the session out
            // before it is rendered. The public display name above stays a valid search term.
            nullable(
                session.getConsultant() != null
                    ? decodeUsername(session.getConsultant().getInternalDisplayNameOrFallback())
                    : null),
            nullable(
                session.getConsultant() != null ? session.getConsultant().getFirstName() : null),
            nullable(
                session.getConsultant() != null ? session.getConsultant().getLastName() : null));

    return haystack.stream()
        .map(this::normalizeSearchText)
        .anyMatch(value -> value.contains(normalizedQuery));
  }

  /**
   * A department is an (agency × topic) pair. The counsellor's departments are the cartesian
   * product of their agencies and their topics; a session is in scope when its agency is one of
   * theirs and it shares at least one topic — the union across those departments (#202).
   *
   * <p>When {@code feature.topics.enabled=false} the topic dimension does not exist, so the
   * department degenerates to the agency. When topics are on, an empty topic assignment must not
   * fall back to every case in the agency: that would be wider than least-privilege.
   */
  private boolean isInRequesterDepartment(Session session, Consultant requester) {
    if (session.getAgencyId() == null
        || !consultantAgencyIds(requester).contains(session.getAgencyId())) {
      return false;
    }
    Set<Long> requesterTopicIds = consultantTopicIds(requester);
    if (requesterTopicIds.isEmpty()) {
      return !topicsEnabled;
    }
    return !Collections.disjoint(sessionTopicIds(session), requesterTopicIds);
  }

  private Set<Long> consultantTopicIds(Consultant consultant) {
    Set<ConsultantTopic> topics = consultant.getConsultantTopics();
    if (topics == null) {
      return Set.of();
    }
    return topics.stream()
        .map(ConsultantTopic::getTopicId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  /**
   * The topics that place a session in a department: its main topic plus every topic assigned to
   * it, so a session tagged with several topics is reachable from each corresponding department.
   */
  private Set<Long> sessionTopicIds(Session session) {
    Set<Long> topicIds = new HashSet<>();
    if (session.getMainTopicId() != null) {
      topicIds.add(session.getMainTopicId());
    }
    List<SessionTopic> sessionTopics = session.getSessionTopics();
    if (sessionTopics != null) {
      sessionTopics.stream()
          .filter(Objects::nonNull)
          .map(SessionTopic::getTopicId)
          .filter(Objects::nonNull)
          .forEach(topicIds::add);
    }
    return topicIds;
  }

  private String nullable(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private String normalizeSearchText(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private ConsultantSessionResponseDTO toCandidateDto(Session session) {
    ConsultantSessionResponseDTO dto =
        new ConsultantSessionResponseDTO()
            .session(new SessionMapper().convertToSessionDTO(session));

    User user = session.getUser();
    if (user != null) {
      SessionUserDTO userDto = new SessionUserDTO();
      userDto.setId(user.getUserId());
      userDto.setUsername(decodeUsername(user.getUsername()));
      userDto.setDeleted(user.getDeleteDate() != null);
      dto.user(userDto);
    }

    Consultant consultant = session.getConsultant();
    if (consultant != null) {
      dto.consultant(
          new SessionConsultantForConsultantDTO()
              .id(consultant.getId())
              .firstName(consultant.getFirstName())
              .lastName(consultant.getLastName())
              .username(decodeUsername(consultant.getUsername()))
              // Handover candidates are shown to colleagues (internal surface, #996).
              .displayName(decodeUsername(consultant.getInternalDisplayNameOrFallback())));
    }

    return dto;
  }

  private String decodeUsername(String username) {
    return username == null ? null : new UsernameTranscoder().decodeUsername(username);
  }

  private void verifyEligibleForSession(Session session, Consultant requester) {
    if (isActiveOwner(session, requester)) {
      return;
    }
    if (!isInRequesterDepartment(session, requester)) {
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
    // PENDING_RECIPIENT_ACCEPT belongs here: if a colleague already has an open offer for this
    // case, a pull request from the same person must return that offer rather than open a second
    // parallel handover for the same pair.
    return List.of(
                Status.PENDING,
                Status.PENDING_CLIENT_CONSENT,
                Status.PENDING_RECIPIENT_ACCEPT,
                Status.GRANTED)
            .contains(request.getStatus())
        && !isExpired(request);
  }

  private Optional<CaseHandoverRequest> latestGrantedForOtherRequester(
      Long sessionId, Consultant requester) {
    return caseHandoverRequestRepository
        .findBySessionIdAndStatusOrderByCreatedAtDesc(sessionId, Status.GRANTED)
        .stream()
        .filter(request -> !isExpired(request))
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

  private CaseHandoverReason findReason(Session session, String reasonCode) {
    return findReason(session, reasonCode, false);
  }

  private CaseHandoverReason findReason(
      Session session, String reasonCode, boolean includeDisabled) {
    String normalized = canonicalReasonCode(reasonCode);
    Long tenantId = session == null ? TenantContext.getCurrentTenant() : session.getTenantId();
    String language = session == null ? "de" : resolveSessionLanguage(session);
    return listReasons(tenantId, language, includeDisabled).stream()
        .filter(reason -> canonicalReasonCode(reason.getCode()).equals(normalized))
        .findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown handover reason"));
  }

  private CaseHandoverReason toReason(CaseHandoverReasonPolicy policy) {
    return CaseHandoverReason.builder()
        .code(canonicalReasonCode(policy.getCode()))
        .label(policy.getLabel())
        .clientConsentRequired(Boolean.TRUE.equals(policy.getClientConsentRequired()))
        .accessAllowed(!Boolean.FALSE.equals(policy.getAccessAllowed()))
        .enabled(Boolean.TRUE.equals(policy.getEnabled()))
        .displayOrder(policy.getDisplayOrder())
        .policyAuthority(policy.getPolicyAuthority())
        .clientNotificationTemplates(policy.getClientNotificationTemplates())
        .maxAccessDurationMinutes(
            ADVICE_NEEDED.equals(canonicalReasonCode(policy.getCode()))
                ? Optional.ofNullable(policy.getMaxAccessDurationMinutes())
                    .orElse(DEFAULT_ADVICE_ACCESS_DURATION_MINUTES)
                : null)
        .build();
  }

  private CaseHandoverReason toReason(
      de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy
          policy,
      String language) {
    String code =
        canonicalReasonCode(policy.getCode() == null ? null : policy.getCode().getValue());
    Map<String, String> labels = valueOf(policy.getLabels());
    Set<String> approvalRoles =
        policy.getApprovalRoles() == null || policy.getApprovalRoles().getValue() == null
            ? Set.of()
            : Set.copyOf(policy.getApprovalRoles().getValue());
    boolean clientConsentRequired =
        booleanValue(policy.getClientConsentRequired(), false) || approvalRoles.contains("CLIENT");
    Integer duration =
        ADVICE_NEEDED.equals(code) && policy.getMaxAccessDurationMinutes() != null
            ? validateMaxAccessDuration(code, policy.getMaxAccessDurationMinutes().getValue())
            : null;
    return CaseHandoverReason.builder()
        .code(code)
        .label(localizedValue(labels, language, code))
        .clientConsentRequired(clientConsentRequired)
        .accessAllowed(booleanValue(policy.getAccessAllowed(), false))
        .enabled(booleanValue(policy.getEnabled(), false))
        .displayOrder(displayOrder(code))
        .policyAuthority("tenant-service-resolved")
        .approvalRoles(approvalRoles)
        .clientNotificationTemplates(valueOf(policy.getClientNotificationTemplates()))
        .maxAccessDurationMinutes(duration)
        .build();
  }

  private boolean booleanValue(
      de.caritas.cob.userservice.tenantadminservice.generated.web.model.BooleanPermissionPolicy
          policy,
      boolean fallback) {
    return policy == null || policy.getValue() == null ? fallback : policy.getValue();
  }

  private Map<String, String> valueOf(
      de.caritas.cob.userservice.tenantadminservice.generated.web.model
              .MultilingualTextPermissionPolicy
          policy) {
    return policy == null || policy.getValue() == null ? Map.of() : Map.copyOf(policy.getValue());
  }

  private String localizedValue(Map<String, String> values, String language, String fallback) {
    if (values.isEmpty()) {
      return fallback;
    }
    return values.getOrDefault(
        language,
        values.getOrDefault("de", values.getOrDefault("en", values.values().iterator().next())));
  }

  private int displayOrder(String code) {
    return switch (code) {
      case ADVICE_NEEDED -> 10;
      case "PLANNED_ABSENCE" -> 20;
      case "OTHER_EMERGENCY" -> 30;
      case "UNPLANNED_ABSENCE" -> 40;
      case "ASSIGNMENT_ENDED" -> 50;
      default -> 100;
    };
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
    policy.setCode(existingPolicy == null ? code : existingPolicy.getCode());
    policy.setLabel(label);
    policy.setClientConsentRequired(reason.isClientConsentRequired());
    policy.setAccessAllowed(isAccessAllowed(reason));
    policy.setEnabled(reason.isEnabled());
    policy.setDisplayOrder(reason.getDisplayOrder() != null ? reason.getDisplayOrder() : 100);
    policy.setPolicyAuthority(
        reason.getPolicyAuthority() == null || reason.getPolicyAuthority().isBlank()
            ? POLICY_AUTHORITY
            : reason.getPolicyAuthority().trim());
    policy.setClientNotificationTemplates(
        sanitizeNotificationTemplates(reason.getClientNotificationTemplates()));
    policy.setMaxAccessDurationMinutes(
        validateMaxAccessDuration(code, reason.getMaxAccessDurationMinutes()));
    policy.setUpdatedAt(now);
    return policy;
  }

  private Map<String, String> sanitizeNotificationTemplates(Map<String, String> templates) {
    if (templates == null || templates.isEmpty()) {
      return null;
    }
    Map<String, String> sanitized = new LinkedHashMap<>();
    templates.forEach(
        (language, template) -> {
          if (language == null || template == null) {
            return;
          }
          var languageKey = language.trim().toLowerCase();
          var text = template.trim();
          if (languageKey.matches("[a-z]{2}") && !text.isBlank()) {
            sanitized.put(languageKey, text);
          }
        });
    return sanitized.isEmpty() ? null : sanitized;
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

  private static String canonicalReasonCode(String reasonCode) {
    String code = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
    return switch (code) {
      case "COUNSELLOR_ASKED_FOR_ADVICE" -> "ADVICE_REQUESTED";
      case "COUNSELLOR_ON_HOLIDAY" -> "PLANNED_ABSENCE";
      case "COUNSELLOR_IS_ILL" -> "UNPLANNED_ABSENCE";
      case "COUNSELLOR_LEFT" -> "ASSIGNMENT_ENDED";
      default -> code;
    };
  }

  private AccessType accessType(String reasonCode) {
    return ADVICE_NEEDED.equals(canonicalReasonCode(reasonCode))
        ? AccessType.CO_ACCESS
        : AccessType.TAKEOVER;
  }

  private AccessType effectiveAccessType(CaseHandoverRequest request) {
    return request.getAccessType() != null
        ? request.getAccessType()
        : accessType(request.getReasonCode());
  }

  private Integer maxAccessDurationMinutes(CaseHandoverReason reason) {
    if (!ADVICE_NEEDED.equals(canonicalReasonCode(reason.getCode()))) {
      return null;
    }
    return reason.getMaxAccessDurationMinutes() != null
        ? reason.getMaxAccessDurationMinutes()
        : DEFAULT_ADVICE_ACCESS_DURATION_MINUTES;
  }

  private LocalDateTime expiresAt(
      CaseHandoverReason reason, Status status, LocalDateTime grantedAt) {
    return status == Status.GRANTED && accessType(reason.getCode()) == AccessType.CO_ACCESS
        ? grantedAt.plusMinutes(maxAccessDurationMinutes(reason))
        : null;
  }

  private Integer validateMaxAccessDuration(String reasonCode, Integer durationMinutes) {
    if (!ADVICE_NEEDED.equals(canonicalReasonCode(reasonCode))) {
      return null;
    }
    int duration =
        durationMinutes == null ? DEFAULT_ADVICE_ACCESS_DURATION_MINUTES : durationMinutes;
    if (duration < 15 || duration % 15 != 0) {
      throw new BadRequestException(
          "Advice Needed access duration must be at least 15 minutes in 15-minute steps");
    }
    return duration;
  }

  private boolean isExpired(CaseHandoverRequest request) {
    return request.getStatus() == Status.GRANTED
        && effectiveAccessType(request) == AccessType.CO_ACCESS
        && request.getExpiresAt() != null
        && !request.getExpiresAt().isAfter(LocalDateTime.now(clock));
  }

  /**
   * Scheduler entrypoint must be {@code void}; the shared scheduler logging advice returns void.
   */
  @Scheduled(
      fixedDelayString = "${case.handover.co-access-sweep-delay-ms:60000}",
      initialDelayString = "${case.handover.co-access-sweep-initial-delay-ms:60000}")
  @Transactional
  public void expireCoAccessSchedule() {
    if (!scheduledTaskClaimService.tryClaim(CO_ACCESS_EXPIRY_TASK, coAccessClaimDuration)) {
      return;
    }
    TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
    try {
      expireCoAccess();
    } finally {
      TenantContext.clear();
    }
  }

  /** Exact persisted expiry sweep; API reads also close the curtain at {@code expiresAt}. */
  @Transactional
  public int expireCoAccess() {
    LocalDateTime now = LocalDateTime.now(clock);
    List<CaseHandoverRequest> expired =
        caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            Status.GRANTED, AccessType.CO_ACCESS, now);
    List<CaseHandoverRequest> revoked = new ArrayList<>();
    expired.forEach(
        request -> {
          if (!removeCoAccessRequesterFromMatrixRoom(request)) {
            log.warn(
                "Could not remove Case Handover requester for request {}; retrying on the next expiry sweep",
                request.getId());
            return;
          }
          request.setStatus(Status.EXPIRED);
          request.setAuditOutcome(OUTCOME_ACCESS_EXPIRED);
          request.setResolvedAt(now);
          revoked.add(request);
        });
    caseHandoverRequestRepository.saveAll(revoked);
    return revoked.size();
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
    boolean expired = isExpired(request);
    AccessType accessType = effectiveAccessType(request);
    return CaseHandoverStatus.builder()
        .requestId(request.getId())
        .sessionId(request.getSession().getId())
        .status(expired ? Status.EXPIRED.name() : request.getStatus().name())
        .canViewContent(request.getStatus() == Status.GRANTED && !expired)
        .reasonCode(request.getReasonCode())
        .reasonLabel(request.getReasonLabel())
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .policyAuthority(request.getPolicyAuthority())
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .accessType(accessType.name())
        .expiresAt(request.getExpiresAt())
        .build();
  }

  private CaseHandoverStatus toClientStatus(CaseHandoverRequest request) {
    return CaseHandoverStatus.builder()
        .requestId(request.getId())
        .sessionId(request.getSession().getId())
        .status(isExpired(request) ? Status.EXPIRED.name() : request.getStatus().name())
        .canViewContent(request.getStatus() == Status.GRANTED && !isExpired(request))
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .accessType(effectiveAccessType(request).name())
        .expiresAt(request.getExpiresAt())
        .build();
  }

  private void notifyGranted(CaseHandoverRequest request) {
    Session session = request.getSession();
    Consultant requester = request.getRequesterConsultant();
    if (effectiveAccessType(request) == AccessType.TAKEOVER) {
      caseHandoverEmailNotification.ownershipGranted(
          request.getId(),
          session.getMatrixRoomId(),
          UUID.fromString(requester.getId()),
          resolveConsultantName(request.getPreviousConsultant()),
          mailTenant(session));
    }
    String requesterName = resolveConsultantName(requester);
    ClientHandoverCopy clientCopy = resolveClientHandoverCopy(session);
    boolean coAccess = effectiveAccessType(request) == AccessType.CO_ACCESS;
    String clientDescription =
        coAccess
            ? DEFAULT_CLIENT_NOTIFICATION_TEMPLATES
                .get(ADVICE_NEEDED)
                .getOrDefault(
                    resolveSessionLanguage(session),
                    DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get(ADVICE_NEEDED).get("de"))
                .replace("{{newAdvisor}}", requesterName)
                .replace(
                    "{{duration}}",
                    formatDuration(
                        request.getMaxAccessDurationMinutes(), resolveSessionLanguage(session)))
            : renderClientCopy(clientCopy.grantedDescription(), requesterName);
    postGrantedChatSystemMessage(session, requesterName, clientDescription);
    // #1010 task 1a: the explanation is counsellor-written free text that can reference case
    // content. It is no longer copied into the notification, which kept it in plaintext for good;
    // the handover-request API serves it on demand instead.
    String params =
        eventNotificationService.buildCaseHandoverParams(
            session, requesterName, request.getReasonCode(), request.getReasonLabel(), null);

    if (session.getUser() != null && session.getUser().getUserId() != null) {
      String clientParams =
          eventNotificationService.buildCaseHandoverParams(
              session, requesterName, null, null, null);
      eventNotificationService.createEvent(
          session.getUser().getUserId(),
          "case.handover.granted",
          EventNotificationService.CATEGORY_SYSTEM,
          coAccess
              ? ("en".equals(resolveSessionLanguage(session))
                  ? "Time-limited case review granted"
                  : "Zeitlich begrenzter Einblick gewährt")
              : clientCopy.grantedTitle(),
          clientDescription,
          clientParams,
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
          coAccess ? "Time-limited case review granted" : "Case handover completed",
          coAccess
              ? String.format(
                  "%s may read case #%s for %d minutes. Reason: %s",
                  requesterName,
                  session.getId(),
                  request.getMaxAccessDurationMinutes(),
                  request.getReasonLabel())
              : String.format(
                  "%s took over case #%s. Reason: %s",
                  requesterName, session.getId(), request.getReasonLabel()),
          params,
          buildConsultantSessionActionPath(session),
          session.getId(),
          session.getTenantId());
    }
  }

  /**
   * Posts the designed in-chat system notification ("new counsellor took over your case") into the
   * session's Matrix room. Emission failures must never fail the handover itself.
   */
  private void postGrantedChatSystemMessage(
      Session session, String requesterName, String description) {
    try {
      matrixSessionSystemMessageService.postCaseHandoverGrantedMessage(
          session, requesterName, description);
    } catch (RuntimeException exception) {
      log.warn(
          "Case-handover system message for session {} could not be posted: {}",
          session.getId(),
          exception.getMessage());
    }
  }

  private ClientHandoverCopy resolveClientHandoverCopy(Session session) {
    var language = resolveSessionLanguage(session);
    return CLIENT_SAFE_HANDOVER_COPY.getOrDefault(language, CLIENT_SAFE_HANDOVER_COPY.get("de"));
  }

  private String renderClientCopy(String template, String requesterName) {
    return template.replace("{{newAdvisor}}", requesterName);
  }

  private String resolveClientNotificationDescription(
      CaseHandoverRequest request, String requesterName) {
    var reasonCode = normalizeReasonCode(request.getReasonCode());
    Map<String, String> templates =
        Optional.ofNullable(
                findReason(request.getSession(), reasonCode, true).getClientNotificationTemplates())
            .filter(map -> !map.isEmpty())
            .orElseGet(() -> DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get(reasonCode));
    if (templates == null || templates.isEmpty()) {
      return null;
    }
    var language = resolveSessionLanguage(request.getSession());
    var template =
        templates.getOrDefault(
            language,
            templates.getOrDefault(
                "de", templates.getOrDefault("en", templates.values().iterator().next())));
    return template
        .replace("{{newAdvisor}}", requesterName)
        .replace("{{duration}}", formatDuration(request.getMaxAccessDurationMinutes(), language));
  }

  private String formatDuration(Integer durationMinutes, String language) {
    int minutes =
        durationMinutes == null ? DEFAULT_ADVICE_ACCESS_DURATION_MINUTES : durationMinutes;
    if (minutes % 60 == 0) {
      int hours = minutes / 60;
      return switch (language) {
        case "de" -> hours + (hours == 1 ? " Stunde" : " Stunden");
        case "fr" -> hours + (hours == 1 ? " heure" : " heures");
        case "ru" -> hours + russianUnitSuffix(hours, " час", " часа", " часов");
        case "tr" -> hours + " saat";
        case "uk" -> hours + ukHourSuffix(hours);
        case "ti" -> hours + (hours == 1 ? " ሰዓት" : " ሰዓታት");
        default -> hours + (hours == 1 ? " hour" : " hours");
      };
    }
    return switch (language) {
      case "de" -> minutes + " Minuten";
      case "fr" -> minutes + (minutes == 1 ? " minute" : " minutes");
      case "ru" -> minutes + russianUnitSuffix(minutes, " минута", " минуты", " минут");
      case "tr" -> minutes + " dakika";
      case "uk" -> minutes + " хвилин";
      case "ti" -> minutes + " ደቓይቕ";
      default -> minutes + " minutes";
    };
  }

  private String russianUnitSuffix(int value, String singular, String paucal, String plural) {
    int mod100 = value % 100;
    int mod10 = value % 10;
    if (mod10 == 1 && mod100 != 11) {
      return singular;
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return paucal;
    }
    return plural;
  }

  private String ukHourSuffix(int hours) {
    int mod100 = hours % 100;
    int mod10 = hours % 10;
    if (mod10 == 1 && mod100 != 11) {
      return " година";
    }
    if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
      return " години";
    }
    return " годин";
  }

  private boolean removeCoAccessRequesterFromMatrixRoom(CaseHandoverRequest request) {
    try {
      Session accessSession = request.getSession();
      Consultant requester = request.getRequesterConsultant();
      if (accessSession == null || isBlank(accessSession.getMatrixRoomId())) {
        return true;
      }
      if (requester == null || isBlank(requester.getMatrixUserId())) {
        return true;
      }
      // The old temporary grant can expire after this advisor acquired independent ownership.
      // Expire its audit row, but retain the membership required by the current owner role.
      if (isActiveOwner(accessSession, requester)) {
        return true;
      }
      String roomId = accessSession.getMatrixRoomId();
      String requesterId = requester.getMatrixUserId();
      var membersBefore = matrixSynapseService.getRoomMembers(roomId);
      if (membersBefore.isPresent() && !membersBefore.get().contains(requesterId)) {
        return true;
      }
      Consultant operator =
          request.getPreviousConsultant() != null
              ? request.getPreviousConsultant()
              : accessSession.getConsultant();
      if (operator == null || isBlank(operator.getMatrixUserId())) {
        return false;
      }
      String operatorToken =
          matrixSynapseService.loginAsUserAccessToken(operator.getMatrixUserId());
      if (isBlank(operatorToken)) {
        return false;
      }
      if (matrixSynapseService.removeUserFromRoom(roomId, requesterId, operatorToken)) {
        return true;
      }
      return matrixSynapseService
          .getRoomMembers(roomId)
          .map(members -> !members.contains(requesterId))
          .orElse(false);
    } catch (RuntimeException exception) {
      log.warn(
          "Could not reconcile Matrix access for Case Handover request {}",
          request.getId(),
          exception);
      return false;
    }
  }

  private String resolveSessionLanguage(Session session) {
    if (session == null || session.getLanguageCode() == null) {
      return "de";
    }
    return session.getLanguageCode().name().toLowerCase(Locale.ROOT);
  }

  private TenantData mailTenant(Session session) {
    TenantData current = TenantContext.getCurrentTenantData();
    return new TenantData(
        session.getTenantId(),
        current != null && java.util.Objects.equals(current.getTenantId(), session.getTenantId())
            ? current.getSubdomain()
            : null);
  }

  private void notifyPendingConsent(CaseHandoverRequest request) {
    Session session = request.getSession();
    if (effectiveAccessType(request) == AccessType.TAKEOVER) {
      caseHandoverEmailNotification.takeoverConsentRequested(
          request.getId(), session.getMatrixRoomId(), mailTenant(session));
    }
    if (session.getUser() == null || session.getUser().getUserId() == null) {
      return;
    }
    String requesterName = resolveConsultantName(request.getRequesterConsultant());
    ClientHandoverCopy clientCopy = resolveClientHandoverCopy(session);
    // The request id remains so the advice seeker can answer the consent prompt. The configured
    // reason and counsellor-written explanation stay staff-only and are never copied into the
    // advice seeker's notification payload.
    eventNotificationService.createEvent(
        session.getUser().getUserId(),
        "case.handover.consent.requested",
        EventNotificationService.CATEGORY_SYSTEM,
        clientCopy.pendingTitle(),
        renderClientCopy(clientCopy.pendingDescription(), requesterName),
        eventNotificationService.buildCaseHandoverParams(
            session, requesterName, null, null, request.getId()),
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
        eventNotificationService.buildCaseHandoverParams(
            session,
            resolveConsultantName(requester),
            request.getReasonCode(),
            request.getReasonLabel(),
            request.getId()),
        buildConsultantSessionActionPath(session),
        session.getId(),
        session.getTenantId());
  }

  private String buildAskerSessionActionPath(Session session) {
    return "/sessions/user/view/session/" + session.getId();
  }

  private String buildConsultantSessionActionPath(Session session) {
    String roomRef = session.getMatrixRoomId();
    return roomRef != null
        ? "/sessions/consultant/sessionView/" + roomRef + "/" + session.getId()
        : null;
  }

  private void ensureRequesterJoinedMatrixRoom(
      Session session, Consultant requester, Consultant previousConsultant) {
    if (session == null || isBlank(session.getMatrixRoomId())) {
      return;
    }
    if (requester == null || isBlank(requester.getMatrixUserId())) {
      throw new InternalServerErrorException(
          "Case handover requester does not have Matrix credentials");
    }
    if (previousConsultant == null || isBlank(previousConsultant.getMatrixUserId())) {
      throw new InternalServerErrorException(
          "Previous consultant does not have Matrix credentials for case handover");
    }

    String roomId = session.getMatrixRoomId();
    String previousConsultantToken =
        matrixSynapseService.loginAsUserAccessToken(previousConsultant.getMatrixUserId());
    if (isBlank(previousConsultantToken)) {
      throw new InternalServerErrorException(
          "Failed to create previous consultant Matrix token for case handover");
    }

    // Since #905 the department's counsellors are already members of the room (ADR-002 §1), so
    // Synapse answers this invite with 403 "<user> is already in the room" — the normal case, not
    // a failure. The join below is the actual assertion, so let it decide. Mirrors the same
    // tolerance in AgencySilentMembershipService.joinSilently.
    try {
      matrixSynapseService.inviteUserToRoom(
          roomId, requester.getMatrixUserId(), previousConsultantToken);
    } catch (Exception exception) {
      log.debug(
          "Invite of case handover requester {} to Matrix room {} did not succeed: {}",
          requester.getUsername(),
          roomId,
          exception.getMessage());
    }

    String requesterToken =
        matrixSynapseService.loginAsUserAccessToken(requester.getMatrixUserId());
    if (isBlank(requesterToken)) {
      throw new InternalServerErrorException(
          "Failed to create requester Matrix token for case handover");
    }

    boolean wasMember =
        matrixSynapseService
            .getRoomMembers(roomId)
            .map(members -> members.contains(requester.getMatrixUserId()))
            .orElse(true);
    boolean joined = matrixSynapseService.joinRoom(roomId, requesterToken);
    if (!joined) {
      throw new InternalServerErrorException(
          "Failed to join case handover requester to Matrix room");
    }

    if (!wasMember && TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              if (status == STATUS_ROLLED_BACK) {
                matrixSynapseService.removeUserFromRoom(
                    roomId, requester.getMatrixUserId(), previousConsultantToken);
              }
            }
          });
    }

    // The previous counsellor deliberately keeps their membership. ADR-002's reveal lifecycle has
    // a takeover re-hide the original counsellor while they stay a member, so they can reclaim the
    // case when they return — and under Megolm a counsellor removed here could never be given the
    // history back. Hiding the conversation is the application curtain's job, not Matrix's.
  }

  private String resolveConsultantName(Consultant consultant) {
    if (consultant == null) {
      return "A counsellor";
    }
    if (consultant.getDisplayName() != null && !consultant.getDisplayName().isBlank()) {
      return decodeUsername(consultant.getDisplayName());
    }
    if (consultant.getFullName() != null && !consultant.getFullName().isBlank()) {
      return consultant.getFullName();
    }
    if (consultant.getUsername() != null && !consultant.getUsername().isBlank()) {
      return decodeUsername(consultant.getUsername());
    }
    return "A counsellor";
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor(access = AccessLevel.PRIVATE)
  public static class CaseHandoverReason {
    private String code;
    private String label;
    private boolean clientConsentRequired;
    private Boolean accessAllowed;
    private boolean enabled;
    private Integer displayOrder;
    private String policyAuthority;
    private Set<String> approvalRoles;
    private Map<String, String> clientNotificationTemplates;
    private Integer maxAccessDurationMinutes;

    @com.fasterxml.jackson.annotation.JsonProperty(
        access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String getAccessType() {
      return ADVICE_NEEDED.equals(canonicalReasonCode(code))
          ? AccessType.CO_ACCESS.name()
          : AccessType.TAKEOVER.name();
    }
  }

  @Data
  @Builder
  public static class CaseHandoverOffer {
    private String accessType;
    private Long offerId;
    private Long sessionId;
    private String status;
    private String direction;
    private String reasonCode;
    private String reasonLabel;
    private String explanation;
    private boolean clientConsentRequired;
    private String fromConsultantId;
    private String fromConsultantName;
    private String targetConsultantId;
    private String targetConsultantName;
    private LocalDateTime createdAt;
    private LocalDateTime offerExpiresAt;
    private LocalDateTime resolvedAt;
  }

  @Data
  @Builder
  public static class CaseHandoverColleague {
    private String consultantId;
    private String username;
    private String displayName;
    private String firstName;
    private String lastName;
    private boolean absent;
  }

  @Data
  @Builder
  public static class CaseHandoverColleagueList {
    private List<CaseHandoverColleague> colleagues;
    private int total;
    private int offset;
    private int count;
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
    private String accessType;
    private LocalDateTime expiresAt;
  }
}
