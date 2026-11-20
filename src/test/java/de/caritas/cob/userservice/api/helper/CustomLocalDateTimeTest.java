package de.caritas.cob.userservice.api.helper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CustomLocalDateTimeTest {

  @Test
  void toUnixTimeShouldReturnTheGivenUtcTimeNotNow() {
    // 2026-10-27T08:15:00Z, computed independently with `date -u`
    assertThat(CustomLocalDateTime.toUnixTime(LocalDateTime.of(2026, 10, 27, 8, 15, 0)))
        .isEqualTo(1793088900L);
  }

  @Test
  void toUnixTimeShouldReturnZeroForNull() {
    assertThat(CustomLocalDateTime.toUnixTime(null)).isZero();
  }
}
