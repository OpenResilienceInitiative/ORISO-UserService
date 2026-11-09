package de.caritas.cob.userservice.api.port.out;

import java.sql.Connection;
import java.sql.SQLException;

/** MariaDB advisory locks belong to the JDBC session, including across commit or rollback. */
public final class MariaDbNamedLock {

  private MariaDbNamedLock() {}

  public static boolean tryAcquire(Connection connection, String name, int waitSeconds)
      throws SQLException {
    try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
      statement.setString(1, name);
      statement.setInt(2, waitSeconds);
      try (var result = statement.executeQuery()) {
        return result.next() && result.getInt(1) == 1;
      }
    }
  }

  public static void release(Connection connection, String name) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, name);
      statement.executeQuery().close();
    }
  }

  public static void abortQuietly(Connection connection) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException | RuntimeException ignored) {
      // The connection is unusable either way; its owner will close it.
    }
  }
}
