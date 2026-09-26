package de.caritas.cob.userservice.api.service.notification;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Chat.ChatInterval;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException;
import de.caritas.cob.userservice.api.model.ChatOccurrenceException.ExceptionType;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.port.out.ChatOccurrenceExceptionRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentSeriesEventProducerTest {
  @Mock GroupAppointmentMailQueue queue;
  @Mock ChatOccurrenceExceptionRepository exceptions;
  @InjectMocks GroupAppointmentSeriesEventProducer producer;

  @Test
  void recordsTheLocalMorningTimeAcrossTheBerlinDstBoundary() {
    var firstUtc = LocalDateTime.parse("2027-03-27T08:00:00");
    var secondUtc = LocalDateTime.parse("2027-03-28T07:00:00");
    var series =
        Chat.builder()
            .id(42L)
            .topic("hidden from mail")
            .initialStartDate(firstUtc)
            .startDate(firstUtc)
            .timezone("Europe/Berlin")
            .repeatCount(2)
            .chatInterval(ChatInterval.DAILY)
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(exceptions.findBySeries_Id(42L)).thenReturn(List.of());

    producer.recordCreated(series);

    verify(queue).recordOccurrence(series, 0, firstUtc, firstUtc, true);
    verify(queue).recordOccurrence(series, 1, secondUtc, secondUtc, false);
  }

  @Test
  void aSkippedFirstDateMakesTheNextLiveDateTheOnlyImmediateConfirmation() {
    var firstUtc = LocalDateTime.parse("2027-03-27T08:00:00");
    var secondUtc = LocalDateTime.parse("2027-03-28T07:00:00");
    var series =
        Chat.builder()
            .id(42L)
            .topic("hidden from mail")
            .initialStartDate(firstUtc)
            .startDate(firstUtc)
            .timezone("Europe/Berlin")
            .repeatCount(2)
            .chatInterval(ChatInterval.DAILY)
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(exceptions.findBySeries_Id(42L))
        .thenReturn(List.of(ChatOccurrenceException.skip(series, firstUtc)));

    producer.recordCreated(series);

    verify(queue).recordOccurrence(series, 0, firstUtc, null, false);
    verify(queue).recordOccurrence(series, 1, secondUtc, secondUtc, true);
  }

  @Test
  void aMovedFirstDateConfirmsTheEarlierEffectiveSecondDate() {
    var firstUtc = LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(5);
    var secondUtc = firstUtc.plusDays(1);
    var movedFirstUtc = firstUtc.plusDays(2);
    var series =
        Chat.builder()
            .id(42L)
            .topic("hidden from mail")
            .initialStartDate(firstUtc)
            .startDate(firstUtc)
            .timezone("Europe/Berlin")
            .repeatCount(2)
            .chatInterval(ChatInterval.DAILY)
            .conversationType(ConversationType.SELF_HELP)
            .build();
    when(exceptions.findBySeries_Id(42L))
        .thenReturn(
            List.of(
                ChatOccurrenceException.builder()
                    .series(series)
                    .originalOccurrenceStartUtc(firstUtc)
                    .exceptionType(ExceptionType.OVERRIDE)
                    .overrideStartUtc(movedFirstUtc)
                    .build()));

    producer.recordCreated(series);

    verify(queue).recordOccurrence(series, 0, firstUtc, movedFirstUtc, false);
    verify(queue).recordOccurrence(series, 1, secondUtc, secondUtc, true);
  }
}
