package de.caritas.cob.userservice.api.service.statistics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.identity.IdentityOtpCredential;
import de.caritas.cob.userservice.api.port.out.IdentitySecondFactor;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ORISO-UserService#1666: a statistics reader sees no numbers before 2FA is active. */
@ExtendWith(MockitoExtension.class)
class StatisticsReaderSecondFactorGateTest {

  @Mock private IdentitySecondFactor identitySecondFactor;

  private final AuthenticatedUser user = new AuthenticatedUser();
  private StatisticsReaderSecondFactorGate gate;

  @BeforeEach
  void setUp() {
    user.setUserId("reader-id");
    user.setUsername("stats.reader");
    gate = new StatisticsReaderSecondFactorGate(user, identitySecondFactor);
  }

  @Test
  void readerWithActiveOtpPasses() {
    givenReader();
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(true, null, null, null));

    assertThatCode(gate::requireActiveSecondFactor).doesNotThrowAnyException();
  }

  @Test
  void readerWithoutOtpIsRefused() {
    givenReader();
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenReturn(new IdentityOtpCredential(false, "secret", "qr", null));

    assertThatThrownBy(gate::requireActiveSecondFactor).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void readerIsRefusedWhenTheOtpStateCannotBeRead() {
    givenReader();
    when(identitySecondFactor.getOtpCredential(anyString()))
        .thenThrow(new IllegalStateException("keycloak down"));

    assertThatThrownBy(gate::requireActiveSecondFactor).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void readerIsRefusedWhenNoOtpStateComesBack() {
    givenReader();
    when(identitySecondFactor.getOtpCredential(anyString())).thenReturn(null);

    assertThatThrownBy(gate::requireActiveSecondFactor).isInstanceOf(ForbiddenException.class);
  }

  @Test
  void otherAdminsAreNotAskedForTheirOtpState() {
    user.setRoles(Set.of(UserRole.TENANT_ADMIN.getValue(), UserRole.AGENCY_ADMIN.getValue()));

    assertThatCode(gate::requireActiveSecondFactor).doesNotThrowAnyException();
    verifyNoInteractions(identitySecondFactor);
  }

  private void givenReader() {
    user.setRoles(Set.of(UserRole.STATISTICS_READER.getValue()));
    user.setTenantId(0L);
  }
}
