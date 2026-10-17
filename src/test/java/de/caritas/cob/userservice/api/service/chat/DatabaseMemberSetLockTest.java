package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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
