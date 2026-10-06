package de.caritas.cob.userservice.api.admin.service.admin;

import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Enriches authorized Admin list rows without guessing on identity-provider failures. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountLoginStatusService {
  private final IdentityAccountStatusLookup identityAccountStatusLookup;

  public Map<String, Boolean> activeByIds(Collection<String> userIds) {
    Map<String, Boolean> result = new HashMap<>();
    for (String id : new LinkedHashSet<>(userIds)) {
      if (id == null || id.isBlank()) {
        continue;
      }
      try {
        identityAccountStatusLookup.findEnabledById(id).ifPresent(active -> result.put(id, active));
      } catch (WebApplicationException | ProcessingException unavailable) {
        // Stop this page after one provider failure; unresolved rows remain unknown.
        log.warn(
            "Account login status is unavailable ({})", unavailable.getClass().getSimpleName());
        break;
      }
    }
    return result;
  }

  public Boolean activeOf(String userId) {
    return userId == null || userId.isBlank() ? null : activeByIds(List.of(userId)).get(userId);
  }
}
