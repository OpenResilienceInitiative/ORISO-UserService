package de.caritas.cob.userservice.api.service.temporaryaccount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.adapters.web.dto.TemporaryAccountDTO;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.TemporaryAccountSnapshot;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TemporaryAccountServiceTest {

  @Mock private UserRepository userRepository;
  @InjectMocks private TemporaryAccountService service;

  private static User guest(MessageRetentionMode mode) {
    var user = new User();
    user.setUserId("guest");
    user.setTemporaryAccount(true);
    user.setTemporaryAccountSnapshot(
        TemporaryAccountSnapshot.builder()
            .medium(
                de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkChatType.AUDIO)
            .callDeleteAfterMinutes(15)
            .callMaxLifetimeHours(24)
            .chatInactivityDays(30)
            .messageRetention(mode)
            .build());
    return user;
  }

  @Test
  void own_Should_DescribeTheGuestsAccount() {
    var dto = service.own(guest(MessageRetentionMode.GUEST_DECIDES));

    assertThat(dto.getMedium()).isEqualTo(TemporaryAccountDTO.MediumEnum.AUDIO);
    assertThat(dto.getMessageRetention())
        .isEqualTo(
            de.caritas.cob.userservice.api.adapters.web.dto.MessageRetentionMode.GUEST_DECIDES);
    assertThat(dto.getKeepMessages()).isNull();
    assertThat(dto.getKeepsMessages()).isFalse();
    assertThat(dto.getDeleted()).isFalse();
  }

  @Test
  void own_Should_Answer404_ForAnOrdinaryAccount() {
    var user = new User();
    user.setUserId("asker");

    assertThatThrownBy(() -> service.own(user)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void decide_Should_StoreTheGuestsAnswerOnce() {
    var user = guest(MessageRetentionMode.GUEST_DECIDES);

    var dto = service.decide(user, true);

    assertThat(dto.getKeepMessages()).isTrue();
    assertThat(dto.getKeepsMessages()).isTrue();
    verify(userRepository).save(user);
    assertThatThrownBy(() -> service.decide(user, false)).isInstanceOf(ConflictException.class);
  }

  @Test
  void decide_Should_Refuse_When_ThePlatformDoesNotAskTheGuest() {
    var user = guest(MessageRetentionMode.ALWAYS_TEMPORARY);

    assertThatThrownBy(() -> service.decide(user, true)).isInstanceOf(ConflictException.class);
    verify(userRepository, never()).save(any());
  }

  @Test
  void toDto_Should_MarkADeletedGuest() {
    var user = guest(MessageRetentionMode.ALWAYS_PERSISTENT);
    user.getTemporaryAccountSnapshot().setDeletedAt(LocalDateTime.now());

    var dto = TemporaryAccountService.toDto(user.getTemporaryAccountSnapshot());

    assertThat(dto.getDeleted()).isTrue();
    assertThat(dto.getKeepsMessages()).isTrue();
  }
}
