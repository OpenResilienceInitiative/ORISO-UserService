package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.neovisionaries.i18n.LanguageCode;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GroupChatParticipant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.GroupChatParticipantRepository;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.email.OrisoEmailRenderer;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentEmailRecipientServiceTest {
  @Mock UserRepository users;
  @Mock ConsultantRepository consultants;
  @Mock UserChatRepository userChats;
  @Mock GroupChatParticipantRepository counselors;

  private GroupAppointmentEmailRecipientService service() {
    return new GroupAppointmentEmailRecipientService(
        users, consultants, userChats, counselors, ".invalid");
  }

  private static Chat group() {
    Chat chat = new Chat();
    chat.setId(42L);
    chat.setConversationType(ConversationType.SELF_HELP);
    return chat;
  }

  @Test
  void resolvesAnEligibleParticipantWithTheirOwnTone() {
    Chat group = group();
    User user = new User();
    user.setUserId("seeker-1");
    user.setEmail("seeker@example.org");
    user.setLanguageCode(LanguageCode.de);
    user.setLanguageFormal(false);
    user.setNotificationsEnabled(true);
    user.setNotificationsSettings("{\"appointmentNotificationEnabled\":true}");
    when(users.findByUserIdAndDeleteDateIsNull("seeker-1")).thenReturn(Optional.of(user));
    when(userChats.findByChatAndUser(group, user))
        .thenReturn(Optional.of(UserChat.builder().chat(group).user(user).build()));

    assertThat(
            service()
                .resolve(group, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .contains(
            new GroupAppointmentEmailRecipientService.Recipient(
                "seeker-1", "seeker@example.org", OrisoEmailRenderer.Tone.DE_INFORMAL));
  }

  @Test
  void resolvesAnInvitedCounselorEvenWhenTheirTenantDiffers() {
    Chat group = group();
    group.setConversationType(null);
    group.setRepeatCount(2);
    Consultant counselor = new Consultant();
    counselor.setId("counselor-1");
    counselor.setTenantId(99L);
    counselor.setEmail("counselor@example.org");
    counselor.setLanguageCode(LanguageCode.fr);
    counselor.setNotificationsEnabled(true);
    counselor.setNotificationsSettings("{\"appointmentNotificationEnabled\":true}");
    when(consultants.findByIdAndDeleteDateIsNull("counselor-1")).thenReturn(Optional.of(counselor));
    when(counselors.findBySeriesIdAndConsultantId(42L, "counselor-1"))
        .thenReturn(Optional.of(new GroupChatParticipant()));

    assertThat(
            service()
                .resolve(
                    group, GroupAppointmentEmailRecipientService.Role.COUNSELOR, "counselor-1"))
        .contains(
            new GroupAppointmentEmailRecipientService.Recipient(
                "counselor-1", "counselor@example.org", OrisoEmailRenderer.Tone.FR));
  }

  @Test
  void rejectsDisabledOrDummyParticipantAddresses() {
    Chat group = group();
    User user = new User();
    user.setUserId("seeker-1");
    user.setEmail("seeker@system.invalid");
    user.setLanguageCode(LanguageCode.en);
    user.setNotificationsEnabled(true);
    user.setNotificationsSettings("{\"appointmentNotificationEnabled\":true}");
    when(users.findByUserIdAndDeleteDateIsNull("seeker-1")).thenReturn(Optional.of(user));
    when(userChats.findByChatAndUser(group, user))
        .thenReturn(Optional.of(UserChat.builder().chat(group).user(user).build()));

    assertThat(
            service()
                .resolve(group, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .isEmpty();
    user.setEmail("seeker@example.org");
    user.setNotificationsSettings("{\"appointmentNotificationEnabled\":false}");
    assertThat(
            service()
                .resolve(group, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .isEmpty();
  }

  @Test
  void internalGroupsAreNeverEmailSources() {
    Chat internal = group();
    internal.setConversationType(ConversationType.INTERNAL_GROUP);
    assertThat(
            service()
                .resolve(
                    internal, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .isEmpty();
  }

  @Test
  void aUserWhoLeftTheGroupCannotReceiveItsMail() {
    Chat group = group();
    User user = new User();
    user.setUserId("seeker-1");
    when(users.findByUserIdAndDeleteDateIsNull("seeker-1")).thenReturn(Optional.of(user));
    when(userChats.findByChatAndUser(group, user)).thenReturn(Optional.empty());

    assertThat(
            service()
                .resolve(group, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .isEmpty();
  }

  @Test
  void anUnknownLanguageDoesNotSilentlyChooseAnotherTemplate() {
    Chat group = group();
    User user = new User();
    user.setUserId("seeker-1");
    user.setEmail("seeker@example.org");
    user.setNotificationsEnabled(true);
    user.setNotificationsSettings("{\"appointmentNotificationEnabled\":true}");
    when(users.findByUserIdAndDeleteDateIsNull("seeker-1")).thenReturn(Optional.of(user));
    when(userChats.findByChatAndUser(group, user))
        .thenReturn(Optional.of(UserChat.builder().chat(group).user(user).build()));

    assertThatThrownBy(
            () ->
                service()
                    .resolve(
                        group, GroupAppointmentEmailRecipientService.Role.PARTICIPANT, "seeker-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Recipient language is missing");
  }
}
