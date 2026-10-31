package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.service.DeletionAuditLogsService.DeletionAuditLogsResult;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Read model over a real (H2, MariaDB mode) deletion_audit_log table. */
class DeletionAuditLogsServiceTest {

  private NamedParameterJdbcTemplate jdbc;
  private DeletionAuditLogsService service;

  @BeforeEach
  void createTable() {
    var dataSource =
        new DriverManagerDataSource(
            "jdbc:h2:mem:del-audit-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MariaDB",
            "sa",
            "");
    jdbc = new NamedParameterJdbcTemplate(dataSource);
    jdbc.getJdbcTemplate()
        .execute(
            "CREATE TABLE deletion_audit_log ("
                + " id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + " actor_id VARCHAR(64) NULL,"
                + " actor_role VARCHAR(32) NOT NULL,"
                + " target_type VARCHAR(32) NOT NULL,"
                + " target_id VARCHAR(64) NOT NULL,"
                + " tenant_id BIGINT NULL,"
                + " outcome VARCHAR(16) NOT NULL,"
                + " create_date DATETIME NOT NULL)");
    insert("admin-1", "PLATFORM_ADMIN", "CONSULTANT", "c-1", "SCHEDULED", "2026-10-01 10:00:00");
    insert(null, "SYSTEM", "CONSULTANT", "c-1", "EXECUTED", "2026-10-03 10:00:00");
    insert("admin-2", "TENANT_ADMIN", "AGENCY_ADMIN", "a-1", "EXECUTED", "2026-10-02 10:00:00");
    service = new DeletionAuditLogsService(jdbc);
  }

  @Test
  void listAuditLogs_Should_ReturnNewestFirst_WithWhoWhatWhenAndOutcome() {
    DeletionAuditLogsResult result = service.listAuditLogs(1, 20, null, null, null);

    assertThat(result.getTotal()).isEqualTo(3);
    assertThat(result.getData())
        .extracting(e -> e.getTargetId() + ":" + e.getOutcome())
        .containsExactly("c-1:EXECUTED", "a-1:EXECUTED", "c-1:SCHEDULED");
    var first = result.getData().get(0);
    assertThat(first.getActorRole()).isEqualTo("SYSTEM");
    assertThat(first.getActorId()).isNull();
    assertThat(first.getTargetType()).isEqualTo("CONSULTANT");
    assertThat(first.getCreateDate()).isNotNull();
  }

  @Test
  void listAuditLogs_Should_FilterByTargetTypeOutcomeAndTargetId_CaseInsensitively() {
    assertThat(service.listAuditLogs(1, 20, " agency_admin ", null, null).getTotal()).isEqualTo(1);
    assertThat(service.listAuditLogs(1, 20, null, "scheduled", null).getTotal()).isEqualTo(1);
    assertThat(service.listAuditLogs(1, 20, null, null, "C-1").getTotal()).isEqualTo(2);
    assertThat(service.listAuditLogs(1, 20, "  ", "", "").getTotal()).isEqualTo(3);
  }

  @Test
  void listAuditLogs_Should_TreatPercentAndUnderscoreInTheTargetIdAsText() {
    insert("admin-3", "TENANT_ADMIN", "AGENCY", "a_9", "SCHEDULED", "2026-10-04 10:00:00");

    assertThat(service.listAuditLogs(1, 20, null, null, "%").getTotal()).isZero();
    assertThat(service.listAuditLogs(1, 20, null, null, "_").getData())
        .extracting(e -> e.getTargetId())
        .containsExactly("a_9");
    assertThat(service.listAuditLogs(1, 20, null, null, "c_1").getTotal()).isZero();
  }

  @Test
  void listAuditLogs_Should_PageAndClamp() {
    var page = service.listAuditLogs(2, 2, null, null, null);
    assertThat(page.getData()).hasSize(1);
    assertThat(page.getPage()).isEqualTo(2);
    assertThat(service.listAuditLogs(-3, 9999, null, null, null).getPerPage()).isEqualTo(200);
    assertThat(service.listAuditLogs(1, 0, null, null, null).getPerPage()).isEqualTo(1);
  }

  private void insert(
      String actor, String role, String type, String target, String outcome, String at) {
    jdbc.getJdbcTemplate()
        .update(
            "INSERT INTO deletion_audit_log (actor_id, actor_role, target_type, target_id,"
                + " tenant_id, outcome, create_date) VALUES (?, ?, ?, ?, 1, ?, ?)",
            actor,
            role,
            type,
            target,
            outcome,
            at);
  }
}
