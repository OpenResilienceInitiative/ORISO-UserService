package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics;
import de.caritas.cob.userservice.api.config.observability.LiveChatDiagnosticMetrics.AvailabilityEndpoint;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import de.caritas.cob.userservice.api.service.availability.ConsultantActivityRegistry;
import de.caritas.cob.userservice.api.service.consultingtype.TopicConsultantRoutingService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class TopicConsultantAvailabilityControllerTest {

  private static final Long TOPIC_ID = 7L;

  @Mock private TopicConsultantRoutingService topicConsultantRoutingService;
  @Mock private LiveChatDiagnosticMetrics liveChatDiagnosticMetrics;

  @InjectMocks private TopicConsultantAvailabilityController controller;

  private Logger logger;
  private ListAppender<ILoggingEvent> logAppender;

  @BeforeEach
  void captureLog() {
    logger = (Logger) LoggerFactory.getLogger(TopicConsultantAvailabilityController.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    logger.addAppender(logAppender);
  }

  @AfterEach
  void releaseLog() {
    logger.detachAppender(logAppender);
  }

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

  @Test
  void getTopicConsultantAvailability_serviceThrows_warnsAndCountsTheSwallowedFailure() {
    // Business reason (#1270): a failed lookup is reported to the guest as "nobody is live".
    // Without a log line and a counter nobody can tell a real zero from a swallowed failure.
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID))
        .thenThrow(new IllegalStateException("redis down for guest anon_4711@example.org"));

    controller.getTopicConsultantAvailability(TOPIC_ID, null);

    assertThat(logAppender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains("topicId=7")
                  .contains("IllegalStateException");
              // KDG: neither the exception message nor a stack trace may carry personal data.
              assertThat(event.getFormattedMessage()).doesNotContain("anon_4711");
              assertThat(event.getThrowableProxy()).isNull();
            });
    verify(liveChatDiagnosticMetrics)
        .recordAvailabilityLookupFailure(AvailabilityEndpoint.ANONYMOUS_DOOR);
  }

  @Test
  void getTopicConsultantAvailability_redisReadFails_countsActualRegistryFailure() {
    var redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.multiGet(List.of("availability:c-1")))
        .thenThrow(new RedisConnectionFailureException("private connection detail"));
    var topics = mock(ConsultantTopicRepository.class);
    var consultants = mock(ConsultantRepository.class);
    when(topics.findConsultantIdsByTopicId(TOPIC_ID)).thenReturn(List.of("c-1"));
    var consultant = new Consultant();
    consultant.setId("c-1");
    consultant.setAbsent(false);
    when(consultants.findAllByIdIn(List.of("c-1"))).thenReturn(List.of(consultant));
    var meters = new SimpleMeterRegistry();
    var registry = new ConsultantActivityRegistry(redis, meters, 120, "availability:");
    var routing =
        new TopicConsultantRoutingService(
            topics,
            consultants,
            mock(MatrixSynapseService.class),
            registry,
            liveChatDiagnosticMetrics);
    ReflectionTestUtils.setField(routing, "activeWindowMs", 120_000L);
    var actualController =
        new TopicConsultantAvailabilityController(routing, liveChatDiagnosticMetrics);

    var response = actualController.getTopicConsultantAvailability(TOPIC_ID, null);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertFalse(response.getBody().isAvailable());
    assertEquals(0, response.getBody().getNumAvailableConsultants());
    verify(liveChatDiagnosticMetrics)
        .recordAvailabilityLookupFailure(AvailabilityEndpoint.ANONYMOUS_DOOR);
    assertThat(logAppender.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getFormattedMessage())
                  .contains("AvailabilityStoreException")
                  .doesNotContain("private connection detail");
              assertThat(event.getThrowableProxy()).isNull();
            });
    assertThat(
            meters
                .get("oriso.live_chat.availability.store.operations")
                .tag("operation", "read")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void getTopicConsultantAvailability_noFailure_logsNothingAndCountsNothing() {
    // Business reason: a real zero must stay distinguishable from a failure.
    when(topicConsultantRoutingService.findAvailableConsultantIds(TOPIC_ID)).thenReturn(List.of());

    controller.getTopicConsultantAvailability(TOPIC_ID, null);

    assertThat(logAppender.list).isEmpty();
    verifyNoInteractions(liveChatDiagnosticMetrics);
  }
}
