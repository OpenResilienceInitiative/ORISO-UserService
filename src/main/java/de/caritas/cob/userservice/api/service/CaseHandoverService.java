package de.caritas.cob.userservice.api.service;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionConsultantForConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionUserDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import de.caritas.cob.userservice.api.model.CaseHandoverConsentMode;
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.AccessType;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest.Status;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.CaseHandoverReasonPolicyRepository;
import de.caritas.cob.userservice.api.port.out.CaseHandoverRequestRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.matrix.MatrixSessionSystemMessageService;
import de.caritas.cob.userservice.api.service.notification.EventNotificationService;
import de.caritas.cob.userservice.api.service.session.SessionMapper;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.api.workflow.scheduling.ScheduledTaskClaimService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

  private static final String ADVICE_NEEDED = "COUNSELLOR_ASKED_FOR_ADVICE";
  private static final int DEFAULT_ADVICE_ACCESS_DURATION_MINUTES = 180;
  private static final String POLICY_AUTHORITY = "platform-admin-default-case-handover-policy";
  private static final String TENANT_POLICY_AUTHORITY = "tenant-service-resolved";
  private static final String OUTCOME_ACTIVE_OWNER = "ACTIVE_OWNER";
  private static final String OUTCOME_ACCESS_GRANTED = "ACCESS_GRANTED";
  private static final String OUTCOME_ACCESS_DENIED = "ACCESS_DENIED";
  private static final String OUTCOME_PENDING_CLIENT_CONSENT = "PENDING_CLIENT_CONSENT";
  private static final String OUTCOME_ACCESS_GRANTED_PENDING_CLIENT_OPTOUT =
      "ACCESS_GRANTED_PENDING_CLIENT_OPTOUT";
  private static final String OUTCOME_CLIENT_OPTOUT_CONFIRMED = "CLIENT_OPTOUT_CONFIRMED";
  private static final String OUTCOME_CLIENT_OPTOUT_DECLINED_AFTER_TAKEOVER =
      "CLIENT_OPTOUT_DECLINED_AFTER_TAKEOVER";
  private static final String OUTCOME_CLIENT_CONSENT_DECLINED = "CLIENT_CONSENT_DECLINED";
  private static final String OUTCOME_ACCESS_EXPIRED = "ACCESS_EXPIRED";
  private static final String OUTCOME_ALREADY_ANSWERED = "ALREADY_ANSWERED";
  private static final String OUTCOME_NOT_REQUESTED = "NOT_REQUESTED";
  private static final String CO_ACCESS_EXPIRY_TASK = "case-handover-co-access-expiry";

  private record ClientHandoverCopy(
      String grantedTitle,
      String grantedDescription,
      String pendingTitle,
      String pendingDescription,
      String coAccessGrantedTitle,
      String coAccessGrantedDescription) {}

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
              "{{newAdvisor}} bittet um Zugriff auf deinen Fall. Deine Zustimmung ist erforderlich.",
              "Zeitlich begrenzter Einblick gewährt",
              "Du hast einem zeitlich begrenzten Einblick zugestimmt. {{newAdvisor}} kann diese Sitzung für {{duration}} mitlesen. Deine bisherige Berater:in bleibt für dich zuständig."),
          "en",
          new ClientHandoverCopy(
              "New counsellor took over your case",
              "{{newAdvisor}} has taken over your case and will continue your counselling from now on.",
              "Counsellor access request",
              "{{newAdvisor}} requested access to your case. Your consent is required.",
              "Time-limited review granted",
              "You agreed to a time-limited review. {{newAdvisor}} can read this session for {{duration}}. Your current counsellor remains responsible for you."),
          "fr",
          new ClientHandoverCopy(
              "Un nouveau conseiller ou une nouvelle conseillère a repris votre dossier",
              "{{newAdvisor}} a repris votre dossier et poursuivra désormais votre accompagnement.",
              "Demande d’accès d’un conseiller ou d’une conseillère",
              "{{newAdvisor}} demande l’accès à votre dossier. Votre consentement est requis.",
              "Consultation temporaire accordée",
              "Vous avez accepté une consultation limitée dans le temps. {{newAdvisor}} peut lire cette session pendant {{duration}}. Votre conseiller ou conseillère reste responsable de votre accompagnement."),
          "ru",
          new ClientHandoverCopy(
              "Новый консультант принял ваше дело",
              "{{newAdvisor}} принял(а) ваше дело и с этого момента продолжит консультирование.",
              "Запрос консультанта на доступ",
              "{{newAdvisor}} запросил(а) доступ к вашему делу. Требуется ваше согласие.",
              "Предоставлен временный просмотр",
              "Вы согласились на временный просмотр. {{newAdvisor}} может читать эту сессию в течение {{duration}}. Ваш текущий консультант остаётся ответственным за вас."),
          "tr",
          new ClientHandoverCopy(
              "Yeni bir danışman vakanızı devraldı",
              "{{newAdvisor}} vakanızı devraldı ve bundan sonra danışmanlığınıza devam edecek.",
              "Danışman erişim talebi",
              "{{newAdvisor}} vakanıza erişim istedi. Onayınız gerekiyor.",
              "Süreli inceleme onaylandı",
              "Süreli incelemeyi onayladınız. {{newAdvisor}} bu oturumu {{duration}} boyunca okuyabilir. Mevcut danışmanınız sizden sorumlu olmaya devam eder."),
          "uk",
          new ClientHandoverCopy(
              "Новий консультант перейняв вашу справу",
              "{{newAdvisor}} перейняв(-ла) вашу справу й відтепер продовжуватиме консультування.",
              "Запит консультанта на доступ",
              "{{newAdvisor}} запитує доступ до вашої справи. Потрібна ваша згода.",
              "Тимчасовий перегляд надано",
              "Ви погодилися на тимчасовий перегляд консультації. {{newAdvisor}} може читати цю сесію протягом {{duration}}. Ваш поточний консультант залишається відповідальним за вас."),
          "ti",
          new ClientHandoverCopy(
              "ሓድሽ ኣማኻሪ ጉዳይካ ተረኪቡ",
              "{{newAdvisor}} ጉዳይካ ተረኪቡ ካብ ሕጂ ንደሓር ምኽሪ ክቕጽል እዩ።",
              "ናይ ኣማኻሪ ናይ ምእታው ሕቶ",
              "{{newAdvisor}} ናብ ጉዳይካ ክኣቱ ሓቲቱ። ፍቓድካ የድሊ።",
              "ንዝተወሰነ ግዜ ምርኣይ ተፈቒዱ",
              "ንዝተወሰነ ግዜ ምርኣይ ተሰማሚዕካ። {{newAdvisor}} ነዚ ክፍለ-ግዜ ን{{duration}} ከንብቦ ይኽእል እዩ። ናይ ሕጂ ኣማኻሪኻ ሓላፍነት ይቕጽል።"));

  /**
   * Default client-facing notification templates per reason and language (de/en/tr/uk). Source:
   * vault doc "Case Handover — System-Benachrichtigungen & Rechtstexte (Entwurf)"; tr/uk are
   * machine-drafted pending native review. {@code {{newAdvisor}}} is substituted at send time.
   */
  private static final Map<String, Map<String, String>> DEFAULT_CLIENT_NOTIFICATION_TEMPLATES =
      Map.of(
          "COUNSELLOR_ASKED_FOR_ADVICE",
          Map.of(
              "de",
              "Du hast einem zeitlich begrenzten Einblick zugestimmt. {{newAdvisor}} kann diese Sitzung für {{duration}} mitlesen. Deine bisherige Berater:in bleibt für dich zuständig.",
              "en",
              "You agreed to a time-limited review. {{newAdvisor}} can read this session for {{duration}}. Your current counsellor remains responsible for you.",
              "tr",
              "Süreli incelemeyi onayladınız. {{newAdvisor}} bu oturumu {{duration}} boyunca okuyabilir. Mevcut danışmanınız sizden sorumlu olmaya devam eder.",
              "uk",
              "Ви погодилися на тимчасовий перегляд консультації. {{newAdvisor}} може читати цю сесію протягом {{duration}}. Ваш поточний консультант залишається відповідальним за вас."),
          "COUNSELLOR_ON_HOLIDAY",
          Map.of(
              "de",
              "Deine bisherige Berater:in ist zurzeit abwesend. Während dieser Zeit betreut {{newAdvisor}} deinen Fall.",
              "en",
              "Your previous counsellor is currently away. During this time, {{newAdvisor}} is looking after your case.",
              "tr",
              "Önceki danışmanınız şu anda izinde. Bu süre boyunca vakanızla {{newAdvisor}} ilgilenecek.",
              "uk",
              "Ваш попередній консультант наразі відсутній. У цей час вашою справою опікується {{newAdvisor}}."),
          "OTHER_EMERGENCY",
          Map.of(
              "de",
              "Aus einem dringenden Grund hat {{newAdvisor}} deinen Fall übernommen. Du musst nichts weiter tun.",
              "en",
              "For an urgent reason, {{newAdvisor}} has taken over your case. You don't need to do anything.",
              "tr",
              "Acil bir nedenden dolayı vakanızı {{newAdvisor}} devraldı. Herhangi bir şey yapmanız gerekmiyor.",
              "uk",
              "З невідкладної причини вашу справу перейняв(-ла) {{newAdvisor}}. Вам нічого не потрібно робити."),
          "COUNSELLOR_IS_ILL",
          Map.of(
              "de",
              "Deine bisherige Berater:in ist leider erkrankt. Damit du nicht warten musst, hat {{newAdvisor}} deinen Fall übernommen.",
              "en",
              "Your previous counsellor is unfortunately ill. So you don't have to wait, {{newAdvisor}} has taken over your case.",
              "tr",
              "Önceki danışmanınız maalesef hastalandı. Beklemek zorunda kalmamanız için vakanızı {{newAdvisor}} devraldı.",
              "uk",
              "На жаль, ваш попередній консультант захворів. Щоб вам не довелося чекати, вашу справу перейняв(-ла) {{newAdvisor}}."),
          "COUNSELLOR_LEFT",
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
              .code("COUNSELLOR_ASKED_FOR_ADVICE")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_ASKED_FOR_ADVICE"))
              .label("Advice needed")
              .clientConsent(CaseHandoverConsentMode.OPT_IN)
              .clientConsentRequired(true)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(10)
              .maxAccessDurationMinutes(DEFAULT_ADVICE_ACCESS_DURATION_MINUTES)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_ON_HOLIDAY")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_ON_HOLIDAY"))
              .label("Planned absence")
              .clientConsent(CaseHandoverConsentMode.NONE)
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
              .clientConsent(CaseHandoverConsentMode.NONE)
              .clientConsentRequired(false)
              .accessAllowed(false)
              .enabled(false)
              .displayOrder(30)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_IS_ILL")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_IS_ILL"))
              .label("Unplanned absence")
              .clientConsent(CaseHandoverConsentMode.NONE)
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(40)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_LEFT")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_LEFT"))
              .label("Counsellor does not work here anymore")
              .clientConsent(CaseHandoverConsentMode.NONE)
              .clientConsentRequired(false)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(50)
              .policyAuthority(POLICY_AUTHORITY)
              .build());

  private final @NonNull CaseHandoverRequestRepository caseHandoverRequestRepository;
  private final @NonNull CaseHandoverReasonPolicyRepository caseHandoverReasonPolicyRepository;
  private final @NonNull CaseHandoverPolicyCacheService caseHandoverPolicyCacheService;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantAgencyRepository consultantAgencyRepository;
  private final @NonNull UserAccountService userAccountService;
  private final @NonNull EventNotificationService eventNotificationService;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;
  private final @NonNull ScheduledTaskClaimService scheduledTaskClaimService;
  private final @NonNull Clock clock;

  @Value("${case.handover.co-access-claim-duration:PT2M}")
  private Duration coAccessClaimDuration = Duration.ofMinutes(2);

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
                    CaseHandoverReasonPolicy::getCode,
                    Function.identity(),
                    (first, ignored) -> first));
    LocalDateTime now = LocalDateTime.now(clock);
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
          .clientConsent(CaseHandoverConsentMode.NONE)
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
                .clientConsent(CaseHandoverConsentMode.NONE)
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
        sessionRepository
            .findByAgencyIdInAndConsultantNotAndStatusInAndTeamSessionFalseOrderByUpdateDateDesc(
                agencyIds, requester, statuses);

    List<Session> matchingCandidates =
        candidates.stream()
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
    verifyEligibleSameAgency(session, requester);

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

    CaseHandoverConsentMode clientConsent = effectiveClientConsent(reason);
    boolean clientConsentRequired = clientConsent == CaseHandoverConsentMode.OPT_IN;
    Status status =
        switch (clientConsent) {
          case OPT_IN -> Status.PENDING_CLIENT_CONSENT;
          case OPT_OUT -> Status.GRANTED_PENDING_CLIENT_OPTOUT;
          case NONE -> Status.GRANTED;
        };
    String auditOutcome =
        switch (clientConsent) {
          case OPT_IN -> OUTCOME_PENDING_CLIENT_CONSENT;
          case OPT_OUT -> OUTCOME_ACCESS_GRANTED_PENDING_CLIENT_OPTOUT;
          case NONE -> OUTCOME_ACCESS_GRANTED;
        };

    CaseHandoverRequest request =
        CaseHandoverRequest.builder()
            .session(session)
            .requesterConsultant(requester)
            .previousConsultant(session.getConsultant())
            .reasonCode(reason.getCode())
            .reasonLabel(reason.getLabel())
            .explanation(normalizedExplanation)
            .status(status)
            .clientConsent(clientConsent)
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

    if (hasGrantedAccess(status)) {
      ensureRequesterJoinedMatrixRoom(session, requester, session.getConsultant());
      if (request.getAccessType() == AccessType.TAKEOVER) {
        session.setConsultant(requester);
        session.setUpdateDate(now);
        sessionRepository.save(session);
      }
      notifyGranted(saved);
      if (status == Status.GRANTED_PENDING_CLIENT_OPTOUT) {
        notifyPendingConsent(saved);
      }
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

    boolean optOutDecision = request.getStatus() == Status.GRANTED_PENDING_CLIENT_OPTOUT;
    if (request.getStatus() != Status.PENDING_CLIENT_CONSENT && !optOutDecision) {
      return toClientStatus(request);
    }

    LocalDateTime now = LocalDateTime.now(clock);
    request.setResolvedAt(now);
    if (approved) {
      if (optOutDecision) {
        request.setStatus(Status.GRANTED);
        request.setAuditOutcome(OUTCOME_CLIENT_OPTOUT_CONFIRMED);
        return toClientStatus(caseHandoverRequestRepository.save(request));
      }
      if (hasAlreadyGrantedOrTakenOver(session, request)) {
        request.setStatus(Status.DENIED);
        request.setAuditOutcome(OUTCOME_ALREADY_ANSWERED);
        CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
        return toClientStatus(saved);
      }

      request.setStatus(Status.GRANTED);
      request.setAuditOutcome(OUTCOME_ACCESS_GRANTED);
      request.setAccessType(accessType(request.getReasonCode()));
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
      }
      CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
      notifyGranted(saved);
      return toClientStatus(saved);
    }

    if (optOutDecision && effectiveAccessType(request) == AccessType.CO_ACCESS) {
      if (!removeCoAccessRequesterFromMatrixRoom(request)) {
        throw new InternalServerErrorException("Could not revoke declined Case Handover access");
      }
      request.setStatus(Status.CLIENT_CONSENT_DECLINED);
      request.setAuditOutcome(OUTCOME_CLIENT_CONSENT_DECLINED);
    } else if (optOutDecision) {
      request.setStatus(Status.GRANTED);
      request.setAuditOutcome(OUTCOME_CLIENT_OPTOUT_DECLINED_AFTER_TAKEOVER);
    } else {
      request.setStatus(Status.CLIENT_CONSENT_DECLINED);
      request.setAuditOutcome(OUTCOME_CLIENT_CONSENT_DECLINED);
    }
    CaseHandoverRequest saved = caseHandoverRequestRepository.save(request);
    notifyConsentDeclined(saved);
    return toClientStatus(saved);
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
    return List.of(
                Status.PENDING,
                Status.PENDING_CLIENT_CONSENT,
                Status.GRANTED_PENDING_CLIENT_OPTOUT,
                Status.GRANTED)
            .contains(request.getStatus())
        && !isExpired(request);
  }

  private Optional<CaseHandoverRequest> latestGrantedForOtherRequester(
      Long sessionId, Consultant requester) {
    return java.util.stream.Stream.concat(
            caseHandoverRequestRepository
                .findBySessionIdAndStatusOrderByCreatedAtDesc(sessionId, Status.GRANTED)
                .stream(),
            caseHandoverRequestRepository
                .findBySessionIdAndStatusOrderByCreatedAtDesc(
                    sessionId, Status.GRANTED_PENDING_CLIENT_OPTOUT)
                .stream())
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
    String normalized = reasonCode == null ? "" : reasonCode.trim().toUpperCase(Locale.ROOT);
    Long tenantId = session == null ? TenantContext.getCurrentTenant() : session.getTenantId();
    String language = session == null ? "de" : resolveSessionLanguage(session);
    return listReasons(tenantId, language, includeDisabled).stream()
        .filter(reason -> reason.getCode().equals(normalized))
        .findFirst()
        .orElseThrow(() -> new BadRequestException("Unknown handover reason"));
  }

  private CaseHandoverReason toReason(CaseHandoverReasonPolicy policy) {
    CaseHandoverConsentMode consent =
        policy.getClientConsent() != null
            ? policy.getClientConsent()
            : (Boolean.TRUE.equals(policy.getClientConsentRequired())
                ? CaseHandoverConsentMode.OPT_IN
                : CaseHandoverConsentMode.NONE);
    return CaseHandoverReason.builder()
        .code(policy.getCode())
        .label(policy.getLabel())
        .clientConsent(consent)
        .clientConsentRequired(consent == CaseHandoverConsentMode.OPT_IN)
        .accessAllowed(!Boolean.FALSE.equals(policy.getAccessAllowed()))
        .enabled(Boolean.TRUE.equals(policy.getEnabled()))
        .displayOrder(policy.getDisplayOrder())
        .policyAuthority(policy.getPolicyAuthority())
        .clientNotificationTemplates(policy.getClientNotificationTemplates())
        .maxAccessDurationMinutes(
            ADVICE_NEEDED.equals(policy.getCode())
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
        normalizeReasonCode(policy.getCode() == null ? null : policy.getCode().getValue());
    Map<String, String> labels = valueOf(policy.getLabels());
    Set<String> approvalRoles =
        policy.getApprovalRoles() == null || policy.getApprovalRoles().getValue() == null
            ? Set.of()
            : Set.copyOf(policy.getApprovalRoles().getValue());
    CaseHandoverConsentMode clientConsent = clientConsent(policy, approvalRoles);
    Integer duration =
        ADVICE_NEEDED.equals(code) && policy.getMaxAccessDurationMinutes() != null
            ? validateMaxAccessDuration(code, policy.getMaxAccessDurationMinutes().getValue())
            : null;
    return CaseHandoverReason.builder()
        .code(code)
        .label(localizedValue(labels, language, code))
        .clientConsent(clientConsent)
        .clientConsentRequired(clientConsent == CaseHandoverConsentMode.OPT_IN)
        .accessAllowed(booleanValue(policy.getAccessAllowed(), false))
        .enabled(booleanValue(policy.getEnabled(), false))
        .displayOrder(displayOrder(code))
        .policyAuthority(TENANT_POLICY_AUTHORITY)
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

  private CaseHandoverConsentMode clientConsent(
      de.caritas.cob.userservice.tenantadminservice.generated.web.model.CaseHandoverReasonPolicy
          policy,
      Set<String> approvalRoles) {
    if (policy.getClientConsent() != null && policy.getClientConsent().getValue() != null) {
      return CaseHandoverConsentMode.valueOf(policy.getClientConsent().getValue().getValue());
    }
    return booleanValue(policy.getClientConsentRequired(), false)
            || approvalRoles.contains("CLIENT")
        ? CaseHandoverConsentMode.OPT_IN
        : CaseHandoverConsentMode.NONE;
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
      case "COUNSELLOR_ON_HOLIDAY" -> 20;
      case "OTHER_EMERGENCY" -> 30;
      case "COUNSELLOR_IS_ILL" -> 40;
      case "COUNSELLOR_LEFT" -> 50;
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
    policy.setCode(code);
    policy.setLabel(label);
    CaseHandoverConsentMode clientConsent = effectiveClientConsent(reason);
    policy.setClientConsent(clientConsent);
    policy.setClientConsentRequired(clientConsent == CaseHandoverConsentMode.OPT_IN);
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

  private CaseHandoverConsentMode effectiveClientConsent(CaseHandoverReason reason) {
    if (reason.getClientConsent() != null) {
      return reason.getClientConsent();
    }
    return reason.isClientConsentRequired()
        ? CaseHandoverConsentMode.OPT_IN
        : CaseHandoverConsentMode.NONE;
  }

  private AccessType accessType(String reasonCode) {
    return ADVICE_NEEDED.equals(reasonCode) ? AccessType.CO_ACCESS : AccessType.TAKEOVER;
  }

  private AccessType effectiveAccessType(CaseHandoverRequest request) {
    return request.getAccessType() != null
        ? request.getAccessType()
        : accessType(request.getReasonCode());
  }

  private Integer maxAccessDurationMinutes(CaseHandoverReason reason) {
    if (!ADVICE_NEEDED.equals(reason.getCode())) {
      return null;
    }
    return reason.getMaxAccessDurationMinutes() != null
        ? reason.getMaxAccessDurationMinutes()
        : DEFAULT_ADVICE_ACCESS_DURATION_MINUTES;
  }

  private LocalDateTime expiresAt(
      CaseHandoverReason reason, Status status, LocalDateTime grantedAt) {
    return hasGrantedAccess(status) && accessType(reason.getCode()) == AccessType.CO_ACCESS
        ? grantedAt.plusMinutes(maxAccessDurationMinutes(reason))
        : null;
  }

  private boolean hasGrantedAccess(Status status) {
    return status == Status.GRANTED || status == Status.GRANTED_PENDING_CLIENT_OPTOUT;
  }

  private Integer validateMaxAccessDuration(String reasonCode, Integer durationMinutes) {
    if (!ADVICE_NEEDED.equals(reasonCode)) {
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
    return hasGrantedAccess(request.getStatus())
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
    List<CaseHandoverRequest> expired = new ArrayList<>();
    expired.addAll(
        caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            Status.GRANTED, AccessType.CO_ACCESS, now));
    expired.addAll(
        caseHandoverRequestRepository.findByStatusAndAccessTypeAndExpiresAtLessThanEqual(
            Status.GRANTED_PENDING_CLIENT_OPTOUT, AccessType.CO_ACCESS, now));
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
            .clientConsent(effectiveClientConsent(reason))
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
        .canViewContent(hasGrantedAccess(request.getStatus()) && !expired)
        .reasonCode(request.getReasonCode())
        .reasonLabel(request.getReasonLabel())
        .clientConsent(
            request.getClientConsent() != null
                ? request.getClientConsent()
                : (Boolean.TRUE.equals(request.getClientConsentRequired())
                    ? CaseHandoverConsentMode.OPT_IN
                    : CaseHandoverConsentMode.NONE))
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .policyAuthority(request.getPolicyAuthority())
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .accessType(accessType.name())
        .expiresAt(request.getExpiresAt())
        .build();
  }

  /**
   * Client-scoped view of {@link #toStatus}: the advice seeker sees their own access mode, consent
   * mode and expiry, but never the configured reason, its label or the policy authority (PR #1053).
   */
  private CaseHandoverStatus toClientStatus(CaseHandoverRequest request) {
    boolean expired = isExpired(request);
    AccessType accessType = effectiveAccessType(request);
    return CaseHandoverStatus.builder()
        .requestId(request.getId())
        .sessionId(request.getSession().getId())
        .status(expired ? Status.EXPIRED.name() : request.getStatus().name())
        .canViewContent(hasGrantedAccess(request.getStatus()) && !expired)
        .clientConsent(
            request.getClientConsent() != null
                ? request.getClientConsent()
                : (Boolean.TRUE.equals(request.getClientConsentRequired())
                    ? CaseHandoverConsentMode.OPT_IN
                    : CaseHandoverConsentMode.NONE))
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .accessType(accessType.name())
        .expiresAt(request.getExpiresAt())
        .build();
  }

  private void notifyGranted(CaseHandoverRequest request) {
    Session session = request.getSession();
    Consultant requester = request.getRequesterConsultant();
    String requesterName = resolveConsultantName(requester);
    ClientHandoverCopy clientCopy = resolveClientHandoverCopy(session);
    boolean coAccess = effectiveAccessType(request) == AccessType.CO_ACCESS;
    // Co-access is not a takeover: the client-safe copy names the access mode and duration —
    // never the configured reason (PR #1053 contract) — and the owner stays unchanged.
    String clientTitle = coAccess ? clientCopy.coAccessGrantedTitle() : clientCopy.grantedTitle();
    String clientDescription =
        resolveTenantClientTemplate(request)
            .map(template -> renderClientTemplate(template, requesterName, request, session))
            .orElseGet(
                () ->
                    coAccess
                        ? renderClientCopy(clientCopy.coAccessGrantedDescription(), requesterName)
                            .replace(
                                "{{duration}}",
                                formatDuration(
                                    request.getMaxAccessDurationMinutes(),
                                    resolveSessionLanguage(session)))
                        : renderClientCopy(clientCopy.grantedDescription(), requesterName));
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
          clientTitle,
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

  /**
   * Tenant-configured client notification template for the request's reason, resolved through the
   * last-known-good policy cache. Only a TenantService-resolved policy may override the built-in
   * client-safe copy; the built-in defaults keep the generic wording that the PR #1053 privacy
   * contract asserts. Resolution failures fall back to the built-in copy - a notification must
   * never fail the grant.
   */
  private Optional<String> resolveTenantClientTemplate(CaseHandoverRequest request) {
    try {
      Session requestSession = request.getSession();
      CaseHandoverReason reason = findReason(requestSession, request.getReasonCode(), true);
      if (!TENANT_POLICY_AUTHORITY.equals(reason.getPolicyAuthority())) {
        return Optional.empty();
      }
      Map<String, String> templates = reason.getClientNotificationTemplates();
      if (templates == null || templates.isEmpty()) {
        return Optional.empty();
      }
      String language = resolveSessionLanguage(requestSession);
      String template =
          templates.containsKey(language) ? templates.get(language) : templates.get("de");
      return Optional.ofNullable(template).filter(text -> !text.isBlank());
    } catch (RuntimeException exception) {
      log.warn(
          "Tenant client notification template could not be resolved for request {}; using built-in copy: {}",
          request.getId(),
          exception.getMessage());
      return Optional.empty();
    }
  }

  private String renderClientTemplate(
      String template, String requesterName, CaseHandoverRequest request, Session session) {
    return template
        .replace("{{newAdvisor}}", requesterName)
        .replace(
            "{{duration}}",
            formatDuration(request.getMaxAccessDurationMinutes(), resolveSessionLanguage(session)));
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
      default -> minutes + (minutes == 1 ? " minute" : " minutes");
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

  private void notifyPendingConsent(CaseHandoverRequest request) {
    Session session = request.getSession();
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

    boolean wasMemberBefore =
        matrixSynapseService
            .getRoomMembers(roomId)
            .map(members -> members.contains(requester.getMatrixUserId()))
            .orElse(false);

    boolean joined = matrixSynapseService.joinRoom(roomId, requesterToken);
    if (!joined) {
      throw new InternalServerErrorException(
          "Failed to join case handover requester to Matrix room");
    }
    registerMatrixJoinRollbackCompensation(
        roomId, requester.getMatrixUserId(), previousConsultantToken, wasMemberBefore);

    // The previous counsellor deliberately keeps their membership. ADR-002's reveal lifecycle has
    // a takeover re-hide the original counsellor while they stay a member, so they can reclaim the
    // case when they return — and under Megolm a counsellor removed here could never be given the
    // history back. Hiding the conversation is the application curtain's job, not Matrix's.
  }

  /**
   * The Matrix join above runs inside the granting transaction. If that transaction rolls back
   * after the join (session save, notification write or commit failure), the database grant
   * disappears but the Matrix membership would survive. This compensation removes the requester
   * again on rollback - only when the join actually added them, so a counsellor who was already a
   * room member (ADR-002 department membership) is never kicked out of their own room.
   */
  private void registerMatrixJoinRollbackCompensation(
      String roomId, String requesterId, String operatorToken, boolean wasMemberBefore) {
    if (wasMemberBefore || !TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_ROLLED_BACK) {
              return;
            }
            try {
              if (!matrixSynapseService.removeUserFromRoom(roomId, requesterId, operatorToken)) {
                log.error(
                    "Rolled-back case handover left {} in Matrix room {}; the expiry sweep or manual removal must reconcile it",
                    requesterId,
                    roomId);
              }
            } catch (RuntimeException exception) {
              log.error(
                  "Rolled-back case handover left {} in Matrix room {}; the expiry sweep or manual removal must reconcile it",
                  requesterId,
                  roomId,
                  exception);
            }
          }
        });
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
  @NoArgsConstructor
  @AllArgsConstructor(access = AccessLevel.PRIVATE)
  public static class CaseHandoverReason {
    private String code;
    private String label;
    private CaseHandoverConsentMode clientConsent;
    private boolean clientConsentRequired;
    private Boolean accessAllowed;
    private boolean enabled;
    private Integer displayOrder;
    private String policyAuthority;
    private Set<String> approvalRoles;
    private Map<String, String> clientNotificationTemplates;
    private Integer maxAccessDurationMinutes;
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
    private CaseHandoverConsentMode clientConsent;
    private boolean clientConsentRequired;
    private String policyAuthority;
    private String auditOutcome;
    private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;
    private String accessType;
    private LocalDateTime expiresAt;
  }
}
