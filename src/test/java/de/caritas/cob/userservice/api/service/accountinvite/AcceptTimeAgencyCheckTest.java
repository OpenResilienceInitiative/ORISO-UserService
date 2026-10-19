package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.port.out.IdentityAuthentication;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AcceptTimeAgencyCheckTest {

  private static final long AGENCY = 275L;
  private static final long TENANT = 79L;

  private final AgencyFacts agencyFacts = mock(AgencyFacts.class);
  private final AcceptTimeAgencyCheck check =
      new AcceptTimeAgencyCheck(
          agencyFacts, mock(IdentityAuthentication.class), mock(IdentityClientConfig.class));

  @Test
  void requireLiveAgency_Should_Refuse_When_MultiTenantAndTheAgencyHasNoTenant() {
    ReflectionTestUtils.setField(check, "multiTenancyEnabled", true);
    givenAgencyOfTenant(null);

    assertThatThrownBy(() -> check.requireLiveAgency(invite(), "service-token"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void requireLiveAgency_Should_Accept_When_MultiTenantAndTheAgencyIsInTheInvitesTenant() {
    ReflectionTestUtils.setField(check, "multiTenancyEnabled", true);
    givenAgencyOfTenant(TENANT);

    assertThatCode(() -> check.requireLiveAgency(invite(), "service-token"))
        .doesNotThrowAnyException();
  }

  @Test
  void requireLiveAgency_Should_Accept_When_SingleTenantAndTheAgencyHasNoTenant() {
    givenAgencyOfTenant(null);

    assertThatCode(() -> check.requireLiveAgency(invite(), "service-token"))
        .doesNotThrowAnyException();
  }

  private void givenAgencyOfTenant(Long tenantId) {
    when(agencyFacts.find(AGENCY))
        .thenReturn(Optional.of(new AgencyFacts.Agency(AGENCY, tenantId, false, List.of())));
  }

  private static AccountInvite invite() {
    return AccountInvite.builder().agencyId(AGENCY).tenantId(TENANT).build();
  }
}
