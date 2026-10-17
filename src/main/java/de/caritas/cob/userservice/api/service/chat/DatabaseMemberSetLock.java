package de.caritas.cob.userservice.api.service.chat;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * On MariaDB/MySQL a named advisory lock ({@code GET_LOCK}) held on one pooled connection, so the
 * lock holds across replicas and a dead connection releases it. On any other database (the H2 of
 * the tests) a lock inside this JVM. Either way a lock inside this JVM is taken first, so requests
 * of one replica queue without each holding a database connection while they wait.
 */
@Component
@Slf4j
public class DatabaseMemberSetLock implements MemberSetLock {

  private static final int STRIPES = 64;
  private static final String NAME_PREFIX = "oriso:subset:";

  private final DataSource dataSource;
  private final int waitSeconds;
  private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];
  private volatile Boolean advisoryLocks;

  @Autowired
  public DatabaseMemberSetLock(DataSource dataSource) {
    this(dataSource, 15);
  }

  public DatabaseMemberSetLock(DataSource dataSource, int waitSeconds) {
    this.dataSource = dataSource;
    this.waitSeconds = waitSeconds;
    for (int i = 0; i < STRIPES; i++) {
      stripes[i] = new ReentrantLock();
    }
  }

  @Override
  public Held acquire(Collection<String> memberIds) {
    var name = lockNameOf(memberIds);
    var stripe = stripes[Math.floorMod(name.hashCode(), STRIPES)];
    try {
      if (!stripe.tryLock(waitSeconds, TimeUnit.SECONDS)) {
        throw busy();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw busy();
    }
    Connection connection = null;
    try {
      if (usesAdvisoryLocks()) {
        connection = dataSource.getConnection();
        takeAdvisoryLock(connection, name);
      }
      var held = connection;
      return () -> {
        try {
          if (held != null) {
            releaseAdvisoryLock(held, name);
          }
        } finally {
          stripe.unlock();
        }
      };
    } catch (SQLException | RuntimeException failure) {
      closeQuietly(connection);
      stripe.unlock();
      if (failure instanceof CustomValidationHttpStatusException busy) {
        throw busy;
      }
      log.warn("The lock for a members-only room could not be taken: {}", failure.getMessage());
      throw busy();
    }
  }

  /** The same people give the same name, whatever the order; 64 characters at most. */
  static String lockNameOf(Collection<String> memberIds) {
    var sorted = memberIds.stream().sorted().toList();
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(String.join("\n", sorted).getBytes(StandardCharsets.UTF_8));
      return NAME_PREFIX + HexFormat.of().formatHex(digest).substring(0, 48);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private boolean usesAdvisoryLocks() throws SQLException {
    var known = advisoryLocks;
    if (known == null) {
      try (var probe = dataSource.getConnection()) {
        var product = probe.getMetaData().getDatabaseProductName().toLowerCase();
        known = product.contains("mariadb") || product.contains("mysql");
      }
      advisoryLocks = known;
    }
    return known;
  }

  private void takeAdvisoryLock(Connection connection, String name) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
      statement.setString(1, name);
      statement.setInt(2, waitSeconds);
      try (var result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) {
          throw busy();
        }
      }
    }
  }

  private void releaseAdvisoryLock(Connection connection, String name) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, name);
      statement.executeQuery().close();
    } catch (SQLException failure) {
      log.warn("The lock for a members-only room could not be released: {}", failure.getMessage());
    } finally {
      closeQuietly(connection);
    }
  }

  private static void closeQuietly(Connection connection) {
    if (connection != null) {
      try {
        connection.close();
      } catch (SQLException ignored) {
        // the pool discards a broken connection; its session locks go with it
      }
    }
  }

  private static CustomValidationHttpStatusException busy() {
    return new CustomValidationHttpStatusException(
        HttpStatusExceptionReason.INTERNAL_CHAT_BUSY, HttpStatus.SERVICE_UNAVAILABLE);
  }
}
