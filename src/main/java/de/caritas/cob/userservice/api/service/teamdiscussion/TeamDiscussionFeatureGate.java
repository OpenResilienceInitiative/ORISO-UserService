package de.caritas.cob.userservice.api.service.teamdiscussion;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Enforces the Team-Besprechung feature toggle at the backend boundary (US#473 / ADR-016).
 *
 * <p>Currently a deploy-level property ({@code team-discussion.enabled}). The decided target is a
 * tenant-level setting following the {@code GroupChatFeatureGate} pattern; that needs a companion
 * TenantService settings field first and is tracked as a follow-up on the epic (FE#512). This gate
 * is the single seam where that check will slot in — callers stay unchanged.
 */
@Service
public class TeamDiscussionFeatureGate {

  @Value("${team-discussion.enabled:true}")
  private boolean teamDiscussionEnabled;

  public void requireEnabled() {
    if (!teamDiscussionEnabled) {
      throw new ForbiddenException("Team discussion is disabled");
    }
  }

  public boolean isEnabled() {
    return teamDiscussionEnabled;
  }
}
