package de.caritas.cob.userservice.api.service;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.supervision.SupervisionNotes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SupervisorLogsService {

  private final @NonNull NamedParameterJdbcTemplate namedParameterJdbcTemplate;
  private final @NonNull AdminScope adminScope;

  public SupervisorLogsResult listSupervisorLogs(int page, int perPage) {
    final int safePerPage = Math.min(Math.max(perPage, 1), 200);
    final int safePage = Math.max(page, 1);
    final int offset = (safePage - 1) * safePerPage;

    // One reach for both filters: the platform reads every Träger, everyone else their own.
    final AdminScope.Reach reach = adminScope.current();
    final Long tenantId = reach.tenantId();
    final Optional<Set<Long>> agencyIds =
        reach instanceof AdminScope.Agencies agencies
            ? Optional.of(agencies.ids())
            : Optional.empty();

    // Fail closed: a Beratungsstellen-Admin without a single agency assignment reads nothing —
    // never the whole tenant, and never an `IN ()` that the database would reject.
    if (agencyIds.isPresent() && agencyIds.get().isEmpty()) {
      return SupervisorLogsResult.builder()
          .data(List.of())
          .total(0L)
          .page(safePage)
          .perPage(safePerPage)
          .build();
    }

    // Empty for tenant-wide admins, an agency filter for Beratungsstellen-Admins. Appended rather
    // than parameterised because a NULL collection cannot be expanded into an `IN` list.
    final String agencyFilter = agencyIds.isPresent() ? "\n  AND s.agency_id IN (:agencyIds)" : "";

    MapSqlParameterSource params =
        new MapSqlParameterSource()
            .addValue("tenantId", tenantId)
            .addValue("limit", safePerPage)
            .addValue("offset", offset);
    agencyIds.ifPresent(ids -> params.addValue("agencyIds", ids));

    // ORISO-Frontend#2494 *6: a swap is ONE event. The new row (it carries
    // replaces_session_supervisor_id) is listed as REPLACED with the previous supervisor; the
    // removal half of the old row is not listed on its own. Removals name who made them
    // (removed_by_consultant_id; NULL for rows recorded before, shown as unknown).
    final String tenantFilter =
        "(:tenantId IS NULL OR s.tenant_id = :tenantId OR (:tenantId = 1 AND s.tenant_id IS NULL))"
            + agencyFilter;
    final String notReplaced =
        "NOT EXISTS (SELECT 1 FROM session_supervisor nx"
            + " WHERE nx.replaces_session_supervisor_id = ss.id)";

    Long total =
        namedParameterJdbcTemplate.queryForObject(
            "SELECT\n"
                + "  (SELECT COUNT(*) FROM session_supervisor ss JOIN session s ON s.id = ss.session_id\n"
                + "   WHERE "
                + tenantFilter
                + ")\n"
                + "  +\n"
                + "  (SELECT COUNT(*) FROM session_supervisor ss JOIN session s ON s.id = ss.session_id\n"
                + "   WHERE ss.removed_date IS NOT NULL AND "
                + notReplaced
                + " AND "
                + tenantFilter
                + ") AS total",
            params,
            Long.class);

    List<SupervisorLogEntry> data =
        namedParameterJdbcTemplate.query(
            "SELECT e.*\n"
                + "FROM (\n"
                + "  SELECT\n"
                + "    ss.id AS relationId,\n"
                + "    ss.session_id AS sessionId,\n"
                + "    CASE WHEN ss.replaces_session_supervisor_id IS NULL THEN 'ADDED' ELSE 'REPLACED' END AS action,\n"
                + "    ss.added_date AS eventDate,\n"
                + "    ss.supervisor_consultant_id AS supervisorConsultantId,\n"
                + "    sup.username AS supervisorUsername,\n"
                + "    "
                + nameOf("sup")
                + " AS supervisorName,\n"
                + "    prev.consultant_id AS previousSupervisorConsultantId,\n"
                + "    prev.username AS previousSupervisorUsername,\n"
                + "    "
                + nameOf("prev")
                + " AS previousSupervisorName,\n"
                + "    ss.added_by_consultant_id AS actorConsultantId,\n"
                + "    act.username AS actorUsername,\n"
                + "    "
                + nameOf("act")
                + " AS actorName,\n"
                + "    ss.notes AS notes\n"
                + "  FROM session_supervisor ss\n"
                + "  JOIN session s ON s.id = ss.session_id\n"
                + "  JOIN consultant sup ON sup.consultant_id = ss.supervisor_consultant_id\n"
                + "  JOIN consultant act ON act.consultant_id = ss.added_by_consultant_id\n"
                + "  LEFT JOIN session_supervisor pss ON pss.id = ss.replaces_session_supervisor_id\n"
                + "  LEFT JOIN consultant prev ON prev.consultant_id = pss.supervisor_consultant_id\n"
                + "  WHERE "
                + tenantFilter
                + "\n"
                + "\n"
                + "  UNION ALL\n"
                + "\n"
                + "  SELECT\n"
                + "    ss.id AS relationId,\n"
                + "    ss.session_id AS sessionId,\n"
                + "    'REMOVED' AS action,\n"
                + "    ss.removed_date AS eventDate,\n"
                + "    ss.supervisor_consultant_id AS supervisorConsultantId,\n"
                + "    sup.username AS supervisorUsername,\n"
                + "    "
                + nameOf("sup")
                + " AS supervisorName,\n"
                + "    NULL AS previousSupervisorConsultantId,\n"
                + "    NULL AS previousSupervisorUsername,\n"
                + "    NULL AS previousSupervisorName,\n"
                + "    ss.removed_by_consultant_id AS actorConsultantId,\n"
                + "    rem.username AS actorUsername,\n"
                + "    "
                + nameOf("rem")
                + " AS actorName,\n"
                + "    ss.notes AS notes\n"
                + "  FROM session_supervisor ss\n"
                + "  JOIN session s ON s.id = ss.session_id\n"
                + "  JOIN consultant sup ON sup.consultant_id = ss.supervisor_consultant_id\n"
                + "  LEFT JOIN consultant rem ON rem.consultant_id = ss.removed_by_consultant_id\n"
                + "  WHERE ss.removed_date IS NOT NULL\n"
                + "    AND "
                + notReplaced
                + "\n"
                + "    AND "
                + tenantFilter
                + "\n) e\n"
                + "ORDER BY e.eventDate DESC\n"
                + "LIMIT :limit OFFSET :offset",
            params,
            new SupervisorLogEntryRowMapper());

    return SupervisorLogsResult.builder()
        .data(data)
        .total(total != null ? total : 0L)
        .page(safePage)
        .perPage(safePerPage)
        .build();
  }

  /** A person's shown name in the log: display name, else first and last name. */
  private static String nameOf(String alias) {
    return "CASE WHEN "
        + alias
        + ".consultant_id IS NULL THEN NULL ELSE COALESCE(NULLIF("
        + alias
        + ".display_name, ''), CONCAT("
        + alias
        + ".first_name, ' ', "
        + alias
        + ".last_name)) END";
  }

  private static class SupervisorLogEntryRowMapper implements RowMapper<SupervisorLogEntry> {
    @Override
    public SupervisorLogEntry mapRow(ResultSet rs, int rowNum) throws SQLException {
      // ADR-008 item 4 (DRAFT): the stored note may be structured JSON (reason + justification +
      // consent) or legacy free text. Decode it so the log surfaces the human justification (and
      // reason/consent) rather than raw JSON. decode() is defensive: legacy free text decodes to a
      // justification with null reason and NOT_REQUIRED consent, so old rows keep working.
      SupervisionNotes.Payload note = SupervisionNotes.decode(rs.getString("notes"));
      return SupervisorLogEntry.builder()
          .relationId(rs.getLong("relationId"))
          .sessionId(rs.getLong("sessionId"))
          .action(rs.getString("action"))
          .eventDate(rs.getObject("eventDate", LocalDateTime.class))
          .supervisorConsultantId(rs.getString("supervisorConsultantId"))
          .supervisorUsername(rs.getString("supervisorUsername"))
          .supervisorName(rs.getString("supervisorName"))
          .previousSupervisorConsultantId(rs.getString("previousSupervisorConsultantId"))
          .previousSupervisorUsername(rs.getString("previousSupervisorUsername"))
          .previousSupervisorName(rs.getString("previousSupervisorName"))
          .actorConsultantId(rs.getString("actorConsultantId"))
          .actorUsername(rs.getString("actorUsername"))
          .actorName(rs.getString("actorName"))
          .notes(note.justification)
          .reasonCode(note.reasonCode)
          .consent(note.consent)
          .build();
    }
  }

  @Data
  @Builder
  public static class SupervisorLogEntry {
    private Long relationId;
    private Long sessionId;
    private String action; // ADDED | REMOVED | REPLACED (#2494 *6)
    private LocalDateTime eventDate;
    private String supervisorConsultantId;
    private String supervisorUsername;
    private String supervisorName;
    // REPLACED only: the supervisor who was replaced.
    private String previousSupervisorConsultantId;
    private String previousSupervisorUsername;
    private String previousSupervisorName;
    private String actorConsultantId;
    private String actorUsername;
    private String actorName;
    // ADR-008: the human justification (decoded from the stored note; raw JSON is never exposed).
    private String notes;
    // ADR-008 item 4 (DRAFT): decoded structured fields (null/NOT_REQUIRED for legacy rows).
    private String reasonCode;
    private String consent;
  }

  @Data
  @Builder
  public static class SupervisorLogsResult {
    private List<SupervisorLogEntry> data;
    private long total;
    private int page;
    private int perPage;
  }
}
