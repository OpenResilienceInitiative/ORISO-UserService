package de.caritas.cob.userservice.api.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import java.time.LocalDateTime;
import org.jeasy.random.EasyRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class ChatTest {

  private static final EasyRandom easyRandom = new EasyRandom();

  @Test
  public void equals_Should_returnTrue_When_objectIsSameReference() {
    Chat chat = easyRandom.nextObject(Chat.class);

    assertThat(chat, is(chat));
  }

  @Test
  public void equals_Should_returnFalse_When_objectIsNoChatInstance() {
    Chat chat = easyRandom.nextObject(Chat.class);

    boolean equals = chat.equals(new Object());

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnFalse_When_sessionIdsAreDifferent() {
    Chat chat = easyRandom.nextObject(Chat.class);
    chat.setId(1L);
    Chat otherChat = easyRandom.nextObject(Chat.class);
    otherChat.setId(2L);

    boolean equals = chat.equals(otherChat);

    assertThat(equals, is(false));
  }

  @Test
  public void equals_Should_returnTrue_When_sessionIdsAreEqual() {
    Chat chat = easyRandom.nextObject(Chat.class);
    chat.setId(1L);
    Chat otherChat = easyRandom.nextObject(Chat.class);
    otherChat.setId(1L);

    boolean equals = chat.equals(otherChat);

    assertThat(equals, is(true));
  }

  @Test
  public void nextDateShouldThrowExceptionWhenChatIsRepetitiveButNoStartDate() {
    assertThrows(
        InternalServerErrorException.class,
        () -> {
          var chat = new Chat();
          chat.setRepetitive(true);
          chat.setRepeatCount(2);
          chat.setChatInterval(ChatInterval.WEEKLY);

          chat.nextStart();
        });
  }

  @Test
  public void nextDateShouldReturnCorrectNextStartDateWhenChatIsRepetitive() {
    var chat = easyRandom.nextObject(Chat.class);
    chat.setRepetitive(true);
    chat.setRepeatCount(2);
    chat.setCurrentOccurrenceIndex(0);
    chat.setChatInterval(ChatInterval.WEEKLY);

    assertThat(chat.nextStart(), is(notNullValue()));
  }

  @Test
  void occurrenceStartShouldUseTheSeriesAnchorForMonthlyEndOfMonthDates() {
    var chat = new Chat();
    chat.setInitialStartDate(LocalDateTime.of(2026, 1, 31, 18, 0));
    chat.setChatInterval(ChatInterval.MONTHLY);

    assertThat(chat.occurrenceStart(1), is(LocalDateTime.of(2026, 2, 28, 18, 0)));
    assertThat(chat.occurrenceStart(2), is(LocalDateTime.of(2026, 3, 31, 18, 0)));
  }

  @Test
  void nextStartShouldReturnNullAfterTheFiniteSeriesLastOccurrence() {
    var chat = new Chat();
    chat.setInitialStartDate(LocalDateTime.of(2026, 7, 10, 18, 0));
    chat.setStartDate(LocalDateTime.of(2026, 7, 17, 18, 0));
    chat.setChatInterval(ChatInterval.WEEKLY);
    chat.setRepeatCount(2);
    chat.setCurrentOccurrenceIndex(1);

    assertThat(chat.nextStart(), is((LocalDateTime) null));
  }

  @ParameterizedTest
  @CsvSource({
    "DAILY, 2026-01-02T18:00:00",
    "WEEKLY, 2026-01-08T18:00:00",
    "BIWEEKLY, 2026-01-15T18:00:00",
    "MONTHLY, 2026-02-01T18:00:00",
    "QUARTERLY, 2026-04-01T18:00:00",
    "YEARLY, 2027-01-01T18:00:00"
  })
  void occurrenceStartShouldSupportEverySeriesInterval(
      ChatInterval interval, LocalDateTime expected) {
    var chat = new Chat();
    chat.setInitialStartDate(LocalDateTime.of(2026, 1, 1, 18, 0));
    chat.setChatInterval(interval);

    assertThat(chat.occurrenceStart(1), is(expected));
  }

  @Test
  void occurrenceStartShouldPreserveLocalWallClockTimeAcrossDst() {
    var chat = new Chat();
    chat.setInitialStartDate(LocalDateTime.of(2026, 3, 22, 17, 0));
    chat.setTimezone("Europe/Berlin");
    chat.setChatInterval(ChatInterval.WEEKLY);

    assertThat(chat.occurrenceStart(1), is(LocalDateTime.of(2026, 3, 29, 16, 0)));
  }

  @Test
  void occurrenceStartShouldFallBackToUtcForAnInvalidPersistedTimezone() {
    var chat = new Chat();
    chat.setInitialStartDate(LocalDateTime.of(2026, 7, 10, 18, 0));
    chat.setTimezone("Invalid/Persisted-Timezone");
    chat.setChatInterval(ChatInterval.WEEKLY);

    assertThat(chat.occurrenceStart(1), is(LocalDateTime.of(2026, 7, 17, 18, 0)));
  }
}
