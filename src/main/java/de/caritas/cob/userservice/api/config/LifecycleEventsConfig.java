package de.caritas.cob.userservice.api.config;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the durable topic exchange for lifecycle events (ADR-034), so publishing works on a
 * fresh broker. The broker admin declares it lazily on the first connection, so a missing broker
 * does not keep the service from starting.
 */
@Configuration
public class LifecycleEventsConfig {

  @Bean
  @ConditionalOnProperty(
      name = "lifecycle.events.enabled",
      havingValue = "true",
      matchIfMissing = true)
  public TopicExchange lifecycleExchange(
      @Value("${lifecycle.rabbitmq.exchange.name:oriso.lifecycle}") String exchangeName) {
    return new TopicExchange(exchangeName, true, false);
  }
}
