package de.caritas.cob.userservice.api.conversation.provider;

import static de.caritas.cob.userservice.api.conversation.model.ConversationListType.ANONYMOUS_ENQUIRY;
import static de.caritas.cob.userservice.api.model.Session.RegistrationType.ANONYMOUS;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionListResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionResponseDTO;
import de.caritas.cob.userservice.api.conversation.model.ConversationListType;
import de.caritas.cob.userservice.api.conversation.model.PageableListRequest;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.session.SessionMapper;
import de.caritas.cob.userservice.api.service.sessionlist.ConsultantSessionEnricher;
import de.caritas.cob.userservice.api.service.user.UserAccountService;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ConversationListProvider} to provide anonymous enquiry conversations. */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnonymousEnquiryConversationListProvider implements ConversationListProvider {

  private final @NonNull UserAccountService userAccountProvider;
  private final @NonNull SessionRepository sessionRepository;
  private final @NonNull ConsultantSessionEnricher consultantSessionEnricher;
  private final @NonNull ConsultantTopicRepository consultantTopicRepository;

  @Value("${user.anonymous.deactivateworkflow.periodMinutes}")
  private long liveChatQueueActivePeriodMinutes;

  /** {@inheritDoc} */
  @Override
  @Transactional(readOnly = true)
  public ConsultantSessionListResponseDTO buildConversations(
      PageableListRequest pageableListRequest) {
    var consultant = this.userAccountProvider.retrieveValidatedConsultant();
    List<Long> consultantTopicIds =
        consultantTopicRepository.findTopicIdsByConsultantId(consultant.getId());

    Page<Session> anonymousSessionsOfConsultant =
        queryForRelevantSessions(pageableListRequest, consultantTopicIds);

    List<ConsultantSessionResponseDTO> sessions =
        anonymousSessionsOfConsultant.stream()
            .map(session -> new SessionMapper().toConsultantSessionDto(session))
            .collect(Collectors.toList());

    // The queue must stay visible even when enrichment (chat room info, last message,
    // topic details) fails — an un-enriched card can still be accepted, an empty queue
    // cannot. Without this guard a single enrichment error 500s the endpoint and the
    // frontend treats the anonymous feed as empty.
    try {
      this.consultantSessionEnricher.updateRequiredConsultantSessionValues(
          sessions, pageableListRequest.getRcToken(), consultant);
    } catch (Exception e) {
      log.error(
          "Anonymous enquiry enrichment failed for consultant {} — returning {} un-enriched queue entries",
          consultant.getId(),
          sessions.size(),
          e);
    }

    return new ConsultantSessionListResponseDTO()
        .sessions(sessions)
        .count(sessions.size())
        .offset(pageableListRequest.getOffset())
        .total((int) anonymousSessionsOfConsultant.getTotalElements());
  }

  private Page<Session> queryForRelevantSessions(
      PageableListRequest pageableListRequest, List<Long> consultantTopicIds) {
    var requestedPage = obtainPageByOffsetAndCount(pageableListRequest);
    var pageable = PageRequest.of(requestedPage, pageableListRequest.getCount());

    // The anonymous Live Chat queue is topic-bound: a consultant sees anonymous enquiries for the
    // topics they are assigned to. A consultant with no topic assignment cannot serve the topic
    // queue, so return nothing without any cross-service lookup. This deliberately does NOT resolve
    // the consultant's consulting types via AgencyService — the queue is topic-only, which also
    // avoids an authenticated AgencyService call from this list path.
    if (consultantTopicIds == null || consultantTopicIds.isEmpty()) {
      return Page.empty(pageable);
    }

    var minUpdateDate = LocalDateTime.now().minusMinutes(liveChatQueueActivePeriodMinutes);

    // The queue is deliberately cross-agency AND cross-tenant: a consultant who is live for a topic
    // must see anonymous enquiries for that topic regardless of the asker's tenant. The
    // consultant's
    // own topics were resolved in the caller's tenant context; only the visibility query itself
    // must
    // bypass the tenant filter. Running it in technical context makes TenantAspect disable the
    // Hibernate tenantFilter; the caller's tenant is restored afterwards so no other query in this
    // request leaks. Registered (non-anonymous) session queries stay strictly tenant-isolated.
    return runCrossTenant(
        () ->
            this.sessionRepository.findAnonymousEnquiriesVisibleForConsultantsByTopicsOnly(
                new HashSet<>(consultantTopicIds),
                SessionStatus.NEW,
                minUpdateDate,
                ANONYMOUS,
                pageable));
  }

  private Page<Session> runCrossTenant(java.util.function.Supplier<Page<Session>> query) {
    var callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      return query.get();
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public ConversationListType providedType() {
    return ANONYMOUS_ENQUIRY;
  }
}
