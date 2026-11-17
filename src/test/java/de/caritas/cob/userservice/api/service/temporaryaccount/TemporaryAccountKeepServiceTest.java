package de.caritas.cob.userservice.api.service.temporaryaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.model.Chat;
import de.caritas.cob.userservice.api.model.ConversationType;
import de.caritas.cob.userservice.api.model.GuestAccountMode;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.model.UserChat;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClient;
import de.caritas.cob.userservice.api.port.out.UserChatRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionLifecycleState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** #1662 "Konto behalten": a temporary guest becomes a permanent account with a password. */
class TemporaryAccountKeepServiceTest {

  private static final String USER_ID = "guest-1";
  private static final String PASSWORD = "a-long-enough-password";

  private final UserRepository users = mock(UserRepository.class);
  private final AgencyInviteLinkRepository links = mock(AgencyInviteLinkRepository.class);
  private final UserChatRepository userChats = mock(UserChatRepository.class);
  private final IdentityClient identities = mock(IdentityClient.class);
  private final TemporaryAccountKeepService service =
      new TemporaryAccountKeepService(users, links, userChats, identities);
  private User guest;
  private AgencyInviteLink link;

  @BeforeEach
  void setUp() {
    guest = new User();
    guest.setUserId(USER_ID);
    guest.setTemporaryAccount(true);
    guest.setDeletionLifecycleState(DeletionLifecycleState.ACTIVE);
    guest.setTemporaryAccountSnapshot(TemporaryAccountSnapshot.builder().inviteLinkId(9L).build());
    link = AgencyInviteLink.builder().id(9L).linkKind("COUNSELLOR").chatType("VIDEO").build();
    link.applyGuestAccountMode(GuestAccountMode.GUEST_CHOOSES);
    when(users.findByUserIdForUpdate(USER_ID)).thenReturn(Optional.of(guest));
    when(links.findById(9L)).thenReturn(Optional.of(link));
    when(identities.changePassword(USER_ID, PASSWORD)).thenReturn(true);
  }

  @Test
  void keepMakesTheGuestPermanent_AndEndsTheDeletionSchedule() {
    service.keep(USER_ID, PASSWORD);

    verify(identities).changePassword(USER_ID, PASSWORD);
    assertThat(guest.isTemporaryAccount()).isFalse();
    assertThat(guest.getTemporaryAccountSnapshot()).isNull();
    verify(users).save(guest);
  }

  @Test
  void theModeTheGuestEnteredUnderDecides_NotALaterSwitchOfTheLink() {
    guest.setGuestAccountModeAtEntry(GuestAccountMode.GUEST_CHOOSES);
    // the counsellor switched the link to "delete after the session" after this guest entered
    link.applyGuestAccountMode(GuestAccountMode.TEMPORARY);

    service.keep(USER_ID, PASSWORD);

    assertThat(guest.isTemporaryAccount()).isFalse();
  }

  @Test
  void aGuestWhoEnteredUnderTemporaryMayNotKeep_EvenWhenTheLinkLetsGuestsChooseNow() {
    guest.setGuestAccountModeAtEntry(GuestAccountMode.TEMPORARY);
    link.applyGuestAccountMode(GuestAccountMode.GUEST_CHOOSES);

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD))
        .isInstanceOf(ForbiddenException.class);
    verify(identities, never()).changePassword(anyString(), anyString());
  }

  @Test
  void aStoredEntryModeNeedsNoLink() {
    guest.setGuestAccountModeAtEntry(GuestAccountMode.GUEST_CHOOSES);
    when(links.findById(9L)).thenReturn(Optional.empty());

    service.keep(USER_ID, PASSWORD);

    assertThat(guest.isTemporaryAccount()).isFalse();
  }

  @Test
  void aGesprächskreisGuestKeepsTheRightToChoose_WhenTheCircleIsSwitchedToTemporaryLater() {
    guest.setTemporaryAccountSnapshot(null);
    guest.setGuestAccountModeAtEntry(GuestAccountMode.GUEST_CHOOSES);
    var circle = circle(GuestAccountMode.TEMPORARY);
    when(userChats.findByUser(guest))
        .thenReturn(List.of(UserChat.builder().user(guest).chat(circle).build()));

    service.keep(USER_ID, PASSWORD);

    assertThat(guest.isTemporaryAccount()).isFalse();
  }

  // Accounts created before the entry mode was stored are judged by their link or circle.
  @Test
  void aLinkThatOnlyCreatesTemporaryAccountsRefusesKeeping() {
    link.applyGuestAccountMode(GuestAccountMode.TEMPORARY);

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD))
        .isInstanceOf(ForbiddenException.class);
    verify(identities, never()).changePassword(anyString(), anyString());
    assertThat(guest.isTemporaryAccount()).isTrue();
  }

  @Test
  void aDeletedLinkCannotProveTheGuestMayKeepTheAccount() {
    when(links.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void aGesprächskreisGuestMayKeepTheAccount_WhenTheCircleLetsGuestsChoose() {
    guest.setTemporaryAccountSnapshot(null);
    var circle = circle(GuestAccountMode.GUEST_CHOOSES);
    when(userChats.findByUser(guest))
        .thenReturn(List.of(UserChat.builder().user(guest).chat(circle).build()));

    service.keep(USER_ID, PASSWORD);

    assertThat(guest.isTemporaryAccount()).isFalse();
  }

  @Test
  void aGesprächskreisGuestOfATemporaryCircleMayNot() {
    guest.setTemporaryAccountSnapshot(null);
    var circle = circle(GuestAccountMode.TEMPORARY);
    when(userChats.findByUser(guest))
        .thenReturn(List.of(UserChat.builder().user(guest).chat(circle).build()));

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void aPermanentAccountHasNothingToKeep() {
    guest.setTemporaryAccount(false);

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD)).isInstanceOf(ConflictException.class);
  }

  @Test
  void aStartedDeletionWins() {
    guest.getTemporaryAccountSnapshot().setDeletionStartedAt(LocalDateTime.now());

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD)).isInstanceOf(ConflictException.class);
    verify(identities, never()).changePassword(anyString(), anyString());
  }

  @Test
  void anAccountInAnotherLifecycleStateIsRefused() {
    guest.setDeletionLifecycleState(DeletionLifecycleState.PENDING_DELETION);

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD)).isInstanceOf(ConflictException.class);
  }

  @Test
  void aShortPasswordIsRefusedBeforeAnythingHappens() {
    assertThatThrownBy(() -> service.keep(USER_ID, "short"))
        .isInstanceOf(BadRequestException.class);
    verify(users, never()).findByUserIdForUpdate(any());
  }

  @Test
  void aFailedPasswordChangeLeavesTheAccountTemporary() {
    when(identities.changePassword(USER_ID, PASSWORD)).thenReturn(false);

    assertThatThrownBy(() -> service.keep(USER_ID, PASSWORD))
        .isInstanceOf(InternalServerErrorException.class);
    assertThat(guest.isTemporaryAccount()).isTrue();
    verify(users, never()).save(any());
  }

  private static Chat circle(GuestAccountMode mode) {
    var chat = new Chat();
    chat.setConversationType(ConversationType.SELF_HELP);
    chat.setGuestAccountMode(mode);
    return chat;
  }
}
