package de.caritas.cob.userservice.api.service.session;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSessionDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionTopicDTO;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnExpression("${feature.topics.enabled:true}")
@Slf4j
public class ConsultantSessionTopicEnrichmentService {

  private final @NonNull TopicService topicService;

  /**
   * Enriches the main topic and topic list with one shared topic lookup.
   *
   * @param session consultant-facing session response
   * @return the enriched response
   */
  public ConsultantSessionDTO enrichSessionWithTopicData(ConsultantSessionDTO session) {
    boolean enrichMainTopic = shouldEnrichMainTopic(session);
    boolean enrichTopics = shouldEnrichTopics(session);
    if (!enrichMainTopic && !enrichTopics) {
      log.debug("Skipping topic enrichment for session with id: {}", session.getId());
      return session;
    }

    var availableTopics = topicService.getAllTopicsMap();
    if (enrichMainTopic) {
      enrichMainTopicTo(session, availableTopics);
    }
    if (enrichTopics) {
      enrichTopicsTo(session, availableTopics);
    }
    return session;
  }

  private boolean shouldEnrichMainTopic(ConsultantSessionDTO session) {
    return session.getMainTopic() != null && session.getMainTopic().getId() != null;
  }

  private boolean shouldEnrichTopics(ConsultantSessionDTO session) {
    return session.getTopics() != null && !session.getTopics().isEmpty();
  }

  private void enrichMainTopicTo(
      ConsultantSessionDTO session, Map<Long, TopicDTO> availableTopics) {
    if (nonNull(session.getMainTopic())) {
      var mainTopic = getTopicData(availableTopics, session.getMainTopic().getId());
      if (nonNull(mainTopic)) {
        session.setMainTopic(mainTopic);
      }
    }
  }

  private void enrichTopicsTo(ConsultantSessionDTO session, Map<Long, TopicDTO> availableTopics) {
    if (nonNull(session.getTopics())) {
      var topics =
          session.getTopics().stream()
              .map(sessionTopic -> getTopicData(availableTopics, sessionTopic.getId()))
              .filter(Objects::nonNull)
              .collect(Collectors.toList());
      session.setTopics(topics);
    }
  }

  private SessionTopicDTO getTopicData(Map<Long, TopicDTO> availableTopics, Long topicId) {
    var topicData = availableTopics.get(topicId);
    if (topicData != null) {
      return convertToSessionTopicDTO(topicData);
    } else {
      log.warn("Did not find matching topic for id: {} in the available topic list", topicId);
    }
    return null;
  }

  private SessionTopicDTO convertToSessionTopicDTO(TopicDTO source) {
    SessionTopicDTO sessionTopicDTO = new SessionTopicDTO();
    sessionTopicDTO.setId(source.getId());
    sessionTopicDTO.setDescription(source.getDescription());
    sessionTopicDTO.setName(source.getName());
    sessionTopicDTO.setStatus(source.getStatus());
    return sessionTopicDTO;
  }
}
