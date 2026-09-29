package de.caritas.cob.userservice.api.service.httpheader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TechnicalAccessTokenContextTest {

  @AfterEach
  void tearDown() {
    TechnicalAccessTokenContext.clear();
  }

  @Test
  void callWithExposesTheTokenOnlyInsideTheBlock() {
    var seen = new AtomicReference<String>();

    var result =
        TechnicalAccessTokenContext.callWith(
            "technical-token",
            () -> {
              seen.set(TechnicalAccessTokenContext.get().orElse(null));
              return 42;
            });

    assertThat(result).isEqualTo(42);
    assertThat(seen.get()).isEqualTo("technical-token");
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void callWithClearsTheTokenWhenTheBlockThrows() {
    assertThatThrownBy(
            () ->
                TechnicalAccessTokenContext.callWith(
                    "technical-token",
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .hasMessage("boom");

    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void callWithRestoresAnOuterToken() {
    TechnicalAccessTokenContext.set("outer-token");

    TechnicalAccessTokenContext.runWith(
        "inner-token", () -> assertThat(TechnicalAccessTokenContext.get()).contains("inner-token"));

    assertThat(TechnicalAccessTokenContext.get()).contains("outer-token");
  }

  @Test
  void callWithRejectsABlankToken() {
    assertThatThrownBy(() -> TechnicalAccessTokenContext.runWith(" ", () -> {}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }
}
