package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantEnquiryOpened;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantEnquiryOpenedRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class EnquiryOpenedServiceTest {

  private static final long SESSION_ID = 42L;

  @InjectMocks private EnquiryOpenedService service;
  @Mock private SessionService sessionService;
  @Mock private ConsultantEnquiryOpenedRepository repository;

  private Consultant counsellor;
  private Session waiting;

  @BeforeEach
  void setUp() {
    counsellor = mock(Consultant.class);
    org.mockito.Mockito.lenient().when(counsellor.getId()).thenReturn("counsellor-a");
    waiting = new Session();
    waiting.setId(SESSION_ID);
    waiting.setStatus(SessionStatus.NEW);
  }

  private void givenSession(Session session) {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.of(session));
  }

  @Test
  void marksOnlyTheCallingCounsellor() {
    givenSession(waiting);
    when(sessionService.isConsultantPermittedToSession(counsellor, waiting)).thenReturn(true);

    service.markOpened(SESSION_ID, counsellor);

    var saved = ArgumentCaptor.forClass(ConsultantEnquiryOpened.class);
    verify(repository).save(saved.capture());
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getConsultantId())
        .isEqualTo("counsellor-a");
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getSessionId())
        .isEqualTo(SESSION_ID);
    org.assertj.core.api.Assertions.assertThat(saved.getValue().getOpenedAt()).isNotNull();
  }

  @Test
  void repeatCallWritesNothingAndDoesNotFail() {
    givenSession(waiting);
    when(sessionService.isConsultantPermittedToSession(counsellor, waiting)).thenReturn(true);
    when(repository.existsByConsultantIdAndSessionId("counsellor-a", SESSION_ID)).thenReturn(true);

    assertThatCode(() -> service.markOpened(SESSION_ID, counsellor)).doesNotThrowAnyException();

    verify(repository, never()).save(any());
  }

  @Test
  void concurrentCallLosingTheUniqueKeyRaceEndsQuietly() {
    givenSession(waiting);
    when(sessionService.isConsultantPermittedToSession(counsellor, waiting)).thenReturn(true);
    when(repository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

    assertThatCode(() -> service.markOpened(SESSION_ID, counsellor)).doesNotThrowAnyException();
  }

  @Test
  void enquiryOutsideTheCounsellorsPoolIsForbiddenAndNothingIsStored() {
    givenSession(waiting);
    when(sessionService.isConsultantPermittedToSession(counsellor, waiting)).thenReturn(false);

    assertThatThrownBy(() -> service.markOpened(SESSION_ID, counsellor))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(repository);
  }

  @Test
  void unknownSessionIsNotFound() {
    when(sessionService.getSession(SESSION_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.markOpened(SESSION_ID, counsellor))
        .isInstanceOf(NotFoundException.class);

    verifyNoInteractions(repository);
  }

  @Test
  void sessionThatIsNoWaitingEnquiryIsAConflict() {
    for (var status : SessionStatus.values()) {
      if (status == SessionStatus.NEW) {
        continue;
      }
      waiting.setStatus(status);
      givenSession(waiting);
      assertThatThrownBy(() -> service.markOpened(SESSION_ID, counsellor))
          .as("status %s", status)
          .isInstanceOf(ConflictException.class);
    }
    verifyNoInteractions(repository);
  }

  @Test
  void enquiryAlreadyAcceptedByAnotherCounsellorIsAConflict() {
    waiting.setConsultant(mock(Consultant.class));
    givenSession(waiting);

    assertThatThrownBy(() -> service.markOpened(SESSION_ID, counsellor))
        .isInstanceOf(ConflictException.class);

    verifyNoInteractions(repository);
  }

  @Test
  void marksAnAnonymousEnquiryTheTopicQueueShowsEvenInAnotherTenant() {
    waiting.setRegistrationType(RegistrationType.ANONYMOUS);
    when(sessionService.findVisibleAnonymousLiveChatEnquiry(counsellor, SESSION_ID))
        .thenReturn(Optional.of(waiting));

    service.markOpened(SESSION_ID, counsellor);

    verify(repository).save(any(ConsultantEnquiryOpened.class));
    verify(sessionService, never()).getSession(any());
  }

  @Test
  void refusesAnAnonymousEnquiryOutsideTheTopicQueueDespiteGeneralSessionAccess() {
    waiting.setRegistrationType(RegistrationType.ANONYMOUS);
    givenSession(waiting);
    org.mockito.Mockito.lenient()
        .when(sessionService.isConsultantPermittedToSession(counsellor, waiting))
        .thenReturn(true);

    assertThatThrownBy(() -> service.markOpened(SESSION_ID, counsellor))
        .isInstanceOf(ForbiddenException.class);
    verify(repository, never()).save(any());
  }
}
