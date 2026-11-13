package de.caritas.cob.userservice.api.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.ReplyEmailDelivery.RecipientKind;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/**
 * The nightly delete workflow calls these methods outside a transaction. A derived delete without
 * its own transaction throws "No EntityManager with actual transaction available", which left
 * advice seekers and counsellors in HARD_DELETE_PARTIAL_FAILURE every night (ORISO-Admin #1521).
 */
class ReplyEmailDeliveryRepositoryDeleteContractTest {

  @Test
  void deleteByRecipientKindAndRecipientUserId_Should_runAsOwnModifyingTransaction()
      throws NoSuchMethodException {
    assertRunsAsOwnBulkDelete(
        ReplyEmailDeliveryRepository.class.getMethod(
            "deleteByRecipientKindAndRecipientUserId", RecipientKind.class, String.class));
  }

  @Test
  void deleteBySourceMatrixUserId_Should_runAsOwnModifyingTransaction()
      throws NoSuchMethodException {
    assertRunsAsOwnBulkDelete(
        ReplyEmailDeliveryRepository.class.getMethod("deleteBySourceMatrixUserId", String.class));
  }

  private static void assertRunsAsOwnBulkDelete(Method method) {
    var transactional = method.getAnnotation(Transactional.class);
    assertThat(transactional).isNotNull();
    assertThat(transactional.readOnly()).isFalse();
    assertThat(method.getAnnotation(Modifying.class)).isNotNull();
    assertThat(method.getAnnotation(Query.class).value()).startsWithIgnoringCase("delete");
  }
}
