package de.caritas.cob.userservice.api.workflow.inactiveaccountnotification.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.InactiveAccountNotificationAuditLogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InactiveAccountNotificationClaimWriterTest {

  @Mock private InactiveAccountNotificationAuditLogRepository auditLogRepository;
  @InjectMocks private InactiveAccountNotificationClaimWriter claimWriter;

  @Test
  void markEmailDispatched_ShouldFail_WhenAuditLogIsMissing() {
    when(auditLogRepository.findById(42L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> claimWriter.markEmailDispatched(42L))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("42");

    verify(auditLogRepository, never()).saveAndFlush(any());
  }
}
