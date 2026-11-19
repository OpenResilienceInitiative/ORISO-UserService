package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.session.AgencySilentMembershipService.InitialRoomContact;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyEnquiryRoomPublicationTest {

  private static final String MATRIX_ROOM_ID = "!new:example.invalid";
  private static final long SESSION_ID = 25L;
  private static final long AGENCY_ID = 42L;

  @Mock private ConsultantRepository consultants;
  @Mock private SessionRepository sessions;
  @Mock private SessionService sessionService;
  @Mock private AgencyEnquiryRevocations revocations;
  @InjectMocks private AgencyEnquiryRoomPublication publication;

  @Test
  void publicationHoldsRosterLocksInOrderAndRecordsTheExactBinding() {
    var current = openEnquiry();
    var first = new InitialRoomContact("a", "@a:example.invalid");
    var second = new InitialRoomContact("z", "@z:example.invalid");
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.of(current));
    doAnswer(
            args -> {
              assertThat(current.getMatrixRoomId()).isEqualTo(MATRIX_ROOM_ID);
              return null;
            })
        .when(revocations)
        .stageDuringPublication(current, List.of(first, second));

    assertThat(publication.publish(openEnquiry(), MATRIX_ROOM_ID, List.of(second, first, first)))
        .contains(MATRIX_ROOM_ID);

    var order = inOrder(consultants, sessions, revocations, sessionService);
    order.verify(consultants).lockConsultant("a");
    order.verify(consultants).lockConsultant("z");
    order.verify(sessions).lockFresh(SESSION_ID);
    order.verify(revocations).stageDuringPublication(current, List.of(first, second));
    order.verify(sessionService).saveSession(current);
  }

  @Test
  void aStalePublicationCannotReplaceTheWinningBinding() {
    var current = openEnquiry();
    current.setMatrixRoomId("!winner:example.invalid");
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.of(current));

    assertThat(publication.publish(openEnquiry(), MATRIX_ROOM_ID, List.of()))
        .contains("!winner:example.invalid");

    verifyNoInteractions(sessionService, revocations, consultants);
  }

  @Test
  void staleCreationDoesNotWriteIntoAnAcceptedCase() {
    var current = openEnquiry();
    current.setStatus(SessionStatus.IN_PROGRESS);
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.of(current));

    assertThat(publication.publish(openEnquiry(), MATRIX_ROOM_ID, List.of())).isEmpty();

    verifyNoInteractions(sessionService, revocations, consultants);
  }

  private Session openEnquiry() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setStatus(SessionStatus.INITIAL);
    session.setAgencyId(AGENCY_ID);
    return session;
  }
}
