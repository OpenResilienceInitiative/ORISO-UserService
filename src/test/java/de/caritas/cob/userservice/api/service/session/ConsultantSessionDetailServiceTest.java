package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantSessionDetailServiceTest {

  @Mock private SessionRepository sessionRepository;
  @Mock private SessionAccessService sessionAccessService;
  @Mock private Session session;
  @Mock private User user;
  @Mock private Consultant consultant;

  private ConsultantSessionDetailService consultantSessionDetailService;

  @BeforeEach
  void setUp() {
    consultantSessionDetailService =
        new ConsultantSessionDetailService(sessionRepository, sessionAccessService, null);
  }

  @Test
  void fetchSessionForConsultant_Should_LookUpAndAuthorizeSessionOnce() {
    when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
    when(session.getId()).thenReturn(1L);
    when(session.getStatus()).thenReturn(SessionStatus.IN_PROGRESS);
    when(session.getUser()).thenReturn(user);

    var result = consultantSessionDetailService.fetchSessionForConsultant(1L, consultant);

    assertThat(result.getId()).isEqualTo(1L);
    verify(sessionRepository).findById(1L);
    verify(sessionAccessService).checkPermissionForConsultantSession(session, consultant);
  }
}
