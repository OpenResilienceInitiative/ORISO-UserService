package de.caritas.cob.userservice.api.service.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.model.Session;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Publishes lifecycle events (ADR-032) to the topic exchange {@code oriso.lifecycle}, so add-ons
 * can delete their own data when the UserService deleted a user or a session.
 *
 * <p>Delivery is best effort on purpose: the core deletion is the priority. A publishing failure is
 * logged at ERROR with the event id and type and never fails or rolls back the deletion. The price
 * is that an add-on may miss an event (broker down, missing permission) and keep orphaned data
 * until it reconciles on its own, e.g. through {@code GET /users/addon-context} answering 404.
 *
 * <p>Inside a transaction the event is sent only after the commit, so a deletion that is rolled
 * back never announces itself. Payloads carry ids only, never personal data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LifecycleEventPublisher {

  public static final String USER_DELETED = "user.deleted";
  public static final String SESSION_DELETED = "session.deleted";
  public static final String ROLE_ASKER = "asker";
  public static final String ROLE_CONSULTANT = "consultant";

  private static final Long LEGACY_TENANT = 1L;

  private final @NonNull AmqpTemplate amqpTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Value("${lifecycle.events.enabled:true}")
  private boolean lifecycleEventsEnabled;

  @Value("${lifecycle.rabbitmq.exchange.name:oriso.lifecycle}")
  private String exchangeName;

  /** The ids of a session, captured while its row still exists. */
  public record DeletedSession(Long tenantId, Long sessionId, String askerId, Long agencyId) {

    public static DeletedSession of(Session session) {
      var user = session.getUser();
      return new DeletedSession(
          session.getTenantId(),
          session.getId(),
          user == null ? null : user.getUserId(),
          session.getAgencyId());
    }
  }

  /**
   * Announces that an account was deleted.
   *
   * @param tenantId the tenant of the account, {@code null} for legacy rows of tenant one
   * @param userId the Keycloak id of the account
   * @param role {@link #ROLE_ASKER} or {@link #ROLE_CONSULTANT}
   */
  public void userDeleted(Long tenantId, String userId, String role) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("userId", userId);
    fields.put("role", role);
    publish(USER_DELETED, tenantId, fields);
  }

  /**
   * Announces that a session was deleted.
   *
   * @param session the ids of the deleted session
   */
  public void sessionDeleted(DeletedSession session) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("sessionId", session.sessionId());
    fields.put("askerId", session.askerId());
    fields.put("agencyId", session.agencyId());
    publish(SESSION_DELETED, session.tenantId(), fields);
  }

  private void publish(String type, Long tenantId, Map<String, Object> fields) {
    if (!lifecycleEventsEnabled) {
      return;
    }
    var id = UUID.randomUUID().toString();
    try {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("id", id);
      payload.put("type", type);
      payload.put("occurredAt", Instant.now().toString());
      payload.put("tenantId", tenantId == null ? LEGACY_TENANT : tenantId);
      payload.putAll(fields);
      var body = objectMapper.writeValueAsBytes(payload);
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                send(id, type, body);
              }
            });
      } else {
        send(id, type, body);
      }
    } catch (Exception e) {
      logFailure(id, type, e);
    }
  }

  private void send(String id, String type, byte[] body) {
    try {
      amqpTemplate.send(
          exchangeName,
          type,
          MessageBuilder.withBody(body)
              .setContentType(MessageProperties.CONTENT_TYPE_JSON)
              .setContentEncoding("UTF-8")
              .setMessageId(id)
              .setType(type)
              .build());
    } catch (Exception e) {
      logFailure(id, type, e);
    }
  }

  private static void logFailure(String id, String type, Exception e) {
    log.error("Could not publish lifecycle event {} of type {}", id, type, e);
  }
}
