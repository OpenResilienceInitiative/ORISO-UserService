package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.TaskPurpose;
import de.caritas.cob.userservice.api.config.auth.TechnicalUserConfig;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.IdentityLogin;
import de.caritas.cob.userservice.api.service.httpheader.TechnicalAccessTokenContext;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QueuedInviteReleaseListenerTest {

  @InjectMocks private QueuedInviteReleaseListener listener;

  @Mock private UnitQueue unitQueue;
  @Mock private IdentityAuthentication identityAuthentication;
  @Mock private IdentityClientConfig identityClientConfig;

  @AfterEach
  void clearContexts() {
    TechnicalAccessTokenContext.clear();
    TenantContext.clear();
  }

  @Test
  void onUnitCreated_Should_ReleaseWithoutAnAmbientServiceToken() {
    var technicalUser = new TechnicalUserConfig();
    technicalUser.setClientId("technical");
    technicalUser.setClientSecret("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.loginService("technical", "secret"))
        .thenReturn(new IdentityLogin("token", 60, 60, "refresh"));
    var event = new InviteUnitCreatedEvent(InviteUnitType.AGENCY, 7L, 3L);
    var ambientDuringRelease = new AtomicReference<Object>("not called");
    when(unitQueue.release(event))
        .thenAnswer(
            invocation -> {
              // Local writes and SMTP run without the service token.
              ambientDuringRelease.set(TechnicalAccessTokenContext.get());
              assertThat(TechnicalAccessTokenContext.offered()).contains("token");
              return List.of();
            });

    listener.onUnitCreated(event);

    assertThat(ambientDuringRelease.get()).isEqualTo(java.util.Optional.empty());
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }

  @Test
  void onTenantCreated_Should_PassTheOriginalProofUnderTheScopedTaskToken() {
    var event = new InviteUnitCreatedEvent(InviteUnitType.TENANT, 9L, 9L, "synthetic-proof");
    when(identityClientConfig.isFiveActorMode()).thenReturn(true);
    when(identityClientConfig.taskAccessToken(TaskPurpose.INVITE_RESERVATIONS))
        .thenReturn("scoped-token");
    when(unitQueue.release(event))
        .thenAnswer(
            invocation -> {
              assertThat(TechnicalAccessTokenContext.offered()).contains("scoped-token");
              assertThat(TechnicalAccessTokenContext.get()).isEmpty();
              return List.of();
            });

    listener.onUnitCreated(event);

    verify(unitQueue).release(event);
    assertThat(TechnicalAccessTokenContext.offered()).isEmpty();
    assertThat(event.toString()).doesNotContain(event.reservationProof());
  }
}
