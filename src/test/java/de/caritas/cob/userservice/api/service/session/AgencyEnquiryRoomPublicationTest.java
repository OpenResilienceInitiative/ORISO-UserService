package de.caritas.cob.userservice.api.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyEnquiryRoomPublicationTest {

  private static final String MATRIX_ROOM_ID = "!new:example.invalid";
  private static final long SESSION_ID = 25L;
  private static final long AGENCY_ID = 42L;
  private static final long TENANT_ID = 34L;
  private static final List<InitialRoomContact> CONTACTS =
      List.of(new InitialRoomContact("a", "@a:example.invalid"));

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

  @ParameterizedTest(name = "{0}")
  @MethodSource("bindingChanges")
  void refusesPublicationWhenTheLockedBindingChanged(String field, Consumer<Session> change) {
    var current = openEnquiry();
    change.accept(current);
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.of(current));

    assertThat(publication.publish(openEnquiry(), MATRIX_ROOM_ID, CONTACTS)).as(field).isEmpty();

    assertThat(current.getMatrixRoomId()).isNull();
    verifyNoInteractions(sessionService, revocations);
  }

  private static Stream<Arguments> bindingChanges() {
    return Stream.of(
        bindingChange("agency", current -> current.setAgencyId(AGENCY_ID + 1)),
        bindingChange("tenant", current -> current.setTenantId(TENANT_ID + 1)),
        bindingChange("ownership revision", current -> current.setOwnershipRevision(1L)),
        bindingChange("direct assignment", current -> current.setIsConsultantDirectlySet(true)));
  }

  private static Arguments bindingChange(String field, Consumer<Session> change) {
    return Arguments.of(field, change);
  }

  @Test
  void refusesPublicationWhenTheSessionNoLongerExists() {
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.empty());

    assertThat(publication.publish(openEnquiry(), MATRIX_ROOM_ID, CONTACTS)).isEmpty();

    verifyNoInteractions(sessionService, revocations);
  }

  @Test
  void doesNotSaveTheBindingWhenStagingRevocationsFails() {
    var current = openEnquiry();
    var failure = new IllegalStateException("synthetic pending revocation failure");
    when(sessions.lockFresh(SESSION_ID)).thenReturn(Optional.of(current));
    doThrow(failure).when(revocations).stageDuringPublication(current, CONTACTS);

    assertThatThrownBy(() -> publication.publish(openEnquiry(), MATRIX_ROOM_ID, CONTACTS))
        .isSameAs(failure);

    verifyNoInteractions(sessionService);
  }

  private Session openEnquiry() {
    var session = new Session();
    session.setId(SESSION_ID);
    session.setStatus(SessionStatus.INITIAL);
    session.setAgencyId(AGENCY_ID);
    session.setTenantId(TENANT_ID);
    return session;
  }
}
