package de.caritas.cob.userservice.api.service.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executes the disposable startup database setting through the actual custody snapshot. */
class StartupFixtureSqlSnapshotTest {
  @Test
  void configuredStartupDatabaseKeepsTheShippedLowercaseCustodyColumns() throws Exception {
    String driver = Files.readString(Path.of("scripts/ci/application-startup-native-pair.py"));
    var setting = Pattern.compile("'SPRING_DATASOURCE_URL': '([^']+)'").matcher(driver);
    assertTrue(setting.find(), "Startup must declare its actual disposable datasource");
    String url = setting.group(1);
    assertFalse(setting.find(), "Only one datasource declaration may control startup");
    assertEquals(
        "jdbc:h2:mem:userservice-startup-${random.uuid};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=USER,VALUE,DAY",
        url);
    var row = snapshot(url.replace("${random.uuid}", UUID.randomUUID().toString()));
    assertTrue(row.path("tenant_id").isIntegralNumber());
    assertEquals(22L, row.path("tenant_id").longValue());
    assertEquals("fixture", row.path("admin_id").textValue());
    assertFalse(row.has("TENANT_ID"));
  }

  @Test
  void unconfiguredH2LabelsReproduceTheMissingPostconditionField() throws Exception {
    var row =
        snapshot("jdbc:h2:mem:uppercase-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MariaDB");
    assertTrue(row.path("tenant_id").isMissingNode());
    assertFalse(row.path("tenant_id").isIntegralNumber());
    assertEquals(22L, row.path("TENANT_ID").longValue());
  }

  private com.fasterxml.jackson.databind.JsonNode snapshot(String url) throws Exception {
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
    for (String table :
        List.of(
            "admin",
            "consultant",
            "language",
            "consultant_topic",
            "consultant_agency",
            "admin_agency")) {
      String owner = table.startsWith("admin") ? "admin_id" : "consultant_id";
      jdbc.execute("create table " + table + " (" + owner + " varchar(36), tenant_id bigint)");
    }
    jdbc.update("insert into admin(admin_id,tenant_id) values('fixture',22)");
    var json = new ObjectMapper();
    String saved =
        ConsultantProfileSqlSnapshot.capture(
            jdbc,
            "fixture",
            false,
            value -> {
              try {
                return json.writeValueAsString(value);
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    return json.readTree(json.readTree(saved).path("admin").get(0).textValue());
  }
}
