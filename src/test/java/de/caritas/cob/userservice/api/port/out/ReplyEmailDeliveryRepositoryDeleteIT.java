package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.config.JpaAuditingConfiguration;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.Status;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly delete workflow calls these deletes outside any transaction. Without their own, they
 * failed with "No EntityManager with actual transaction available" (ORISO-Admin#1521), so this test
 * runs without the transaction {@code @DataJpaTest} would otherwise wrap around it.
 */
@DataJpaTest
@ActiveProfiles("testing")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReplyEmailDeliveryRepositoryDeleteIT {

  private static final String ASKER = UUID.randomUUID().toString();
  private static final String OTHER = UUID.randomUUID().toString();
  private static final String MATRIX_SENDER = "@" + UUID.randomUUID() + ":matrix.example";

  @Autowired private ReplyEmailDeliveryRepository repository;

  @AfterEach
  void cleanUp() {
    repository.deleteAll();
  }

  @Test
  void deleteByRecipientKindAndRecipientUserId_Should_deleteOutsideATransaction() {
    repository.save(delivery(RecipientKind.ASKER, ASKER, null));
    repository.save(delivery(RecipientKind.CONSULTANT, ASKER, null));
    var kept = repository.save(delivery(RecipientKind.ASKER, OTHER, null));

    int deleted = repository.deleteByRecipientKindAndRecipientUserId(RecipientKind.ASKER, ASKER);

    assertThat(deleted).isOne();
    assertThat(repository.findAll())
        .extracting(ReplyEmailDelivery::getRecipientUserId, ReplyEmailDelivery::getRecipientKind)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ASKER, RecipientKind.CONSULTANT),
            org.assertj.core.groups.Tuple.tuple(kept.getRecipientUserId(), RecipientKind.ASKER));
  }

  @Test
  void deleteBySourceMatrixUserId_Should_deleteOutsideATransaction() {
    repository.save(delivery(RecipientKind.ASKER, ASKER, MATRIX_SENDER));
    repository.save(delivery(RecipientKind.ASKER, OTHER, "@someone-else:matrix.example"));

    int deleted = repository.deleteBySourceMatrixUserId(MATRIX_SENDER);

    assertThat(deleted).isOne();
    assertThat(repository.findAll())
        .extracting(ReplyEmailDelivery::getSourceMatrixUserId)
        .containsExactly("@someone-else:matrix.example");
  }

  private static ReplyEmailDelivery delivery(
      RecipientKind kind, String recipient, String sourceMatrixUserId) {
    var delivery = new ReplyEmailDelivery();
    delivery.setRecipientKind(kind);
    delivery.setRecipientUserId(recipient);
    delivery.setSourceMatrixUserId(sourceMatrixUserId);
    delivery.setEventKey(UUID.randomUUID().toString());
    delivery.setCorrelationId(UUID.randomUUID().toString());
    delivery.setTenantId(990_301L);
    delivery.setSessionId(1L);
    delivery.setStatus(Status.SENT);
    delivery.setCreatedAt(LocalDateTime.of(2026, 11, 13, 3, 0));
    delivery.setNextAttemptAt(LocalDateTime.of(2026, 11, 13, 3, 0));
    return delivery;
  }
}
