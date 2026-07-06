package de.caritas.cob.userservice.api.adapters.rocketchat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * ADR-004 wiring guard: exactly one Rocket.Chat adapter is registered per flag state - the real
 * {@link RocketChatService} only when {@code rocket-chat.enabled=true}, the inert {@link
 * DisabledRocketChatService} otherwise (including when the property is missing entirely).
 */
class RocketChatConditionalRegistrationTest {

  @Test
  void realServiceShouldOnlyRegisterWhenRocketChatIsEnabled() {
    try (var context = contextWithProperty("rocket-chat.enabled", "true")) {
      assertThat(context.containsBeanDefinition("rocketChatService")).isTrue();
      assertThat(context.containsBeanDefinition("disabledRocketChatService")).isFalse();
    }
  }

  @Test
  void inertServiceShouldRegisterWhenRocketChatIsDisabled() {
    try (var context = contextWithProperty("rocket-chat.enabled", "false")) {
      assertThat(context.containsBeanDefinition("rocketChatService")).isFalse();
      assertThat(context.containsBeanDefinition("disabledRocketChatService")).isTrue();
    }
  }

  @Test
  void inertServiceShouldRegisterWhenPropertyIsMissing() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.register(RocketChatService.class, DisabledRocketChatService.class);
      assertThat(context.containsBeanDefinition("rocketChatService")).isFalse();
      assertThat(context.containsBeanDefinition("disabledRocketChatService")).isTrue();
    }
  }

  private AnnotationConfigApplicationContext contextWithProperty(String key, String value) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of(key, value)));
    // conditions are evaluated at registration time - no refresh needed, definitions suffice
    context.register(RocketChatService.class, DisabledRocketChatService.class);
    return context;
  }
}
