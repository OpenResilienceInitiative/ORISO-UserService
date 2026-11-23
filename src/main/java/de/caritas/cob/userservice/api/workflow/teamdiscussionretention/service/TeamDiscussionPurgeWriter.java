package de.caritas.cob.userservice.api.workflow.teamdiscussionretention.service;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes one team discussion and its participant records in a single transaction (#1116).
 *
 * <p>Kept apart from {@link TeamDiscussionRetentionService} so the database delete runs in its own
 * transaction after the Matrix purge has succeeded, never around the remote call. {@code
 * REQUIRES_NEW} enforces that regardless of the caller, as {@code ScheduledTaskClaimWriter} does: a
 * transactional caller must not be able to stretch this delete around the Synapse call or roll it
 * back after the room is already gone. The participant table carries no foreign key to {@code
 * team_discussion}, so both deletes are explicit.
 */
@Component
@RequiredArgsConstructor
public class TeamDiscussionPurgeWriter {

  private final @NonNull TeamDiscussionRepository teamDiscussionRepository;
  private final @NonNull TeamDiscussionParticipantRepository participantRepository;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deleteDiscussionAndParticipants(TeamDiscussion discussion) {
    participantRepository.deleteByTeamDiscussionId(discussion.getId());
    teamDiscussionRepository.deleteById(discussion.getId());
  }
}
