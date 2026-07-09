package de.caritas.cob.userservice.api.service.consultingtype;

import de.caritas.cob.userservice.api.Messenger;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
  private final @NonNull Messenger messenger;
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

    List<String> topicConsultantIds = consultantTopicRepository.findConsultantIdsByTopicId(topicId);
    if (topicConsultantIds.isEmpty()) {
      return Collections.emptyList();
    }

    List<String> activeConsultantIds =
        consultantRepository.findAllByIdIn(topicConsultantIds).stream()
            .filter(consultant -> consultant != null && !consultant.isAbsent())
            .map(Consultant::getId)
            .collect(Collectors.toList());
    if (activeConsultantIds.isEmpty()) {
      return Collections.emptyList();
    }

    return new ArrayList<>(
        consultantActivityRegistry.filterActive(activeConsultantIds, activeWindowMs));
  }

  public List<String> findEligibleConsultantIds(Long topicId, Integer consultingTypeId) {
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

    // Matrix presence unavailable (disabled / no admin token / all lookups failed) — fall back to
    // the legacy RocketChat presence signal, best-effort, as before.
    if (consultingTypeId == null) {
      return activeConsultantIds;
    }

    Set<String> onlineConsultantIds;
    try {
      onlineConsultantIds = messenger.findAvailableConsultants(consultingTypeId);
    } catch (Exception ex) {
      return activeConsultantIds;
    }
    if (onlineConsultantIds.isEmpty()) {
      return activeConsultantIds;
    }

    List<String> onlineTopicConsultantIds =
        consultants.stream()
            .filter(consultant -> consultant != null && !consultant.isAbsent())
            .filter(
                consultant ->
                    onlineConsultantIds.contains(consultant.getId())
                        || (consultant.getRocketChatId() != null
                            && onlineConsultantIds.contains(consultant.getRocketChatId())))
            .map(Consultant::getId)
            .collect(Collectors.toList());

    // RocketChat presence is best-effort during Matrix migration — do not drop all
    // topic consultants when the online set is populated but none match.
    if (onlineTopicConsultantIds.isEmpty()) {
      return activeConsultantIds;
    }

    return onlineTopicConsultantIds;
  }
}
