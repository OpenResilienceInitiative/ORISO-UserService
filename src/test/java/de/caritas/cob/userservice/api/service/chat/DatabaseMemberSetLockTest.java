package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** The in-JVM half of the member-set lock (the database half runs in ChatMembersOnlyMariaDbIT). */
class DatabaseMemberSetLockTest {

  @Test
  void theSamePeopleGiveTheSameLockWhateverTheOrder() {
    assertThat(DatabaseMemberSetLock.lockNameOf(List.of("b", "a", "c")))
        .isEqualTo(DatabaseMemberSetLock.lockNameOf(List.of("c", "b", "a")))
        .isNotEqualTo(DatabaseMemberSetLock.lockNameOf(List.of("a", "b")))
        .hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void twoRequestsForTheSamePeopleRunOneAfterTheOther() throws Exception {
    var lock = new DatabaseMemberSetLock(h2(), 5);
    var inside = new AtomicInteger();
    var overlapped = new AtomicInteger();
    var pool = Executors.newFixedThreadPool(2);
    var go = new CountDownLatch(1);
    try {
      var work =
          (java.util.concurrent.Callable<Void>)
              () -> {
                go.await();
                try (var ignored = lock.acquire(List.of("a", "b"))) {
                  if (inside.incrementAndGet() > 1) {
                    overlapped.incrementAndGet();
                  }
                  Thread.sleep(150);
                  inside.decrementAndGet();
                }
                return null;
              };
      var one = pool.submit(work);
      var other = pool.submit(work);
      go.countDown();
      one.get(10, TimeUnit.SECONDS);
      other.get(10, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    assertThat(overlapped).hasValue(0);
  }

  @Test
  void aRequestThatCannotGetTheLockInTimeIsAnsweredBusy() throws Exception {
    var lock = new DatabaseMemberSetLock(h2(), 1);
    var holder = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor();
    try {
      pool.submit(
          () -> {
            try (var ignored = lock.acquire(List.of("a", "b"))) {
              holder.countDown();
              release.await();
            }
            return null;
          });
      assertThat(holder.await(5, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> lock.acquire(List.of("b", "a")))
          .isInstanceOfSatisfying(
              CustomValidationHttpStatusException.class,
              busy -> {
                assertThat(busy.getHttpStatus().value()).isEqualTo(503);
                assertThat(busy.getCustomHttpHeaders().getFirst("X-Reason"))
                    .isEqualTo("INTERNAL_CHAT_BUSY");
              });
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void aLockThatCannotBeReleasedEndsTheDatabaseSessionSoItCannotStayHeldOnAPooledConnection()
      throws Exception {
    var metadata = mock(DatabaseMetaData.class);
    when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
    var taken = mock(ResultSet.class);
    when(taken.next()).thenReturn(true);
    when(taken.getInt(1)).thenReturn(1);
    var getLock = mock(PreparedStatement.class);
    when(getLock.executeQuery()).thenReturn(taken);
    var connection = mock(Connection.class);
    when(connection.getMetaData()).thenReturn(metadata);
    when(connection.prepareStatement("SELECT GET_LOCK(?, ?)")).thenReturn(getLock);
    when(connection.prepareStatement("SELECT RELEASE_LOCK(?)"))
        .thenThrow(new SQLException("statement cancelled"));
    var dataSource = mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);

    var lock = new DatabaseMemberSetLock(dataSource, 1);
    lock.acquire(List.of("a", "b")).close();

    // A session that still holds the lock would keep every other replica waiting until the pool
    // retires the connection; ending the session releases the lock on the server.
    verify(connection).abort(any());
  }

  @Test
  void onlyAFewHoldersPinAPooledConnectionAtOnceAndTheNextOneIsAnsweredBusy() throws Exception {
    var lock = new DatabaseMemberSetLock(mariaDb(), 1, 2);
    var sets = membersOnDifferentStripes(3);

    var first = lock.acquire(sets.get(0));
    var second = lock.acquire(sets.get(1));

    // Two connections are pinned, the pool must keep room for everything else: no third one.
    assertThatThrownBy(() -> lock.acquire(sets.get(2)))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            busy -> {
              assertThat(busy.getHttpStatus().value()).isEqualTo(503);
              assertThat(busy.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo("INTERNAL_CHAT_BUSY");
            });

    first.close();
    lock.acquire(sets.get(2)).close();
    second.close();
  }

  @Test
  void aHolderThatFailedToGetItsConnectionGivesItsPlaceBack() throws Exception {
    var healthy = mariaDb().getConnection();
    var dataSource = mock(DataSource.class);
    // The first connection is the product probe, the second one is refused, then all is well.
    when(dataSource.getConnection())
        .thenReturn(healthy)
        .thenThrow(new SQLException("pool exhausted"))
        .thenReturn(healthy);
    var lock = new DatabaseMemberSetLock(dataSource, 1, 1);
    var sets = membersOnDifferentStripes(2);

    assertThatThrownBy(() -> lock.acquire(sets.get(0)))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    // The one place was given back, otherwise this would be busy for good.
    lock.acquire(sets.get(1)).close();
  }

  /** Member sets whose locks land on different in-JVM stripes, so only the bound can stop them. */
  private static List<List<String>> membersOnDifferentStripes(int count) {
    var sets = new java.util.ArrayList<List<String>>();
    var stripes = new java.util.HashSet<Integer>();
    for (int i = 0; sets.size() < count; i++) {
      var members = List.of("member-" + i, "colleague-" + i);
      var stripe = Math.floorMod(DatabaseMemberSetLock.lockNameOf(members).hashCode(), 64);
      if (stripes.add(stripe)) {
        sets.add(members);
      }
    }
    return sets;
  }

  private static DataSource mariaDb() {
    try {
      var metadata = mock(DatabaseMetaData.class);
      when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
      var taken = mock(ResultSet.class);
      when(taken.next()).thenReturn(true);
      when(taken.getInt(1)).thenReturn(1);
      var statement = mock(PreparedStatement.class);
      when(statement.executeQuery()).thenReturn(taken);
      var connection = mock(Connection.class);
      when(connection.getMetaData()).thenReturn(metadata);
      when(connection.prepareStatement(any(String.class))).thenReturn(statement);
      var dataSource = mock(DataSource.class);
      when(dataSource.getConnection()).thenReturn(connection);
      return dataSource;
    } catch (java.sql.SQLException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static DataSource h2() {
    try {
      var metadata = mock(DatabaseMetaData.class);
      when(metadata.getDatabaseProductName()).thenReturn("H2");
      var connection = mock(Connection.class);
      when(connection.getMetaData()).thenReturn(metadata);
      var dataSource = mock(DataSource.class);
      when(dataSource.getConnection()).thenReturn(connection);
      return dataSource;
    } catch (java.sql.SQLException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
