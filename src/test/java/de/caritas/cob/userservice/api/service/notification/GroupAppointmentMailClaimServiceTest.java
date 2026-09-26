package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox;
import de.caritas.cob.userservice.api.model.GroupAppointmentMailOutbox.Status;
import de.caritas.cob.userservice.api.port.out.GroupAppointmentMailOutboxRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GroupAppointmentMailClaimServiceTest {
  @Mock GroupAppointmentMailOutboxRepository outbox;
  @InjectMocks GroupAppointmentMailClaimService claims;

  @Test
  void claimsOnlyAnUnclaimedRow() {
    when(outbox.claim(
            org.mockito.ArgumentMatchers.eq(42L),
            org.mockito.ArgumentMatchers.eq(Status.PENDING),
            org.mockito.ArgumentMatchers.eq(Status.SENDING),
            any(LocalDateTime.class)))
        .thenReturn(1, 0);

    assertThat(claims.claim(42L)).isTrue();
    assertThat(claims.claim(42L)).isFalse();
  }

  @Test
  void anAmbiguousHandoffEndsInUncertainWithoutMakingTheRowPendingAgain() {
    var mail = GroupAppointmentMailOutbox.builder().id(42L).status(Status.SENDING).build();
    when(outbox.findById(42L)).thenReturn(Optional.of(mail));

    claims.finish(42L, Status.UNCERTAIN);

    assertThat(mail.getStatus()).isEqualTo(Status.UNCERTAIN);
    assertThat(mail.getSentAt()).isNull();
    verify(outbox).save(mail);
    assertThatThrownBy(() -> claims.finish(42L, Status.SENT))
        .isInstanceOf(IllegalStateException.class);
  }
}
