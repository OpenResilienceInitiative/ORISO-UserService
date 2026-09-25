package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Template decisions need the caller's kind and Träger only, never their agencies. */
@ExtendWith(MockitoExtension.class)
class AccountInviteAccessPolicyTemplateScopeTest {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AgencyService agencyService;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private AccountInviteAccessPolicy policy;

  @BeforeEach
  void setUp() {
    policy = new AccountInviteAccessPolicy(caller, adminAgencyRepository, agencyService);
    caller.setUserId("caller");
  }

  @Test
  void templateDecisions_Should_NotLoadAgencies_When_AgencyAdmin() {
    actAs(OWN_TENANT, UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(policy.templateOwnerTenantId()).isEqualTo(OWN_TENANT);
    assertThat(policy.seesEveryTemplate()).isFalse();
    assertThat(policy.canUseTemplate(OWN_TENANT)).isTrue();
    assertThat(policy.canUseTemplate(null)).isTrue();
    assertThat(policy.canUseTemplate(FOREIGN_TENANT)).isFalse();
    assertThat(policy.canChangeTemplate(OWN_TENANT)).isTrue();
    assertThat(policy.canChangeTemplate(null)).isFalse();
    assertThat(policy.canChangeTemplate(FOREIGN_TENANT)).isFalse();

    verifyNoInteractions(adminAgencyRepository, agencyService);
  }

  @Test
  void templateDecisions_Should_KeepTheKindRules_When_TraegerOrPlatformAdmin() {
    actAs(OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    assertThat(policy.seesEveryTemplate()).isFalse();
    assertThat(policy.canChangeTemplate(FOREIGN_TENANT)).isFalse();

    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);
    assertThat(policy.seesEveryTemplate()).isTrue();
    assertThat(policy.templateOwnerTenantId()).isNull();
    assertThat(policy.canChangeTemplate(null)).isTrue();
    assertThat(policy.canUseTemplate(FOREIGN_TENANT)).isTrue();
  }

  private void actAs(Long tenantId, UserRole... roles) {
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
  }
}
