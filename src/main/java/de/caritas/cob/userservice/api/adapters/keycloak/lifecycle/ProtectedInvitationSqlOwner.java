package de.caritas.cob.userservice.api.adapters.keycloak.lifecycle;

import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** A synchronized connection alone does not prove which manager owns its commit. */
final class ProtectedInvitationSqlOwner {
  private ProtectedInvitationSqlOwner() {}

  static void require(JdbcTemplate jdbc, PlatformTransactionManager manager) {
    DataSource owner = null;
    if (manager instanceof JpaTransactionManager jpa) owner = jpa.getDataSource();
    else if (manager instanceof DataSourceTransactionManager sql) owner = sql.getDataSource();
    if (jdbc == null || owner == null || owner != jdbc.getDataSource()) {
      throw new IllegalArgumentException("Protected invitation SQL transaction owner mismatch");
    }
  }
}
