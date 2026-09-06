package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.time.LocalDateTime;
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
 * Executes the retention queries and the purge delete against the H2 testing schema (#1116).
 *
 * <p>The selection predicate decides which team rooms are wiped, and the participant table has no
 * foreign key to lean on, so only a real execution proves the right rows go and nothing is left
 * behind.
 */
@DataJpaTest
@Import(TeamDiscussionPurgeWriter.class)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class TeamDiscussionPurgeWriterIT {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 6, 12, 0);
  private static final LocalDateTime CUTOFF = NOW.minusDays(90);

  @MockitoSpyBean private TeamDiscussionRepository discussions;
  @Autowired private TeamDiscussionParticipantRepository participants;
  @Autowired private TeamDiscussionPurgeWriter underTest;

  @Test
  void findByStatusAndArchiveDateBefore_selectsOnlyArchivedDiscussionsPastTheCutoff() {
    TeamDiscussion stale = archived(1L, NOW.minusDays(200), NOW.minusDays(120));
    archived(2L, NOW.minusDays(200), NOW.minusDays(10));
    open(3L, NOW.minusDays(200));

    var expired =
        discussions.findByStatusAndArchiveDateBefore(TeamDiscussion.Status.ARCHIVED, CUTOFF);

    assertThat(expired).extracting(TeamDiscussion::getId).containsExactly(stale.getId());
  }

  /** An abandoned discussion is measured from creation, under the same period. */
  @Test
  void findByStatusAndCreateDateBefore_selectsOnlyOpenDiscussionsCreatedPastTheCutoff() {
    TeamDiscussion abandoned = open(1L, NOW.minusDays(120));
    open(2L, NOW.minusDays(10));
    archived(3L, NOW.minusDays(200), NOW.minusDays(10));

    var expired = discussions.findByStatusAndCreateDateBefore(TeamDiscussion.Status.OPEN, CUTOFF);

    assertThat(expired).extracting(TeamDiscussion::getId).containsExactly(abandoned.getId());
  }

  /** Strictly before: a row exactly on the cutoff is still inside its retention period. */
  @Test
  void cutoffsAreExclusive() {
    archived(1L, NOW.minusDays(200), CUTOFF);
    open(2L, CUTOFF);

    assertThat(discussions.findByStatusAndArchiveDateBefore(TeamDiscussion.Status.ARCHIVED, CUTOFF))
        .isEmpty();
    assertThat(discussions.findByStatusAndCreateDateBefore(TeamDiscussion.Status.OPEN, CUTOFF))
        .isEmpty();
  }

  @Test
  void deleteDiscussionAndParticipants_removesTheRowAndAllItsParticipants_andNothingElse() {
    TeamDiscussion doomed = archived(1L, NOW.minusDays(200), NOW.minusDays(120));
    TeamDiscussion kept = archived(2L, NOW.minusDays(200), NOW.minusDays(10));
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
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void deleteDiscussionAndParticipants_rollsBackTheParticipantDelete_When_theRowDeleteFails() {
    TeamDiscussion doomed = archived(1L, NOW.minusDays(200), NOW.minusDays(120));
    participant(doomed, "consultant-a");
    doThrow(new DataIntegrityViolationException("simulated")).when(discussions).deleteById(any());
    try {
      assertThatThrownBy(() -> underTest.deleteDiscussionAndParticipants(doomed))
          .isInstanceOf(DataIntegrityViolationException.class);

      assertThat(discussions.findById(doomed.getId())).isPresent();
      assertThat(participants.findByTeamDiscussionId(doomed.getId())).hasSize(1);
    } finally {
      // No test transaction to roll back here, so clean up by hand.
      participants.deleteAll();
      discussions.deleteAll();
    }
  }

  private TeamDiscussion archived(long sessionId, LocalDateTime created, LocalDateTime archived) {
    return discussions.save(
        TeamDiscussion.builder()
            .sessionId(sessionId)
            .matrixRoomId("!room-" + sessionId + ":matrix.example.com")
            .status(TeamDiscussion.Status.ARCHIVED)
            .createDate(created)
            .archiveDate(archived)
            .tenantId(1L)
            .build());
  }

  private TeamDiscussion open(long sessionId, LocalDateTime created) {
    return discussions.save(
        TeamDiscussion.builder()
            .sessionId(sessionId)
            .matrixRoomId("!room-" + sessionId + ":matrix.example.com")
            .status(TeamDiscussion.Status.OPEN)
            .createDate(created)
            .tenantId(1L)
            .build());
  }

  private void participant(TeamDiscussion discussion, String consultantId) {
    participants.save(
        TeamDiscussionParticipant.builder()
            .teamDiscussionId(discussion.getId())
            .consultantId(consultantId)
            .joinDate(NOW.minusDays(150))
            .build());
  }
}
