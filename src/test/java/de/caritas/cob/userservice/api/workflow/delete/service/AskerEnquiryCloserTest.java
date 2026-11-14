package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AskerEnquiryCloserTest {

  @Mock private SessionRepository sessionRepository;
  @InjectMocks private AskerEnquiryCloser closer;

  @Test
  void closeOpenEnquiries_Should_closeTheAskersUntakenEnquiriesNowInUtc() {
    var asker = new User();
    asker.setUserId("asker-1");
    when(sessionRepository.closeUnassignedEnquiries(eq(asker), any())).thenReturn(2);
    var before = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);

    int closed = closer.closeOpenEnquiries(asker);

    var now = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(sessionRepository).closeUnassignedEnquiries(eq(asker), now.capture());
    assertThat(closed).isEqualTo(2);
    assertThat(now.getValue()).isAfter(before);
  }

  @Test
  void closeOpenEnquiries_Should_doNothing_When_noAsker() {
    assertThat(closer.closeOpenEnquiries(null)).isZero();

    verifyNoInteractions(sessionRepository);
  }
}
