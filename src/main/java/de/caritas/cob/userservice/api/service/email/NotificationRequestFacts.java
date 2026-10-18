package de.caritas.cob.userservice.api.service.email;

import static org.apache.commons.lang3.StringUtils.isBlank;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Factual request metadata shared by the existing producer and the new notification composer. */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRequestFacts {

  private static final DateTimeFormatter UTC_TIMESTAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm 'UTC'");
  private final @NonNull TopicService topicService;

  public List<TemplateDataDTO> forSession(Session session) {
    if (session == null) {
      throw new IllegalArgumentException("Notification session is missing");
    }
    List<TemplateDataDTO> facts = new ArrayList<>();
    String topic = topicName(session);
    if (topic != null) {
      facts.add(new TemplateDataDTO().key("requestTopic").value(topic));
    }
    if (session.getEnquiryMessageDate() != null) {
      facts.add(
          new TemplateDataDTO()
              .key("requestReceivedAt")
              .value(UTC_TIMESTAMP.format(session.getEnquiryMessageDate())));
    }
    if (!isBlank(session.getPostcode())) {
      facts.add(new TemplateDataDTO().key("requestPostcode").value(session.getPostcode()));
    }
    if (session.getTenantId() != null) {
      facts.add(new TemplateDataDTO().key("tenantId").value(session.getTenantId().toString()));
    }
    return facts;
  }

  private String topicName(Session session) {
    if (session.getMainTopicId() == null) {
      return null;
    }
    try {
      var topic = topicService.getTopicById(session.getMainTopicId());
      return topic == null || isBlank(topic.getName()) ? null : topic.getName();
    } catch (RuntimeException lookupFailure) {
      log.warn(
          "Notification topic lookup failed for topic id {}: {}",
          session.getMainTopicId(),
          lookupFailure.getClass().getSimpleName());
      return null;
    }
  }
}
