package de.caritas.cob.userservice.api.service.donotdisturb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.UserDoNotDisturb;
import de.caritas.cob.userservice.api.port.out.UserDoNotDisturbRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Global per-user Do-Not-Disturb: active while {@code dndUntil} is in the future, auto-reverts when
 * it passes (no cleanup job).
 */
@ExtendWith(MockitoExtension.class)
class DoNotDisturbServiceTest {

  private static final String USER = "user-1";

  @InjectMocks private DoNotDisturbService service;
  @Mock private UserDoNotDisturbRepository repository;

  @Test
  void isInDoNotDisturb_true_whenDndUntilInFuture() {
    when(repository.findByUserId(USER))
        .thenReturn(
            Optional.of(
                UserDoNotDisturb.builder()
                    .userId(USER)
                    .dndUntil(LocalDateTime.now().plusHours(1))
                    .build()));
    assertThat(service.isInDoNotDisturb(USER)).isTrue();
  }

  @Test
  void isInDoNotDisturb_false_whenDndUntilInPast_autoReverts() {
    when(repository.findByUserId(USER))
        .thenReturn(
            Optional.of(
                UserDoNotDisturb.builder()
                    .userId(USER)
                    .dndUntil(LocalDateTime.now().minusMinutes(1))
                    .build()));
    assertThat(service.isInDoNotDisturb(USER)).isFalse();
  }

  @Test
  void isInDoNotDisturb_false_whenNoRecord() {
    when(repository.findByUserId(USER)).thenReturn(Optional.empty());
    assertThat(service.isInDoNotDisturb(USER)).isFalse();
  }

  @Test
  void isInDoNotDisturb_false_whenDndUntilNull() {
    when(repository.findByUserId(USER))
        .thenReturn(Optional.of(UserDoNotDisturb.builder().userId(USER).dndUntil(null).build()));
    assertThat(service.isInDoNotDisturb(USER)).isFalse();
  }

  @Test
  void isInDoNotDisturb_false_whenUserIdBlank() {
    assertThat(service.isInDoNotDisturb(null)).isFalse();
    assertThat(service.isInDoNotDisturb(" ")).isFalse();
  }

  @Test
  void getDndUntil_returnsStoredValue() {
    var until = LocalDateTime.now().plusHours(2);
    when(repository.findByUserId(USER))
        .thenReturn(Optional.of(UserDoNotDisturb.builder().userId(USER).dndUntil(until).build()));
    assertThat(service.getDndUntil(USER)).isEqualTo(until);
  }

  @Test
  void setDndUntil_upsertsRecord() {
    when(repository.findByUserId(USER)).thenReturn(Optional.empty());
    var until = LocalDateTime.now().plusHours(8);

    service.setDndUntil(USER, until);

    verify(repository).save(any(UserDoNotDisturb.class));
  }

  @Test
  void clear_setsDndUntilNull() {
    when(repository.findByUserId(USER))
        .thenReturn(
            Optional.of(
                UserDoNotDisturb.builder()
                    .userId(USER)
                    .dndUntil(LocalDateTime.now().plusHours(1))
                    .build()));

    service.clear(USER);

    verify(repository).save(any(UserDoNotDisturb.class));
  }
}
