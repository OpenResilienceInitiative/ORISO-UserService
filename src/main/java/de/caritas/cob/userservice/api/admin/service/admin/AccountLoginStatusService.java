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
  private final ManagedAccountStatusService accountStatus;

  @Autowired
  public AccountLoginStatusService(
      IdentityAccountStatusLookup identityAccountStatusLookup,
      ManagedAccountStatusService accountStatus) {
    this(identityAccountStatusLookup, System::nanoTime, accountStatus);
  }

  public AccountLoginStatusService(IdentityAccountStatusLookup identityAccountStatusLookup) {
    this(identityAccountStatusLookup, System::nanoTime, null);
  }

  AccountLoginStatusService(
      IdentityAccountStatusLookup identityAccountStatusLookup, LongSupplier nanoTime) {
    this(identityAccountStatusLookup, nanoTime, null);
  }

  private AccountLoginStatusService(
      IdentityAccountStatusLookup identityAccountStatusLookup,
      LongSupplier nanoTime,
      ManagedAccountStatusService accountStatus) {
    this.accountStatus = accountStatus;
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
    Map<Long, Boolean> tenantStates = new HashMap<>();
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
        Boolean enabled = identityAccountStatusLookup.findEnabledById(id).orElse(null);
        Boolean active =
            accountStatus == null
                ? enabled
                : accountStatus.activeForList(id, enabled, tenantStates);
        if (active != null) result.put(id, active);
      } catch (WebApplicationException failure) {
        if (failure.getResponse().getStatus() < 500) {
          throw failure;
        }
        // Provider outages leave unread rows unknown; invalid/forbidden reads are not outages.
        log.warn("Account login status is unavailable ({})", failure.getClass().getSimpleName());
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

  /** Existing filtered/menu DTOs use the same bounded effective status as search rows. */
  public void appendAdminStatuses(
      List<de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO> responses) {
    if (responses == null) return;
    var rows =
        responses.stream()
            .filter(java.util.Objects::nonNull)
            .map(de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO::getEmbedded)
            .filter(java.util.Objects::nonNull)
            .toList();
    var statuses =
        activeByIds(
            rows.stream()
                .map(de.caritas.cob.userservice.api.adapters.web.dto.AdminDTO::getId)
                .toList());
    rows.forEach(row -> row.setActive(statuses.get(row.getId())));
  }

  public void appendConsultantStatuses(
      List<de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO> responses) {
    if (responses == null) return;
    var rows =
        responses.stream()
            .filter(java.util.Objects::nonNull)
            .map(
                de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO
                    ::getEmbedded)
            .filter(java.util.Objects::nonNull)
            .toList();
    var statuses =
        activeByIds(
            rows.stream()
                .map(de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO::getId)
                .toList());
    rows.forEach(row -> row.setActive(statuses.get(row.getId())));
  }
}
