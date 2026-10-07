package de.caritas.cob.userservice.api.admin.service.admin;

import de.caritas.cob.userservice.api.port.out.IdentityAccountStatusLookup;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Enriches authorized Admin list rows without guessing on identity-provider failures. */
@Service
@Slf4j
public class AccountLoginStatusService {
  private static final int MAX_LOOKUPS_PER_PAGE = 100;
  private static final long PAGE_LOOKUP_BUDGET_NANOS = Duration.ofSeconds(2).toNanos();

  private final IdentityAccountStatusLookup identityAccountStatusLookup;
  private final LongSupplier nanoTime;

  @Autowired
  public AccountLoginStatusService(IdentityAccountStatusLookup identityAccountStatusLookup) {
    this(identityAccountStatusLookup, System::nanoTime);
  }

  AccountLoginStatusService(
      IdentityAccountStatusLookup identityAccountStatusLookup, LongSupplier nanoTime) {
    this.identityAccountStatusLookup = identityAccountStatusLookup;
    this.nanoTime = nanoTime;
  }

  /**
   * Reads at most 100 distinct authorized IDs, stopping before another read after two seconds.
   *
   * <p>This is a scheduling budget, not a response deadline: an in-flight read and its one session
   * retry remain governed by the identity adapter's existing transport timeouts. Unread rows remain
   * unknown; the caller's rows, total and pagination are unchanged.
   */
  public Map<String, Boolean> activeByIds(Collection<String> userIds) {
    Map<String, Boolean> result = new HashMap<>();
    long startedAt = nanoTime.getAsLong();
    int lookups = 0;
    for (String id : new LinkedHashSet<>(userIds)) {
      if (id == null || id.isBlank()) {
        continue;
      }
      if (lookups >= MAX_LOOKUPS_PER_PAGE
          || nanoTime.getAsLong() - startedAt >= PAGE_LOOKUP_BUDGET_NANOS) {
        break;
      }
      lookups++;
      try {
        identityAccountStatusLookup.findEnabledById(id).ifPresent(active -> result.put(id, active));
      } catch (WebApplicationException failure) {
        if (failure.getResponse().getStatus() < 500) {
          throw failure;
        }
        // Provider outages leave unread rows unknown; invalid/forbidden reads are not outages.
        log.warn("Account login status is unavailable ({})", failure.getClass().getSimpleName());
        break;
      } catch (org.springframework.web.client.RestClientResponseException failure) {
        if (failure.getStatusCode().value() < 500) throw failure;
        log.warn("Account login status is unavailable ({})", failure.getClass().getSimpleName());
        break;
      } catch (org.springframework.web.client.ResourceAccessException unavailable) {
        log.warn(
            "Account login status is unavailable ({})", unavailable.getClass().getSimpleName());
        break;
      } catch (ProcessingException unavailable) {
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
