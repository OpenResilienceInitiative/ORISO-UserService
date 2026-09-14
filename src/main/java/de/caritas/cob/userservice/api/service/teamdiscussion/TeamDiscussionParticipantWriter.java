package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.model.TeamDiscussionParticipant;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionParticipantRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Isolates concurrent participant inserts so an already joined caller remains successful. */
@Component
@RequiredArgsConstructor
public class TeamDiscussionParticipantWriter {
  private final @NonNull TeamDiscussionParticipantRepository participants;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(TeamDiscussionParticipant participant) {
    participants.saveAndFlush(participant);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public boolean isRecorded(Long discussionId, String consultantId) {
    return participants.existsByTeamDiscussionIdAndConsultantId(discussionId, consultantId);
  }
}
