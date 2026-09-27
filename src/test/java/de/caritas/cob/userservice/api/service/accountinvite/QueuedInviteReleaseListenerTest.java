package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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
    technicalUser.setUsername("technical");
    technicalUser.setPassword("secret");
    when(identityClientConfig.getTechnicalUser()).thenReturn(technicalUser);
    when(identityAuthentication.login("technical", "secret"))
        .thenReturn(new IdentityLogin("token", 60, 60, "refresh"));
    var ambientDuringRelease = new AtomicReference<Object>("not called");
    when(unitQueue.release(InviteUnitType.AGENCY, 7L, 3L))
        .thenAnswer(
            invocation -> {
              // Local writes and SMTP run without the service token.
              ambientDuringRelease.set(TechnicalAccessTokenContext.get());
              return List.of();
            });

    listener.onUnitCreated(new InviteUnitCreatedEvent(InviteUnitType.AGENCY, 7L, 3L));

    assertThat(ambientDuringRelease.get()).isEqualTo(java.util.Optional.empty());
    assertThat(TechnicalAccessTokenContext.get()).isEmpty();
  }
}
