package de.caritas.cob.userservice.api.service.availability;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Tracks the last time each consultant made an authenticated request, used as a real-time "the
 * consultant has the app open" signal for live-chat availability.
 *
 * <p>Matrix presence is unreliable here: a consultant waiting for a new anonymous enquiry has no
 * open chat and therefore generates no Matrix activity. While the consultant app is open it polls
 * UserService at least every ~20s, so recent authenticated activity is an accurate liveness signal.
 */
@Component
public class ConsultantActivityRegistry {

  private final ConcurrentHashMap<String, Long> lastSeenByConsultantId = new ConcurrentHashMap<>();

  /** Records that the given consultant is active right now. */
  public void recordActivity(String consultantId) {
    if (consultantId != null && !consultantId.isBlank()) {
      lastSeenByConsultantId.put(consultantId, System.currentTimeMillis());
    }
  }

  /** Returns the subset of the given consultant IDs seen active within the window (in ms). */
  public Set<String> filterActive(Collection<String> consultantIds, long windowMs) {
    long cutoff = System.currentTimeMillis() - windowMs;
    return consultantIds.stream()
        .filter(
            consultantId -> {
              Long lastSeen = lastSeenByConsultantId.get(consultantId);
              return lastSeen != null && lastSeen >= cutoff;
            })
        .collect(Collectors.toSet());
  }
}
