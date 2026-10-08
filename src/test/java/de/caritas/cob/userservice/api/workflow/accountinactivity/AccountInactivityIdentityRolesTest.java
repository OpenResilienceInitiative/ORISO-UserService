package de.caritas.cob.userservice.api.workflow.accountinactivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccountInactivityIdentityRolesTest {
  @Test
  void registryTaskAndReceivingOnlyActorsStayMachineIdentitiesWithoutHumanEnrollment() {
    assertThat(
            AccountInactivityIdentityRoles.isPureTechnical(
                Set.of("notification-dispatch", "notifications-technical")))
        .isTrue();
    assertThat(AccountInactivityIdentityRoles.isPureTechnical(Set.of("consultant-import")))
        .isTrue();
    assertThat(AccountInactivityIdentityRoles.isPureTechnical(Set.of("smtp-sync"))).isTrue();
  }

  @Test
  void mixedHumanAndUnknownPrivilegesRemainHumanForInactivityEnrollment() {
    assertThat(
            AccountInactivityIdentityRoles.isPureTechnical(
                Set.of("consultant-import", "consultant")))
        .isFalse();
    assertThat(AccountInactivityIdentityRoles.isPureTechnical(Set.of("technical", "tenant-admin")))
        .isFalse();
    assertThat(
            AccountInactivityIdentityRoles.isPureTechnical(
                Set.of("technical", "unknown-privilege")))
        .isFalse();
    assertThat(AccountInactivityIdentityRoles.isPureTechnical(Set.of("default-roles-test")))
        .isFalse();
  }
}
