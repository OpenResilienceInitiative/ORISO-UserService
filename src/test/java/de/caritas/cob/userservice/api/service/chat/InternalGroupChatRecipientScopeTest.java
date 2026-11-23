package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The recipient scope (ORISO-TenantService#321) as a rule: who may a counsellor pick. */
class InternalGroupChatRecipientScopeTest {

  private static final Consultant CREATOR = consultant("creator", 1L, 100L);

  @Test
  void agencyScopeAdmitsOnlyColleaguesOfTheSameCounsellingCentre() {
    var scope = InternalGroupChatRecipientScope.AGENCY;

    assertThat(scope.permits(CREATOR, consultant("same-centre", 1L, 100L), null)).isTrue();
    assertThat(scope.permits(CREATOR, consultant("other-centre", 1L, 200L), null)).isFalse();
    assertThat(scope.permits(CREATOR, consultant("other-traeger", 2L, 300L), null)).isFalse();
  }

  @Test
  void agencyScopeWithANamedCentreNeedsBothSidesInThatCentre() {
    var scope = InternalGroupChatRecipientScope.AGENCY;
    var creatorInTwo = consultant("creator-two", 1L, 100L, 200L);

    assertThat(scope.permits(creatorInTwo, consultant("in-200", 1L, 200L), 200L)).isTrue();
    assertThat(scope.permits(creatorInTwo, consultant("in-100", 1L, 100L), 200L)).isFalse();
    assertThat(scope.permits(CREATOR, consultant("in-200", 1L, 200L), 200L)).isFalse();
  }

  @Test
  void tenantScopeAdmitsEveryoneOfTheSameTraegerAcrossCentresButNobodyElse() {
    var scope = InternalGroupChatRecipientScope.TENANT;

    assertThat(scope.permits(CREATOR, consultant("other-centre", 1L, 200L), null)).isTrue();
    assertThat(scope.permits(CREATOR, consultant("other-traeger", 2L, 100L), null)).isFalse();
    assertThat(scope.permits(CREATOR, consultant("tenantless", null, 100L), null)).isFalse();
  }

  @Test
  void withoutMultitenancyTenantlessColleaguesOfTheSameCentreAreAdmitted() {
    var tenantlessCreator = consultant("creator", null, 100L);

    for (var scope : InternalGroupChatRecipientScope.values()) {
      assertThat(
              scope.permits(tenantlessCreator, consultant("same-centre", null, 100L), 100L, false))
          .as("%s admits a tenantless colleague of the same centre", scope)
          .isTrue();
    }
  }

  @Test
  void withoutMultitenancyTheCentreStillBoundsTheAgencyScope() {
    var tenantlessCreator = consultant("creator", null, 100L);

    assertThat(
            InternalGroupChatRecipientScope.AGENCY.permits(
                tenantlessCreator, consultant("other-centre", null, 200L), null, false))
        .isFalse();
    assertThat(
            InternalGroupChatRecipientScope.AGENCY.permits(
                tenantlessCreator, consultant("same-centre", null, 100L), 200L, false))
        .isFalse();
  }

  @Test
  void withoutMultitenancyATenantlessCallerStillDoesNotReachATenantScopedColleague() {
    var tenantlessCreator = consultant("creator", null, 100L);

    assertThat(
            InternalGroupChatRecipientScope.AGENCY.permits(
                tenantlessCreator, consultant("scoped", 7L, 100L), null, false))
        .isFalse();
  }

  @Test
  void withMultitenancyATenantlessCallerReachesNobodyBelowPlatform() {
    var tenantlessCreator = consultant("creator", null, 100L);

    for (var scope :
        new InternalGroupChatRecipientScope[] {
          InternalGroupChatRecipientScope.AGENCY, InternalGroupChatRecipientScope.TENANT
        }) {
      assertThat(scope.permits(tenantlessCreator, consultant("tenantless", null, 100L), 100L, true))
          .as("%s with multitenancy: tenantless colleague", scope)
          .isFalse();
      assertThat(scope.permits(tenantlessCreator, consultant("scoped", 7L, 100L), 100L, true))
          .as("%s with multitenancy: tenant-scoped colleague", scope)
          .isFalse();
    }
  }

  @Test
  void platformScopeAdmitsColleaguesOfAnyTraeger() {
    var scope = InternalGroupChatRecipientScope.PLATFORM;

    assertThat(scope.permits(CREATOR, consultant("other-traeger", 2L, 300L), null)).isTrue();
  }

  @Test
  void aSettingThatIsMissingOrUnreadableMeansTheMostRestrictiveScope() {
    assertThat(InternalGroupChatRecipientScope.fromPolicies(null))
        .isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    assertThat(InternalGroupChatRecipientScope.fromPolicies(Map.of("tenantId", 7)))
        .isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    assertThat(InternalGroupChatRecipientScope.fromPolicies(policies("EVERYONE")))
        .isEqualTo(InternalGroupChatRecipientScope.AGENCY);
    assertThat(InternalGroupChatRecipientScope.fromPolicies(Map.of("internalGroupChat", "TENANT")))
        .isEqualTo(InternalGroupChatRecipientScope.AGENCY);
  }

  @Test
  void theResolvedValueOfTheTenantServiceIsRead() {
    assertThat(InternalGroupChatRecipientScope.fromPolicies(policies("PLATFORM")))
        .isEqualTo(InternalGroupChatRecipientScope.PLATFORM);
    assertThat(InternalGroupChatRecipientScope.fromPolicies(policies("TENANT")))
        .isEqualTo(InternalGroupChatRecipientScope.TENANT);
    assertThat(InternalGroupChatRecipientScope.fromPolicies(policies("AGENCY")))
        .isEqualTo(InternalGroupChatRecipientScope.AGENCY);
  }

  private static Map<String, Object> policies(String value) {
    return Map.of(
        "tenantId",
        7,
        "internalGroupChat",
        Map.of("recipientScope", Map.of("value", value, "mode", "ENFORCED", "inherited", true)));
  }

  private static Consultant consultant(String id, Long tenantId, Long... agencyIds) {
    var consultant =
        Consultant.builder()
            .id(id)
            .username(id)
            .firstName(id)
            .lastName("Counsellor")
            .email(id + "@example.org")
            .tenantId(tenantId)
            .build();
    var agencies = new java.util.HashSet<ConsultantAgency>();
    for (Long agencyId : agencyIds) {
      agencies.add(
          ConsultantAgency.builder()
              .id(agencyId)
              .consultant(consultant)
              .agencyId(agencyId)
              .build());
    }
    consultant.setConsultantAgencies(Set.copyOf(agencies));
    return consultant;
  }
}
