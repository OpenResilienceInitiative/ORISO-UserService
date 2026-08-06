package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import ch.qos.logback.classic.Level;
import de.caritas.cob.userservice.api.service.statistics.event.AssignSessionStatisticsEvent;
import de.caritas.cob.userservice.statisticsservice.generated.web.model.EventType;
import de.caritas.cob.userservice.testutils.LogbackCaptor;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatisticsServiceTest {

  private static final String FIELD_NAME_STATISTICS_ENABLED = "statisticsEnabled";
  private static final String FIELD_NAME_RABBIT_EXCHANGE_NAME = "rabbitMqExchangeName";
  private static final String RABBIT_EXCHANGE_NAME = "exchange";
  private static final String PAYLOAD = "payload";

  private AssignSessionStatisticsEvent assignSessionStatisticsEvent;
  private final EventType eventType = EventType.ASSIGN_SESSION;

  @InjectMocks private StatisticsService statisticsService;
  @Mock private AmqpTemplate amqpTemplate;

  @BeforeEach
  void setup() {
    assignSessionStatisticsEvent = Mockito.mock(AssignSessionStatisticsEvent.class);
    when(assignSessionStatisticsEvent.getEventType()).thenReturn(eventType);
    when(assignSessionStatisticsEvent.getPayload()).thenReturn(Optional.of(PAYLOAD));
    setField(statisticsService, FIELD_NAME_RABBIT_EXCHANGE_NAME, RABBIT_EXCHANGE_NAME);
  }

  @Test
  void fireEvent_Should_NotSendStatisticsMessage_WhenStatisticsIsDisabled() {

    setField(statisticsService, FIELD_NAME_STATISTICS_ENABLED, false);
    statisticsService.fireEvent(assignSessionStatisticsEvent);
    verify(amqpTemplate, times(0))
        .convertAndSend(eq(RABBIT_EXCHANGE_NAME), anyString(), anyString());
  }

  @Test
  void fireEvent_Should_SendStatisticsMessage_WhenStatisticsIsEnabled() {

    setField(statisticsService, FIELD_NAME_STATISTICS_ENABLED, true);
    when(assignSessionStatisticsEvent.getEventType()).thenReturn(eventType);
    when(assignSessionStatisticsEvent.getPayload()).thenReturn(Optional.of(PAYLOAD));

    statisticsService.fireEvent(assignSessionStatisticsEvent);
    verify(amqpTemplate, times(1))
        .convertAndSend(eq(RABBIT_EXCHANGE_NAME), anyString(), eq(buildPayloadMessage()));
  }

  @Test
  void fireEvent_Should_LogWarning_WhenPayloadIsEmpty() {

    setField(statisticsService, FIELD_NAME_STATISTICS_ENABLED, true);
    when(assignSessionStatisticsEvent.getPayload()).thenReturn(Optional.empty());
    try (var logCaptor = LogbackCaptor.forClass(StatisticsService.class)) {
      statisticsService.fireEvent(assignSessionStatisticsEvent);
      assertThat(logCaptor.contains(Level.WARN, "Empty statistics event message payload")).isTrue();
    }
  }

  @Test
  void fireEvent_Should_UseEventTypeAsTopicAndSendPayloadOfEvent() {

    setField(statisticsService, FIELD_NAME_STATISTICS_ENABLED, true);
    statisticsService.fireEvent(assignSessionStatisticsEvent);
    verify(amqpTemplate, times(1))
        .convertAndSend(RABBIT_EXCHANGE_NAME, eventType.toString(), buildPayloadMessage());
  }

  private org.springframework.amqp.core.Message buildPayloadMessage() {
    return MessageBuilder.withBody(PAYLOAD.getBytes(StandardCharsets.UTF_8))
        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
        .build();
  }
}
