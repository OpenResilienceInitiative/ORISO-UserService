package de.caritas.cob.userservice.api.service.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher.DeletedSession;
import de.caritas.cob.userservice.api.service.lifecycle.LifecycleEventPublisher.SessionInteraction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class LifecycleEventPublisherTest {

  private static final String EXCHANGE = "oriso.lifecycle";
  private static final String ASKER_ID = "8d3c3c2e-4a8e-4b8a-9f43-6c0a0c7d1f10";

  @Mock private AmqpTemplate amqpTemplate;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private LifecycleEventPublisher publisher;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    publisher = new LifecycleEventPublisher(amqpTemplate);
    ReflectionTestUtils.setField(publisher, "lifecycleEventsEnabled", true);
    ReflectionTestUtils.setField(publisher, "exchangeName", EXCHANGE);
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(LifecycleEventPublisher.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(LifecycleEventPublisher.class)).detachAppender(logs);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void userDeletedIsSentWithItsRoutingKeyAndIdsOnly() throws Exception {
    publisher.userDeleted(3L, ASKER_ID, LifecycleEventPublisher.ROLE_ASKER);

    var message = sent("user.deleted");
    assertThat(message.getMessageProperties().getContentType())
        .isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
    assertThat(message.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
    var payload = payloadOf(message);
    assertThat(fieldNames(payload))
        .containsExactly("id", "type", "occurredAt", "tenantId", "userId", "role");
    assertThat(UUID.fromString(payload.get("id").asText())).isNotNull();
    assertThat(message.getMessageProperties().getMessageId()).isEqualTo(payload.get("id").asText());
    assertThat(payload.get("type").asText()).isEqualTo("user.deleted");
    assertThat(Instant.parse(payload.get("occurredAt").asText())).isNotNull();
    assertThat(payload.get("tenantId").asLong()).isEqualTo(3L);
    assertThat(payload.get("userId").asText()).isEqualTo(ASKER_ID);
    assertThat(payload.get("role").asText()).isEqualTo("asker");
  }

  @Test
  void sessionDeletedIsSentWithItsRoutingKeyAndIds() throws Exception {
    var user = new User();
    user.setUserId(ASKER_ID);
    user.setUsername("enc.secret");
    user.setEmail("asker@example.invalid");
    var session = new Session();
    session.setId(16L);
    session.setUser(user);
    session.setAgencyId(0L);
    session.setTenantId(1L);
    session.setPostcode("12345");

    publisher.sessionDeleted(DeletedSession.of(session));

    var payload = payloadOf(sent("session.deleted"));
    assertThat(fieldNames(payload))
        .containsExactly(
            "id", "type", "occurredAt", "tenantId", "sessionId", "askerId", "agencyId");
    assertThat(payload.get("type").asText()).isEqualTo("session.deleted");
    assertThat(payload.get("tenantId").asLong()).isEqualTo(1L);
    assertThat(payload.get("sessionId").asLong()).isEqualTo(16L);
    assertThat(payload.get("askerId").asText()).isEqualTo(ASKER_ID);
    assertThat(payload.get("agencyId").asLong()).isZero();
    assertThat(payload.toString()).doesNotContain("secret", "example.invalid", "12345");
  }

  @Test
  void legacyRowsWithoutTenantAreAnnouncedForTenantOne() throws Exception {
    publisher.userDeleted(null, ASKER_ID, LifecycleEventPublisher.ROLE_CONSULTANT);

    var payload = payloadOf(sent("user.deleted"));
    assertThat(payload.get("tenantId").asLong()).isEqualTo(1L);
    assertThat(payload.get("role").asText()).isEqualTo("consultant");
  }

  @Test
  void userRegisteredCarriesTheAccountAndTheAddonReferenceOnly() throws Exception {
    publisher.userRegistered(3L, ASKER_ID, "ref_42-A");

    var payload = payloadOf(sent("user.registered"));
    assertThat(fieldNames(payload))
        .containsExactly("id", "type", "occurredAt", "tenantId", "userId", "registrationRef");
    assertThat(payload.get("type").asText()).isEqualTo("user.registered");
    assertThat(payload.get("tenantId").asLong()).isEqualTo(3L);
    assertThat(payload.get("userId").asText()).isEqualTo(ASKER_ID);
    assertThat(payload.get("registrationRef").asText()).isEqualTo("ref_42-A");
  }

  @Test
  void sessionInteractionCarriesIdsTypeAndTheTimeOfTheInteraction() throws Exception {
    var at = Instant.parse("2026-11-17T09:30:00Z");

    publisher.sessionInteraction(
        new SessionInteraction(3L, 16L, 4L, "consultant-1", "video_call", at));

    var payload = payloadOf(sent("session.interaction"));
    assertThat(fieldNames(payload))
        .containsExactly(
            "id",
            "type",
            "occurredAt",
            "tenantId",
            "sessionId",
            "agencyId",
            "consultantId",
            "interactionType");
    assertThat(payload.get("type").asText()).isEqualTo("session.interaction");
    assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-11-17T09:30:00Z");
    assertThat(payload.get("tenantId").asLong()).isEqualTo(3L);
    assertThat(payload.get("sessionId").asLong()).isEqualTo(16L);
    assertThat(payload.get("agencyId").asLong()).isEqualTo(4L);
    assertThat(payload.get("consultantId").asText()).isEqualTo("consultant-1");
    assertThat(payload.get("interactionType").asText()).isEqualTo("video_call");
  }

  @Test
  void nothingIsPublishedWhenDisabled() {
    ReflectionTestUtils.setField(publisher, "lifecycleEventsEnabled", false);

    publisher.userDeleted(1L, ASKER_ID, LifecycleEventPublisher.ROLE_ASKER);
    publisher.sessionDeleted(new DeletedSession(1L, 16L, ASKER_ID, 0L));

    verifyNoInteractions(amqpTemplate);
  }

  @Test
  void aFailureIsSwallowedAndLoggedWithIdAndType() {
    doThrow(new AmqpConnectException(new RuntimeException("broker down")))
        .when(amqpTemplate)
        .send(anyString(), anyString(), any(Message.class));

    assertThatCode(() -> publisher.sessionDeleted(new DeletedSession(1L, 16L, ASKER_ID, 0L)))
        .doesNotThrowAnyException();

    var message = sent("session.deleted");
    var id = message.getMessageProperties().getMessageId();
    assertThat(logs.list)
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.ERROR);
              assertThat(event.getFormattedMessage()).contains(id).contains("session.deleted");
            });
  }

  @Test
  void insideATransactionTheEventIsSentOnlyAfterTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    publisher.userDeleted(1L, ASKER_ID, LifecycleEventPublisher.ROLE_ASKER);

    verifyNoInteractions(amqpTemplate);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    sent("user.deleted");
  }

  private Message sent(String routingKey) {
    var captor = ArgumentCaptor.forClass(Message.class);
    verify(amqpTemplate).send(eq(EXCHANGE), eq(routingKey), captor.capture());
    return captor.getValue();
  }

  private JsonNode payloadOf(Message message) throws Exception {
    return objectMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
  }

  private static java.util.List<String> fieldNames(JsonNode payload) {
    var names = new java.util.ArrayList<String>();
    payload.fieldNames().forEachRemaining(names::add);
    return names;
  }
}
