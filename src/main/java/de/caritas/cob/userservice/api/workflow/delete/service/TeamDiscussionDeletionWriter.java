package de.caritas.cob.userservice.api.workflow.delete.service;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes one team discussion and its participant records in a single transaction (#1118).
 *
 * <p>Kept apart from {@link TeamDiscussionPurgeService} so the database delete never shares a
 * transaction with the Synapse call that precedes it. {@code REQUIRES_NEW} enforces that regardless
 * of the caller, as {@code ScheduledTaskClaimWriter} does. The participant table has no foreign key
 * to {@code team_discussion}, so both deletes are explicit; if the row cannot go, the participant
 * delete rolls back with it and the next run finds the discussion whole.
 */
@Component
@RequiredArgsConstructor
public class TeamDiscussionDeletionWriter {

  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionParticipantRepository participantRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteDiscussionAndParticipants(TeamDiscussion discussion) {
    participantRepository.deleteAllByTeamDiscussionId(discussion.getId());
    teamDiscussionRepository.delete(discussion);
  }
}
