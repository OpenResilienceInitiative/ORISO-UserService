package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.adapters.web.dto.GuestIdentitySuggestionRequest;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UsersApi;
import org.junit.jupiter.api.Test;

/**
 * The generated {@code UsersApi} maps every operation itself and answers {@code 501} unless the
 * implementing controller overrides it. A separate {@code @RestController} for the same path does
 * not help: the generated mapping carries {@code consumes}/{@code produces} conditions and is the
 * more specific one, so it wins every JSON request without Spring reporting an ambiguity.
 *
 * <p>This is a structural assertion on purpose. Showing it through HTTP needs a context that holds
 * both the generated contract and the real controller, and any test-only controller that provides
 * one is picked up by the component scan of every other integration test, where it then collides
 * with {@code UserController}. The behaviour itself is covered against the running service.
 */
class GeneratedContractOverrideTest {

  @Test
  void suggestGuestIdentitiesIsAnsweredByTheControllerAndNotByTheGeneratedStub() throws Exception {
    var method =
        UserController.class.getMethod(
            "suggestGuestIdentities", GuestIdentitySuggestionRequest.class);

    assertThat(method.getDeclaringClass())
        .as("left on the generated default, the endpoint answers 501 in the running service")
        .isEqualTo(UserController.class);
    assertThat(UsersApi.class.isAssignableFrom(UserController.class)).isTrue();
  }
}
