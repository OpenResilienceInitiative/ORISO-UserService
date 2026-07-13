package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ChatOccurrenceQueryServiceTest {

  private final ChatOccurrenceQueryService service =
      new ChatOccurrenceQueryService(
          mock(ChatRepository.class), mock(ChatOccurrenceExceptionRepository.class));

  @Test
  void rejectsUnboundedOrInvalidWindows() {
    assertThatThrownBy(
            () ->
                service.getOccurrences(
                    1L,
                    LocalDateTime.parse("2026-08-04T00:00:00"),
                    LocalDateTime.parse("2026-08-03T00:00:00"),
                    50))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void rejectsLimitsAboveTheBoundedProjectionMaximum() {
    assertThatThrownBy(
            () ->
                service.getOccurrences(
                    1L,
                    LocalDateTime.parse("2026-08-03T00:00:00"),
                    LocalDateTime.parse("2026-08-04T00:00:00"),
                    101))
        .isInstanceOf(BadRequestException.class);
  }
}
