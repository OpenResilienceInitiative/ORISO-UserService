package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentOccurrenceState;
import de.caritas.cob.userservice.api.port.out.ChatRepository;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentOccurrenceStateRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentMailEligibilityServiceTest {
  @Mock ChatRepository chats;
  @Mock GroupAppointmentOccurrenceStateRepository occurrences;
  @Mock GroupAppointmentEmailRecipientService recipients;
  @InjectMocks GroupAppointmentMailEligibilityService eligibility;

  private Chat series;
  private GroupAppointmentOccurrenceState occurrence;
  private GroupAppointmentMailOutbox mail;

  @BeforeEach
  void setUp() {
    var start = LocalDateTime.now(ZoneOffset.UTC).plusDays(3);
    series =
        Chat.builder()
            .id(42L)
            .topic("not included in mail")
            .initialStartDate(start)
            .startDate(start)
            .conversationType(ConversationType.SELF_HELP)
            .build();
    occurrence =
        GroupAppointmentOccurrenceState.builder()
            .seriesId(42L)
            .occurrenceIndex(2)
            .revision(3)
            .effectiveStartUtc(start)
            .timezone("Europe/Berlin")
            .status(GroupAppointmentOccurrenceState.Status.ACTIVE)
            .build();
    mail =
        GroupAppointmentMailOutbox.builder()
            .seriesId(42L)
            .occurrenceIndex(2)
            .occurrenceRevision(3)
            .eventType(GroupAppointmentMailOutbox.EventType.REMINDER)
            .recipientRole(GroupAppointmentMailOutbox.RecipientRole.PARTICIPANT)
            .recipientId("person")
            .scheduledStartUtc(start)
            .timezone("Europe/Berlin")
            .build();
  }

  @Test
  void currentRevisionAndCurrentMemberAreEligible() {
    when(chats.findById(42L)).thenReturn(Optional.of(series));
    when(occurrences.findBySeriesIdAndOccurrenceIndex(42L, 2)).thenReturn(Optional.of(occurrence));
    var recipient =
        new GroupAppointmentEmailRecipientService.Recipient(
            "person", "person@example.org", OrisoEmailRenderer.Tone.EN);
    when(recipients.resolve(
            series, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "person"))
        .thenReturn(Optional.of(recipient));

    assertThat(eligibility.resolve(mail)).get().extracting("recipient").isEqualTo(recipient);
  }

  @Test
  void oldReminderRevisionIsSuppressedBeforeRecipientLookup() {
    occurrence.setRevision(4);
    when(chats.findById(42L)).thenReturn(Optional.of(series));
    when(occurrences.findBySeriesIdAndOccurrenceIndex(42L, 2)).thenReturn(Optional.of(occurrence));

    assertThat(eligibility.resolve(mail)).isEmpty();
    verifyNoInteractions(recipients);
  }

  @Test
  void cancellationIsOnlyEligibleForCurrentCancelledRevision() {
    mail.setEventType(GroupAppointmentMailOutbox.EventType.CANCELLED);
    occurrence.setStatus(GroupAppointmentOccurrenceState.Status.CANCELLED);
    occurrence.setEffectiveStartUtc(null);
    when(chats.findById(42L)).thenReturn(Optional.of(series));
    when(occurrences.findBySeriesIdAndOccurrenceIndex(42L, 2)).thenReturn(Optional.of(occurrence));
    when(recipients.resolve(
            series, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "person"))
        .thenReturn(
            Optional.of(
                new GroupAppointmentEmailRecipientService.Recipient(
                    "person", "person@example.org", OrisoEmailRenderer.Tone.EN)));

    assertThat(eligibility.resolve(mail)).isPresent();
  }
}
