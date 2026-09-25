package de.caritas.cob.userservice.api.admin.service.listpreference;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AdminListPreference;
import de.caritas.cob.userservice.api.port.out.AdminListPreferenceRepository;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Keeps each admin's last chosen sort per user-list tab (#1263), always scoped to the caller. The
 * allowed fields mirror the sort fields the matching admin search endpoints accept.
 */
@Service
@RequiredArgsConstructor
public class AdminListPreferenceService {

  private static final Set<String> BASE_FIELDS =
      Set.of("FIRSTNAME", "LASTNAME", "EMAIL", "UPDATE_DATE");
  private static final Set<String> TENANT_FIELDS =
      Set.of("FIRSTNAME", "LASTNAME", "EMAIL", "UPDATE_DATE", "TENANT_ID");
  private static final Map<String, Set<String>> FIELDS_BY_TAB =
      Map.of(
          "consultants", BASE_FIELDS,
          "agency-admins", BASE_FIELDS,
          "tenant-admins", TENANT_FIELDS,
          "platform-admins", TENANT_FIELDS);
  private static final Set<String> ORDERS = Set.of("ASC", "DESC");

  private final @NonNull AdminListPreferenceRepository repository;

  public record ListSort(String field, String order) {}

  /** Saved sorts of the caller, keyed by tab. */
  public Map<String, ListSort> findOwnSorts(String userId) {
    var sorts = new TreeMap<String, ListSort>();
    repository
        .findByUserId(userId)
        .forEach(p -> sorts.put(p.getTab(), new ListSort(p.getSortField(), p.getSortOrder())));
    return sorts;
  }

  /** Replaces the caller's sort for one tab; rejects anything the tab cannot sort by. */
  // Must not become @Transactional: the retry below needs the failed insert's own rollback.
  public void saveOwnSort(String userId, String tab, ListSort sort) {
    if (userId == null) {
      throw new ForbiddenException("a list sort needs an identified caller");
    }
    var allowedFields = FIELDS_BY_TAB.get(tab);
    if (allowedFields == null) {
      throw new BadRequestException("unknown list tab");
    }
    if (sort == null || !allowedFields.contains(sort.field())) {
      throw new BadRequestException("this list tab cannot be sorted by that field");
    }
    if (!ORDERS.contains(sort.order())) {
      throw new BadRequestException("sort order must be ASC or DESC");
    }
    try {
      upsert(userId, tab, sort);
    } catch (DataIntegrityViolationException concurrentFirstWrite) {
      // Two devices saved the same tab's first sort at once; the row exists now, so update it.
      upsert(userId, tab, sort);
    }
  }

  private void upsert(String userId, String tab, ListSort sort) {
    var preference =
        repository
            .findByUserIdAndTab(userId, tab)
            .orElseGet(() -> AdminListPreference.builder().userId(userId).tab(tab).build());
    preference.setSortField(sort.field());
    preference.setSortOrder(sort.order());
    preference.setUpdateDate(LocalDateTime.now());
    repository.save(preference);
  }
}
