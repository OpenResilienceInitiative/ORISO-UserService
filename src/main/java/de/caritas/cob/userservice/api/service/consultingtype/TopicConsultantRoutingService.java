package de.caritas.cob.userservice.api.service.consultingtype;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Resolves consultants eligible to receive topic-scoped anonymous live-chat enquiries. */
@Service
@RequiredArgsConstructor
public class TopicConsultantRoutingService {

  private final @NonNull ConsultantTopicRepository consultantTopicRepository;
  private final @NonNull ConsultantRepository consultantRepository;
  private final @NonNull MatrixSynapseService matrixSynapseService;
  private final @NonNull ConsultantActivityRegistry consultantActivityRegistry;

  @Value("${consultant.availability.activeWindowMs:120000}")
  private long activeWindowMs;

  /**
   * Returns consultant IDs assigned to the topic who are not absent. When the platform reports
   * online consultants for the consulting type, the result is intersected with that set.
   */
  /**
   * Consultants currently <em>available</em> to take a new topic-scoped anonymous live chat:
   * assigned to the topic, not absent, and seen active (app open) within the configured window.
   *
   * <p>This is the signal the live-chat UI should display. Unlike {@link
   * #findEligibleConsultantIds} it does not fall back to "everyone assigned" — an empty result
   * means genuinely nobody is reachable right now, which is exactly what the asker needs to know.
   */
  public List<String> findAvailableConsultantIds(Long topicId) {
    if (topicId == null) {
      return Collections.emptyList();
    }

    List<String> activeConsultantIds =
        runCrossTenant(
            () -> {
              List<String> topicConsultantIds =
                  consultantTopicRepository.findConsultantIdsByTopicId(topicId);
              if (topicConsultantIds.isEmpty()) {
                return Collections.emptyList();
              }
              return consultantRepository.findAllByIdIn(topicConsultantIds).stream()
                  .filter(consultant -> consultant != null && !consultant.isAbsent())
                  .map(Consultant::getId)
                  .collect(Collectors.toList());
            });
    if (activeConsultantIds.isEmpty()) {
      return Collections.emptyList();
    }

    return new ArrayList<>(
        consultantActivityRegistry.filterActive(activeConsultantIds, activeWindowMs));
  }

  /**
   * Public topic availability is deliberately cross-tenant: one published Live Chat link feeds a
   * shared topic queue. Disable the tenant filter only while resolving eligible consultant IDs and
   * always restore the caller context before applying the in-memory activity filter.
   */
  private <T> T runCrossTenant(Supplier<T> lookup) {
    Long callerTenant = TenantContext.getCurrentTenant();
    try {
      TenantContext.setCurrentTenant(TenantContext.TECHNICAL_TENANT_ID);
      return lookup.get();
    } finally {
      if (callerTenant == null) {
        TenantContext.clear();
      } else {
        TenantContext.setCurrentTenant(callerTenant);
      }
    }
  }

  public List<String> findEligibleConsultantIds(Long topicId) {
    if (topicId == null) {
      return Collections.emptyList();
    }

    List<String> topicConsultantIds = consultantTopicRepository.findConsultantIdsByTopicId(topicId);
    if (topicConsultantIds.isEmpty()) {
      return Collections.emptyList();
    }

    List<Consultant> consultants = consultantRepository.findAllByIdIn(topicConsultantIds);
    List<String> activeConsultantIds =
        consultants.stream()
            .filter(consultant -> consultant != null && !consultant.isAbsent())
            .map(Consultant::getId)
            .collect(Collectors.toList());

    if (activeConsultantIds.isEmpty()) {
      return Collections.emptyList();
    }

    // Primary signal: real-time Matrix presence of the topic's (non-absent) consultants.
    List<String> candidateMatrixUserIds =
        consultants.stream()
            .filter(consultant -> consultant != null && !consultant.isAbsent())
            .map(Consultant::getMatrixUserId)
            .filter(matrixUserId -> matrixUserId != null && !matrixUserId.isBlank())
            .collect(Collectors.toList());

    Optional<Set<String>> onlineMatrixUserIds =
        candidateMatrixUserIds.isEmpty()
            ? Optional.empty()
            : matrixSynapseService.findOnlineMatrixUserIds(candidateMatrixUserIds);

    if (onlineMatrixUserIds.isPresent()) {
      // Authoritative answer from Matrix — trust it even when empty (genuinely nobody online).
      Set<String> online = onlineMatrixUserIds.get();
      return consultants.stream()
          .filter(consultant -> consultant != null && !consultant.isAbsent())
          .filter(
              consultant ->
                  consultant.getMatrixUserId() != null
                      && online.contains(consultant.getMatrixUserId()))
          .map(Consultant::getId)
          .collect(Collectors.toList());
    }

    // Presence may be disabled or temporarily unavailable. Assignment remains possible for all
    // non-absent topic consultants; the stricter public availability indicator uses the activity
    // registry in findAvailableConsultantIds.
    return activeConsultantIds;
  }
}
