package de.caritas.cob.userservice.api.workflow.delete.model;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the admin interface shows for a nightly deletion that kept the row: each failed store with
 * its fixed reason, and for a blocked database delete the table and constraint that still point at
 * the account (ORISO-Admin#1521). Never message content or personal data: reasons are the steps'
 * fixed texts, and only table and constraint names are taken from a database error.
 */
public final class DeletionFailureSummary {

  /** The width of {@code user.deletion_failure_reason}. */
  public static final int MAX_LENGTH = 512;

  // MariaDB/MySQL: ... a foreign key constraint fails (`schema`.`table`, CONSTRAINT `name` ...
  private static final Pattern MARIADB_FOREIGN_KEY =
      Pattern.compile("foreign key constraint fails \\(`[^`]*`\\.`([^`]+)`, CONSTRAINT `([^`]+)`");
  // H2: Referential integrity constraint violation: "NAME: PUBLIC.TABLE FOREIGN KEY(...
  private static final Pattern H2_FOREIGN_KEY =
      Pattern.compile(
          "Referential integrity constraint violation: \"([^:\"]+): [A-Z_]+\\.\"?([A-Za-z_]+)\"? FOREIGN KEY");

  private DeletionFailureSummary() {}

  /**
   * @return "STORE: reason; STORE: reason", at most {@link #MAX_LENGTH} characters, or {@code null}
   *     when nothing failed
   */
  public static String of(Collection<DeletionWorkflowError> errors) {
    if (errors == null || errors.isEmpty()) {
      return null;
    }
    var parts = new LinkedHashSet<String>();
    errors.forEach(error -> parts.add(error.getDeletionTargetType() + ": " + error.getReason()));
    var summary = String.join("; ", parts);
    return summary.length() <= MAX_LENGTH ? summary : summary.substring(0, MAX_LENGTH - 1) + "…";
  }

  /**
   * The table and constraint that blocked a delete, e.g. "still referenced by session
   * (session_ibfk_1)", read from the root cause of a foreign-key violation.
   */
  public static Optional<String> blockingReference(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      var message = cause.getMessage();
      if (message == null) {
        continue;
      }
      var mariaDb = MARIADB_FOREIGN_KEY.matcher(message);
      if (mariaDb.find()) {
        return Optional.of(describe(mariaDb.group(1), mariaDb.group(2)));
      }
      var h2 = H2_FOREIGN_KEY.matcher(message);
      if (h2.find()) {
        return Optional.of(describe(h2.group(2).toLowerCase(Locale.ROOT), h2.group(1)));
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return Optional.empty();
  }

  private static String describe(String table, String constraint) {
    return "still referenced by " + table + " (" + constraint + ")";
  }
}
