package de.caritas.cob.userservice.api.service.matrixrtc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class MatrixRtcTransactionBoundaryTest {

  @Test
  void onlyDatabaseContextResolutionOwnsTheReadTransaction() throws Exception {
    var serviceResolve =
        MatrixRtcCallPolicyService.class.getMethod("resolve", String.class, String.class);
    assertThat(serviceResolve.getAnnotation(Transactional.class)).isNull();

    var resolverResolve =
        MatrixRtcPolicyContextResolver.class.getDeclaredMethod("resolve", String.class);
    var transaction = resolverResolve.getAnnotation(Transactional.class);
    assertThat(transaction).isNotNull();
    assertThat(transaction.readOnly()).isTrue();
  }
}
