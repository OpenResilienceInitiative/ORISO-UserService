package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class TopicConsultantAvailabilityControllerTest {

  private static final Long TOPIC_ID = 7L;

  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;

  @InjectMocks private TopicConsultantAvailabilityController controller;

  @Test
  void getTopicConsultantAvailability_consultantsFound_returnsAvailableWithCount() {
    // Business reason: anonymous users need a positive signal before starting a live chat.
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID))
        .thenReturn(List.of("c-1", "c-2", "c-3"));

    var response = controller.getTopicConsultantAvailability(TOPIC_ID, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue(response.getBody().isAvailable());
    assertEquals(3, response.getBody().getNumAvailableConsultants());
  }

  @Test
  void getTopicConsultantAvailability_emptyList_returnsUnavailableWithZeroCount() {
    // Business reason: empty routing result must trigger the "no counsellor available" alert.
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID)).thenReturn(List.of());

    var response = controller.getTopicConsultantAvailability(TOPIC_ID, 5);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertFalse(response.getBody().isAvailable());
    assertEquals(0, response.getBody().getNumAvailableConsultants());
  }

  @Test
  void getTopicConsultantAvailability_serviceThrows_returnsUnavailableWithZeroCount() {
    // Business reason: routing failures on a public endpoint must degrade gracefully to
    // unavailable.
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID))
        .thenThrow(new RuntimeException("routing unavailable"));

    var response = controller.getTopicConsultantAvailability(TOPIC_ID, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertFalse(response.getBody().isAvailable());
    assertEquals(0, response.getBody().getNumAvailableConsultants());
  }

  @Test
  void getTopicConsultantAvailability_consultingTypeIdIgnored_onlyTopicIdPassedToService() {
    // Business reason: consultingTypeId is accepted but unused — only topicId drives routing today.
    ArgumentCaptor<Long> topicIdCaptor = ArgumentCaptor.forClass(Long.class);
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID))
        .thenReturn(List.of("c-1"));

    controller.getTopicConsultantAvailability(TOPIC_ID, 99);

    verify(topicConsultantRoutingService).findAvailableConsultantIds(topicIdCaptor.capture());
    assertEquals(TOPIC_ID, topicIdCaptor.getValue());
  }
}
