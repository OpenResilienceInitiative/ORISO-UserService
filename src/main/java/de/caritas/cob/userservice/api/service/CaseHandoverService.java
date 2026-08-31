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
import de.caritas.cob.userservice.api.model.CaseHandoverReasonPolicy;
import de.caritas.cob.userservice.api.model.CaseHandoverRequest;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CaseHandoverService {

  private static final String POLICY_AUTHORITY = "platform-admin-default-case-handover-policy";
  private static final String OUTCOME_ACTIVE_OWNER = "ACTIVE_OWNER";
  private static final String OUTCOME_ACCESS_GRANTED = "ACCESS_GRANTED";
  private static final String OUTCOME_ACCESS_DENIED = "ACCESS_DENIED";
  private static final String OUTCOME_PENDING_CLIENT_CONSENT = "PENDING_CLIENT_CONSENT";
  private static final String OUTCOME_CLIENT_CONSENT_DECLINED = "CLIENT_CONSENT_DECLINED";
  private static final String OUTCOME_ALREADY_ANSWERED = "ALREADY_ANSWERED";
  private static final String OUTCOME_NOT_REQUESTED = "NOT_REQUESTED";

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
  private static final Map<String, Map<String, String>> DEFAULT_CLIENT_NOTIFICATION_TEMPLATES =
      Map.of(
          "COUNSELLOR_ASKED_FOR_ADVICE",
          Map.of(
              "de",
              "Du hast der Fallübergabe zugestimmt. {{newAdvisor}} hat deinen Fall übernommen und führt deine Beratung ab jetzt weiter.",
              "en",
              "You agreed to the case handover. {{newAdvisor}} has taken over your case and will continue your counselling from now on.",
              "tr",
              "Vaka devrine onay verdiniz. {{newAdvisor}} vakanızı devraldı ve bundan sonra danışmanlığınızı sürdürecek.",
              "uk",
              "Ви погодилися на передачу справи. {{newAdvisor}} перейняв(-ла) вашу справу й відтепер продовжуватиме консультування."),
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
              .label("Counsellor asked for advice")
              .clientConsentRequired(true)
              .accessAllowed(true)
              .enabled(true)
              .displayOrder(10)
              .policyAuthority(POLICY_AUTHORITY)
              .build(),
          CaseHandoverReason.builder()
              .code("COUNSELLOR_ON_HOLIDAY")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_ON_HOLIDAY"))
              .label("Counsellor is on holiday")
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
              .code("COUNSELLOR_IS_ILL")
              .clientNotificationTemplates(
                  DEFAULT_CLIENT_NOTIFICATION_TEMPLATES.get("COUNSELLOR_IS_ILL"))
              .label("Counsellor is ill")
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
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull MatrixSessionSystemMessageService matrixSessionSystemMessageService;

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
      ensureRequesterJoinedMatrixRoom(session, requester, session.getConsultant());
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
      return toClientStatus(request);
    }

    LocalDateTime now = LocalDateTime.now();
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
      ensureRequesterJoinedMatrixRoom(
          session, request.getRequesterConsultant(), request.getPreviousConsultant());
      session.setConsultant(request.getRequesterConsultant());
      session.setUpdateDate(now);
      sessionRepository.save(session);
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
        .clientNotificationTemplates(policy.getClientNotificationTemplates())
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
    policy.setClientNotificationTemplates(
        sanitizeNotificationTemplates(reason.getClientNotificationTemplates()));
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

  private CaseHandoverStatus toClientStatus(CaseHandoverRequest request) {
    return CaseHandoverStatus.builder()
        .requestId(request.getId())
        .sessionId(request.getSession().getId())
        .status(request.getStatus().name())
        .canViewContent(request.getStatus() == Status.GRANTED)
        .clientConsentRequired(Boolean.TRUE.equals(request.getClientConsentRequired()))
        .auditOutcome(request.getAuditOutcome())
        .createdAt(request.getCreatedAt())
        .resolvedAt(request.getResolvedAt())
        .build();
  }

  private void notifyGranted(CaseHandoverRequest request) {
    Session session = request.getSession();
    Consultant requester = request.getRequesterConsultant();
    String requesterName = resolveConsultantName(requester);
    ClientHandoverCopy clientCopy = resolveClientHandoverCopy(session);
    String clientDescription = renderClientCopy(clientCopy.grantedDescription(), requesterName);
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
          clientCopy.grantedTitle(),
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
          "Case handover completed",
          String.format(
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

    boolean joined = matrixSynapseService.joinRoom(roomId, requesterToken);
    if (!joined) {
      throw new InternalServerErrorException(
          "Failed to join case handover requester to Matrix room");
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
    private boolean clientConsentRequired;
    private Boolean accessAllowed;
    private boolean enabled;
    private Integer displayOrder;
    private String policyAuthority;
    private Map<String, String> clientNotificationTemplates;
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
