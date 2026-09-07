package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Executes the purge delete against the H2 testing schema (#1118). The participant table has no
 * foreign key to lean on, so only a real execution proves the two deletes are one unit.
 */
@DataJpaTest
@Import(TeamDiscussionDeletionWriter.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
// The writer opens a REQUIRES_NEW transaction, which cannot see rows an uncommitted test
// transaction inserted. Run without a test transaction and clean up by hand instead.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TeamDiscussionDeletionWriterIT {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 12, 0);

  @MockitoSpyBean private TeamDiscussionRepository discussions;
  @Autowired private TeamDiscussionParticipantRepository participants;
  @Autowired private TeamDiscussionDeletionWriter underTest;

  @AfterEach
  void cleanUp() {
    participants.deleteAll();
    discussions.deleteAll();
  }

  /** The guarantee in the class Javadoc is only real if the propagation enforces it. */
  @Test
  void deleteDiscussionAndParticipants_runsInItsOwnTransaction_regardlessOfTheCaller()
      throws NoSuchMethodException {
    var transactional =
        TeamDiscussionDeletionWriter.class
            .getMethod("deleteDiscussionAndParticipants", TeamDiscussion.class)
            .getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
  }

  @Test
  void deleteDiscussionAndParticipants_removesTheRowAndAllItsParticipants_andNothingElse() {
    TeamDiscussion doomed = discussion(1L);
    TeamDiscussion kept = discussion(2L);
    participant(doomed, "consultant-a");
    participant(doomed, "consultant-b");
    participant(kept, "consultant-a");

    underTest.deleteDiscussionAndParticipants(doomed);

    assertThat(discussions.findAll())
        .extracting(TeamDiscussion::getId)
        .containsExactly(kept.getId());
    assertThat(participants.findAll())
        .extracting(TeamDiscussionParticipant::getTeamDiscussionId)
        .containsExactly(kept.getId());
  }

  /**
   * The participant delete and the row delete are one unit: if the row cannot go, the participants
   * must come back too, or the next run finds a discussion without its participant records.
   */
  @Test
  void deleteDiscussionAndParticipants_rollsBackTheParticipantDelete_When_theRowDeleteFails() {
    TeamDiscussion doomed = discussion(1L);
    participant(doomed, "consultant-a");
    doThrow(new DataIntegrityViolationException("simulated"))
        .when(discussions)
        .delete(any(TeamDiscussion.class));

    assertThatThrownBy(() -> underTest.deleteDiscussionAndParticipants(doomed))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(discussions.findById(doomed.getId())).isPresent();
    assertThat(participants.findByTeamDiscussionId(doomed.getId())).hasSize(1);
  }

  private TeamDiscussion discussion(long sessionId) {
    return discussions.save(
        TeamDiscussion.builder()
            .sessionId(sessionId)
            .matrixRoomId("!room-" + sessionId + ":matrix.example.com")
            .createDate(NOW.minusDays(10))
            .tenantId(1L)
            .build());
  }

  private void participant(TeamDiscussion discussion, String consultantId) {
    participants.save(
        TeamDiscussionParticipant.builder()
            .teamDiscussionId(discussion.getId())
            .consultantId(consultantId)
            .joinDate(NOW.minusDays(5))
            .build());
  }
}
