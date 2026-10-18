package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.model.TeamDiscussion;
import de.caritas.cob.userservice.api.port.out.TeamDiscussionRepository;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits room ownership before joining callers or compensating an unused room. */
@Component
@RequiredArgsConstructor
public class TeamDiscussionCreationWriter {
  private final @NonNull TeamDiscussionRepository discussions;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TeamDiscussion create(TeamDiscussion discussion) {
    return discussions.saveAndFlush(discussion);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<TeamDiscussion> findCommitted(Long sessionId) {
    return discussions.findBySessionId(sessionId);
  }
}
