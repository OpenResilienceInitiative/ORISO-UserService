package de.caritas.cob.userservice.api.service.availability;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Tracks which consultants are currently available for live chat. Availability is driven by
 * explicit events from the consultant app (Live Chat toggle on/off, logout) and kept alive by a
 * heartbeat while the app stays open, so a crashed/closed browser auto-expires after the configured
 * window.
 *
 * <p>Matrix presence is unreliable for this: a consultant waiting for a new anonymous enquiry has
 * no open chat and therefore generates no Matrix activity.
 */
@Component
public class ConsultantActivityRegistry {

  private final ConcurrentHashMap<String, Long> availableSince = new ConcurrentHashMap<>();

  /** Marks the consultant available now (Live Chat enabled). Adds them if not present. */
  public void markAvailable(String consultantId) {
    if (consultantId != null && !consultantId.isBlank()) {
      availableSince.put(consultantId, System.currentTimeMillis());
    }
  }

  /** Marks the consultant unavailable immediately (Live Chat disabled or logout). */
  public void markUnavailable(String consultantId) {
    if (consultantId != null) {
      availableSince.remove(consultantId);
    }
  }

  /**
   * Heartbeat keep-alive: refreshes the timestamp only for consultants already marked available.
   * Never adds a consultant, so generic requests can't keep a disabled consultant counted.
   */
  public void refreshIfAvailable(String consultantId) {
    if (consultantId != null) {
      availableSince.computeIfPresent(consultantId, (id, ts) -> System.currentTimeMillis());
    }
  }

  /** Returns the subset of the given consultant IDs marked available within the window (in ms). */
  public Set<String> filterActive(Collection<String> consultantIds, long windowMs) {
    long cutoff = System.currentTimeMillis() - windowMs;
    return consultantIds.stream()
        .filter(
            consultantId -> {
              Long lastSeen = availableSince.get(consultantId);
              return lastSeen != null && lastSeen >= cutoff;
            })
        .collect(Collectors.toSet());
  }
}
