package de.caritas.cob.userservice.api.service.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.port.out.ReplyEmailDeliveryRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(showSql = false)
@TestPropertySource(properties = "spring.profiles.active=testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReplyEmailDeliveryWriter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReplyEmailDeliveryQueueIT {
  @Autowired ReplyEmailDeliveryWriter writer;
  @Autowired ReplyEmailDeliveryRepository repository;
  @Autowired javax.sql.DataSource dataSource;

  @Test
  void longestDueMailIsNotDisplacedByRecentlyRetriedOlderClaims() {
    var ids = new java.util.ArrayList<Long>();
    var jdbc = new JdbcTemplate(dataSource);
    var now = LocalDateTime.now();
    try {
      for (int i = 0; i < 250; i++) {
        long id = reserve("retry-" + i);
        ids.add(id);
        jdbc.update(
            "UPDATE reply_email_delivery SET created_at=?,next_attempt_at=? WHERE id=?",
            now.minusDays(7),
            now.minusMinutes(1),
            id);
      }
      long ready = reserve("ready-now");
      ids.add(ready);
      jdbc.update(
          "UPDATE reply_email_delivery SET created_at=?,next_attempt_at=? WHERE id=?",
          now.minusMinutes(2),
          now.minusMinutes(2),
          ready);

      var batch = writer.pendingIds();
      assertThat(batch).hasSize(100).startsWith(ready);
      assertThat(writer.claim(ready)).isPresent();
      assertThat(writer.claim(ready)).isEmpty();
      writer.finish(ready, ReplyEmailDelivery.Status.SENT);
      assertThat(writer.pendingIds()).doesNotContain(ready);
      assertThat(repository.findById(ready).orElseThrow().getStatus())
          .isEqualTo(ReplyEmailDelivery.Status.SENT);
    } finally {
      repository.deleteAllByIdInBatch(ids);
    }
  }

  @Test
  void oneTenantsBacklogOfDueMailsCannotStarveAnotherTenant() {
    var ids = new java.util.ArrayList<Long>();
    var jdbc = new JdbcTemplate(dataSource);
    var now = LocalDateTime.now();
    try {
      for (int i = 0; i < 150; i++) {
        long id = reserve("stuck-" + i, 7L);
        ids.add(id);
        jdbc.update(
            "UPDATE reply_email_delivery SET created_at=?,next_attempt_at=? WHERE id=?",
            now.minusDays(1),
            now.minusMinutes(30),
            id);
      }
      long healthy = reserve("healthy-tenant", 8L);
      ids.add(healthy);

      var batch = writer.pendingIds();

      assertThat(batch).hasSize(100).contains(healthy);
    } finally {
      repository.deleteAllByIdInBatch(ids);
    }
  }

  private long reserve(String key) {
    return reserve(key, 7L);
  }

  private long reserve(String key, long tenantId) {
    return writer.reserve(
        ReplyEmailDelivery.RecipientKind.ASKER,
        "queue-test",
        "@sender:synthetic.oriso.test",
        "!queue:synthetic.oriso.test",
        key,
        tenantId,
        42L);
  }
}
