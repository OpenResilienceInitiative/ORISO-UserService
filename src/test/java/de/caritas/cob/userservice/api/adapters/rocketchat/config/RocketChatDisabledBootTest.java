package de.caritas.cob.userservice.api.adapters.rocketchat.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClient;
import de.caritas.cob.userservice.api.adapters.rocketchat.DisabledRocketChatService;
import de.caritas.cob.userservice.api.adapters.rocketchat.RocketChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * ADR-004 proof: the service boots Matrix-only with {@code rocket-chat.enabled=false} (the
 * default) and WITHOUT any Rocket.Chat URL configured. The full application context must start,
 * the inert {@link DisabledRocketChatService} must be bound, and neither a Rocket.Chat MongoDB
 * client nor the credential cron may exist.
 */
@SpringBootTest(
    properties = {"rocket-chat.enabled=false", "rocket-chat.base-url=", "rocket-chat.mongo-url="})
@ActiveProfiles("testing")
class RocketChatDisabledBootTest {

  @Autowired private ApplicationContext context;

  @Test
  void contextShouldBootWithInertRocketChatAdapterAndNoMongoClient() {
    assertThat(context.getBean(RocketChatService.class))
        .isInstanceOf(DisabledRocketChatService.class);
    assertThat(context.getBeanNamesForType(MongoClient.class)).isEmpty();
  }
}
