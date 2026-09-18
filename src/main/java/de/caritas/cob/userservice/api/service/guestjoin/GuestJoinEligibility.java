package de.caritas.cob.userservice.api.service.guestjoin;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.GuestJoinAttempt;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Shared guard for every external phase and local session finalization. */
@Component
@RequiredArgsConstructor
public class GuestJoinEligibility {
  private final AgencyInviteLinkRepository invites;

  public void requireActiveTarget(GuestJoinAttempt attempt) {
    if (!attempt.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
      throw new ForbiddenException("Guest Join attempt expired");
    }
    var link =
        invites
            .findById(attempt.getInviteLinkId())
            .orElseThrow(() -> new ForbiddenException("Guest invitation is no longer available"));
    if (!"ACTIVE".equals(link.getStatus())
        || !"LIVE_CHAT".equals(link.getChatType())
        || (link.getExpiresAt() != null && !link.getExpiresAt().isAfter(LocalDateTime.now()))
        || !attempt.getTenantId().equals(link.getTenantId())
        || !attempt.getTopicId().equals(link.getTopicId())
        || (link.getConsultingTypeId() != null
            && !attempt.getConsultingTypeId().equals(link.getConsultingTypeId()))) {
      throw new ForbiddenException("Guest invitation context is no longer valid");
    }
  }
}
