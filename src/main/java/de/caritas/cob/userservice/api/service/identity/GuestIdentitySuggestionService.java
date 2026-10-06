package de.caritas.cob.userservice.api.service.identity;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Read-only, bounded candidate lookup. Availability is not a reservation. */
@Service
@RequiredArgsConstructor
public class GuestIdentitySuggestionService {
  private static final int MAX_ATTEMPTS = 32;
  private final GuestIdentityCatalog catalog;
  private final GuestUsernameAvailability availability;

  public List<GuestIdentitySuggestion> suggest(String locale, int count, List<String> exclude) {
    if ((count != 1 && count != 4)
        || locale == null
        || locale.length() > 20
        || !locale.matches("[a-zA-Z]{2,8}([@._-][a-zA-Z0-9]{1,12})*")
        || exclude == null
        || exclude.size() > 20
        || exclude.stream().anyMatch(name -> name == null || !name.matches("[a-z0-9_]{3,30}"))) {
      throw new BadRequestException(
          "Expected count 1 or 4, a locale and at most 20 valid excluded usernames");
    }
    var seen = new HashSet<>(exclude);
    var results = new ArrayList<GuestIdentitySuggestion>();
    for (int attempt = 0; attempt < MAX_ATTEMPTS && results.size() < count; attempt++) {
      var candidate = catalog.next(locale);
      if (seen.add(candidate.username()) && availability.isAvailable(candidate.username())) {
        results.add(candidate);
      }
    }
    if (results.size() != count)
      throw new ServiceUnavailableException("Guest names are temporarily unavailable");
    return List.copyOf(results);
  }
}
