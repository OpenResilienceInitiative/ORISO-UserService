package de.caritas.cob.userservice.api.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.matrix.MatrixSynapseService;
import de.caritas.cob.userservice.api.adapters.matrix.dto.MatrixCreateRoomResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.controller.TeamDiscussionController;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.model.Session.SessionStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyMatrixCredentialClient;
import de.caritas.cob.userservice.api.service.agency.dto.AgencyMatrixCredentialsDTO;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionCreationWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionFeatureGate;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionParticipantWriter;
import de.caritas.cob.userservice.api.service.teamdiscussion.TeamDiscussionRoomCleanupService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({TeamDiscussionCreationWriter.class, TeamDiscussionParticipantWriter.class})
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamDiscussionConcurrencyIT {
  @Autowired private TeamDiscussionRepository discussions;
  @Autowired private TeamDiscussionParticipantRepository participants;
  @Autowired private TeamDiscussionCreationWriter writer;
  @Autowired private TeamDiscussionParticipantWriter participantWriter;

  @AfterEach
  void cleanUp() {
    participants.deleteAll();
    discussions.deleteAll();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void simultaneousOpeningReachesOneSharedRoom(boolean samePerson) throws Exception {
    var sessions = mock(SessionRepository.class);
    var consultants = mock(ConsultantRepository.class);
    var agencies = mock(ConsultantAgencyRepository.class);
    var matrix = mock(MatrixSynapseService.class);
    var credentials = mock(AgencyMatrixCredentialClient.class);
    var gate = mock(TeamDiscussionFeatureGate.class);
    var session = new Session();
    session.setId(42001L);
    session.setAgencyId(7L);
    session.setTenantId(3L);
    session.setRegistrationType(RegistrationType.REGISTERED);
    session.setStatus(SessionStatus.NEW);
    when(sessions.findById(42001L)).thenReturn(Optional.of(session));
    var agencyCredentials = new AgencyMatrixCredentialsDTO();
    agencyCredentials.setMatrixUserId("@agency7:oriso");
    agencyCredentials.setMatrixPassword("test-only");
    when(credentials.fetchMatrixCredentials(7L)).thenReturn(Optional.of(agencyCredentials));
    when(matrix.loginUser("agency7", "test-only")).thenReturn("agency-token");
    for (String id : new String[] {"alice", "bob"}) {
      var consultant = new Consultant();
      consultant.setId(id);
      consultant.setMatrixUserId("@" + id + ":oriso");
      when(consultants.findById(id)).thenReturn(Optional.of(consultant));
      when(agencies.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(id, 7L)).thenReturn(true);
      when(matrix.loginAsUserAccessToken("@" + id + ":oriso")).thenReturn(id + "-token");
    }
    // The external service receives both creates before either database insert can win.
    var created = new AtomicInteger();
    var simultaneousCreation = new CyclicBarrier(2);
    var rooms = new ConcurrentHashMap<String, Set<String>>();
    when(matrix.createRoom(anyString(), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              String roomId = "!created-" + created.incrementAndGet() + ":oriso";
              rooms.put(roomId, ConcurrentHashMap.newKeySet());
              simultaneousCreation.await(10, TimeUnit.SECONDS);
              var response = new MatrixCreateRoomResponseDTO();
              response.setRoomId(roomId);
              return ResponseEntity.ok(response);
            });
    when(matrix.joinRoom(anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              rooms.get(invocation.<String>getArgument(0)).add(invocation.getArgument(1));
              return true;
            });
    when(matrix.purgeRoomOrConfirmGone(anyString()))
        .thenAnswer(
            invocation -> {
              rooms.remove(invocation.<String>getArgument(0));
              return MatrixSynapseService.RoomPurgeOutcome.PURGED;
            });
    var facade =
        new TeamDiscussionFacade(
            sessions,
            consultants,
            agencies,
            discussions,
            matrix,
            credentials,
            gate,
            writer,
            participantWriter,
            mock(TeamDiscussionRoomCleanupService.class));
    var alice = controller(facade, "alice");
    var bob = controller(facade, samePerson ? "alice" : "bob");
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> alice.getOrCreate(42001L).getBody());
      var second = executor.submit(() -> bob.getOrCreate(42001L).getBody());
      var aliceRoom = first.get(20, TimeUnit.SECONDS).matrixRoomId();
      var bobRoom = second.get(20, TimeUnit.SECONDS).matrixRoomId();
      assertThat(bobRoom).isEqualTo(aliceRoom);
      assertThat(alice.get(42001L).getBody().matrixRoomId()).isEqualTo(aliceRoom);
      assertThat(rooms.keySet()).containsExactly(aliceRoom);
      assertThat(rooms.get(aliceRoom))
          .containsExactlyInAnyOrderElementsOf(
              samePerson ? Set.of("alice-token") : Set.of("alice-token", "bob-token"));
      assertThat(participants.count()).isEqualTo(samePerson ? 1 : 2);
    }
  }

  private TeamDiscussionController controller(TeamDiscussionFacade facade, String id) {
    var user = mock(AuthenticatedUser.class);
    when(user.getUserId()).thenReturn(id);
    return new TeamDiscussionController(facade, user);
  }
}
