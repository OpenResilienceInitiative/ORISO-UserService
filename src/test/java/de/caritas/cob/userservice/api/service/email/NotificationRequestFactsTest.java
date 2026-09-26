package de.caritas.cob.userservice.api.service.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.mailservice.generated.web.model.TemplateDataDTO;
import de.caritas.cob.userservice.topicservice.generated.web.model.TopicDTO;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationRequestFactsTest {

  @Mock TopicService topicService;
  @InjectMocks NotificationRequestFacts facts;

  @Test
  void usesTenantScopedTopicAndUtcRequestDate() {
    var session = new Session();
    session.setMainTopicId(7L);
    session.setEnquiryMessageDate(LocalDateTime.of(2026, 9, 25, 13, 45));
    session.setPostcode("12345");
    session.setTenantId(9L);
    when(topicService.getTopicById(7L)).thenReturn(new TopicDTO().id(7L).name("Housing"));

    assertThat(values(facts.forSession(session)))
        .containsEntry("requestTopic", "Housing")
        .containsEntry("requestReceivedAt", "25.09.2026 13:45 UTC")
        .containsEntry("requestPostcode", "12345")
        .containsEntry("tenantId", "9");
  }

  @Test
  void unavailableFactsAreOmittedRatherThanInvented() {
    var session = new Session();
    session.setMainTopicId(7L);

    assertThat(values(facts.forSession(session)))
        .doesNotContainKeys("requestTopic", "requestReceivedAt", "requestPostcode");
  }

  private static Map<String, String> values(java.util.List<TemplateDataDTO> attributes) {
    return attributes.stream()
        .collect(Collectors.toMap(TemplateDataDTO::getKey, TemplateDataDTO::getValue));
  }
}
