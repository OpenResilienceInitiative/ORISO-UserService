package de.caritas.cob.userservice.api.service.identity.observation;

import static de.caritas.cob.userservice.api.service.identity.observation.IdentityObservation.*;

import java.util.*;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Fixed raw projections include deleted rows and deliberately bypass tenant ORM filters. */
@Component
@Slf4j
public class IdentityOccupancyRawReader {
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 20;
  private final NamedParameterJdbcTemplate jdbc;

  public IdentityOccupancyRawReader(DataSource dataSource) {
    jdbc = new NamedParameterJdbcTemplate(dataSource);
  }

  public Layer read(Source source, IdentityProbe probe) {
    String table =
        switch (source) {
          case ADMIN -> "admin";
          case CONSULTANT -> "consultant";
          case ASKER -> "user";
          default -> throw new IllegalArgumentException("Not a relational source");
        };
    String id = table + "_id";
    var predicates = new ArrayList<String>();
    var parameters = new MapSqlParameterSource();
    if (probe.identityId() != null) {
      predicates.add(id + " = :id");
      parameters.addValue("id", probe.identityId());
    }
    if (!probe.usernames().isEmpty()) {
      predicates.add("LOWER(username) IN (:names)");
      parameters.addValue(
          "names", probe.usernames().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
    }
    if (probe.email() != null) {
      predicates.add("LOWER(email) = :email");
      parameters.addValue("email", probe.email());
    }
    String fields =
        source == Source.ADMIN ? "type, NULL AS delete_date" : "NULL AS type, delete_date";
    String select =
        "SELECT "
            + id
            + " AS identity_id, username, email, tenant_id, "
            + fields
            + " FROM `"
            + table
            + "` WHERE ";
    RowMapper<Fact> mapper =
        (rs, row) -> {
          var deleted = rs.getTimestamp("delete_date");
          return new Fact(
              source,
              rs.getString("identity_id"),
              rs.getString("identity_id"),
              rs.getString("username"),
              rs.getString("email"),
              rs.getObject("tenant_id", Long.class),
              rs.getString("type"),
              source == Source.ADMIN ? null : deleted != null,
              deleted == null ? null : deleted.toLocalDateTime().toString(),
              null);
        };
    var rows =
        jdbc.query(
            select + String.join(" OR ", predicates) + " ORDER BY " + id + " LIMIT 21",
            parameters,
            mapper);
    var facts = new LinkedHashMap<String, Fact>();
    boolean limited = rows.size() > 20;
    for (var fact : rows)
      if (probe.matches(fact.identityId(), fact.username(), fact.email()))
        facts.putIfAbsent(fact.rowId(), fact);
    try {
      if (!probe.usernames().isEmpty() && facts.size() <= 20) {
        boolean exhausted = false;
        Object after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
          var candidates =
              jdbc.query(
                  select
                      + "LOWER(username) LIKE 'enc.%'"
                      + (after == null ? "" : " AND " + id + " > :after")
                      + " ORDER BY "
                      + id
                      + " LIMIT "
                      + PAGE_SIZE,
                  new MapSqlParameterSource("after", after),
                  (rs, row) -> new Candidate(rs.getObject("identity_id"), mapper.mapRow(rs, row)));
          for (var candidate : candidates) {
            after = candidate.cursor();
            var fact = candidate.fact();
            if (!IdentityUsername.decode(fact.username()).resolved()) limited = true;
            if (probe.matches(fact.identityId(), fact.username(), fact.email()))
              facts.putIfAbsent(fact.rowId(), fact);
          }
          if (facts.size() > 20) {
            limited = true;
            break;
          }
          if (candidates.size() < PAGE_SIZE) {
            exhausted = true;
            break;
          }
        }
        if (!exhausted) limited = true;
      }
    } catch (org.springframework.dao.DataAccessException dependencyFailure) {
      // Retain observed facts but never certify absence after a failed candidate read.
      log.warn(
          "Identity occupancy source {} failed: {}",
          source,
          dependencyFailure.getClass().getName());
      return new Layer(
          source, Status.DEPENDENCY_FAILURE, facts.values().stream().limit(20).toList());
    }
    return new Layer(
        source,
        limited ? Status.LIMIT : Status.COMPLETE,
        facts.values().stream().limit(20).toList());
  }

  private record Candidate(Object cursor, Fact fact) {}
}
