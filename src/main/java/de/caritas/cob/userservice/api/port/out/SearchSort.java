package de.caritas.cob.userservice.api.port.out;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

/** Sorting for the admin/consultant infix searches in {@link AdminRepository} and co. */
public final class SearchSort {

  /**
   * Select alias for "last updated" (update date, else create date) — the value the Admin shows, so
   * the list order matches the column.
   */
  public static final String LAST_UPDATED = "lastUpdated";

  private static final String ID = "id";

  private SearchSort() {}

  /** Sorts by the given select alias, with the id as a stable tiebreak for equal values. */
  public static PageRequest pageRequestOf(
      int pageNumber, int pageSize, String fieldName, boolean isAscending) {
    var direction = isAscending ? Direction.ASC : Direction.DESC;
    return PageRequest.of(
        pageNumber, pageSize, Sort.by(direction, fieldName).and(Sort.by(direction, ID)));
  }
}
