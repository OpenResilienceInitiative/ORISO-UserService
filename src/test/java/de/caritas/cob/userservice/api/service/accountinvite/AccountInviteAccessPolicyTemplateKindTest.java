package de.caritas.cob.userservice.api.service.accountinvite;

import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.COUNSELLOR_INVITE;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.DPA_FORWARD;
import static de.caritas.cob.userservice.api.service.accountinvite.InviteEmailTemplateKind.TENANT_INVITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.config.auth.UserRole;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Which template kinds each admin may create, change, list and use: a Beratungsstellen admin only
 * counsellor invites, a Träger admin counsellor and Träger invites, the platform admin every kind.
 */
@ExtendWith(MockitoExtension.class)
class AccountInviteAccessPolicyTemplateKindTest {

  private static final long OWN_TENANT = 1L;
  private static final long FOREIGN_TENANT = 2L;

  @Mock private AdminRepository adminRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;
  @Mock private AgencyService agencyService;

  private final AuthenticatedUser caller = new AuthenticatedUser();
  private AccountInviteAccessPolicy policy;

  @BeforeEach
  void setUp() {
    var adminScope =
        new AdminScope(
            caller,
            adminRepository,
            adminAgencyRepository,
            consultantRepository,
            consultantAgencyRepository,
            agencyService,
            userRepository,
            sessionRepository,
            userAgencyRepository);
    ReflectionTestUtils.setField(adminScope, "multitenancyEnabled", true);
    policy = new AccountInviteAccessPolicy(caller, adminScope);
    caller.setUserId("caller");
  }

  static Stream<Arguments> callers() {
    return Stream.of(
        arguments(
            "platform admin",
            0L,
            List.of(UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN),
            EnumSet.allOf(InviteEmailTemplateKind.class)),
        arguments(
            "Träger admin",
            OWN_TENANT,
            List.of(UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN),
            EnumSet.of(COUNSELLOR_INVITE, TENANT_INVITE)),
        arguments(
            "Beratungsstellen admin",
            OWN_TENANT,
            List.of(UserRole.RESTRICTED_AGENCY_ADMIN, UserRole.USER_ADMIN),
            EnumSet.of(COUNSELLOR_INVITE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("callers")
  void templateKindsInReach_Should_FollowTheAdminRole(
      String callerKind, Long tenantId, List<UserRole> roles, Set<InviteEmailTemplateKind> kinds) {
    actAs(tenantId, roles.toArray(UserRole[]::new));

    assertThat(policy.templateKindsInReach()).containsExactlyInAnyOrderElementsOf(kinds);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("callers")
  void everyTemplateDecision_Should_ApplyTheKindRule(
      String callerKind, Long tenantId, List<UserRole> roles, Set<InviteEmailTemplateKind> kinds) {
    actAs(tenantId, roles.toArray(UserRole[]::new));
    Long ownTemplate = tenantId == 0L ? null : tenantId;

    for (InviteEmailTemplateKind kind : InviteEmailTemplateKind.values()) {
      boolean allowed = kinds.contains(kind);
      assertThat(policy.mayUseTemplateKind(kind)).as("may use %s", kind).isEqualTo(allowed);
      assertThat(policy.canUseTemplate(null, kind)).as("use platform %s", kind).isEqualTo(allowed);
      assertThat(policy.canChangeTemplate(ownTemplate, kind))
          .as("change own %s", kind)
          .isEqualTo(allowed);
      if (allowed) {
        assertThatCode(() -> policy.authorizeTemplateKind(kind)).doesNotThrowAnyException();
        assertThatCode(() -> policy.authorizeTemplateUse(null, kind)).doesNotThrowAnyException();
        assertThatCode(() -> policy.authorizeTemplateUpdate(ownTemplate, kind))
            .doesNotThrowAnyException();
      } else {
        assertThatThrownBy(() -> policy.authorizeTemplateKind(kind))
            .as("create %s", kind)
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> policy.authorizeTemplateUse(null, kind))
            .as("use %s", kind)
            .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> policy.authorizeTemplateUpdate(ownTemplate, kind))
            .as("change %s", kind)
            .isInstanceOf(ForbiddenException.class);
      }
    }
  }

  @Test
  void kindRule_Should_NotWidenTheOwnerRule_When_KindIsAllowed() {
    actAs(OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(policy.canUseTemplate(FOREIGN_TENANT, COUNSELLOR_INVITE)).isFalse();
    assertThat(policy.canChangeTemplate(FOREIGN_TENANT, TENANT_INVITE)).isFalse();
    assertThat(policy.canChangeTemplate(null, COUNSELLOR_INVITE)).isFalse();
    assertThatThrownBy(() -> policy.authorizeTemplateUse(FOREIGN_TENANT, COUNSELLOR_INVITE))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> policy.authorizeTemplateUpdate(null, COUNSELLOR_INVITE))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void kindRule_Should_RefuseDpaForward_When_TraegerAdminOwnsTheRow() {
    // A DPA_FORWARD row a Träger saved before the kind rule existed stays theirs, but out of use.
    actAs(OWN_TENANT, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(policy.canUseTemplate(OWN_TENANT, DPA_FORWARD)).isFalse();
    assertThat(policy.canChangeTemplate(OWN_TENANT, DPA_FORWARD)).isFalse();
  }

  @Test
  void kindRule_Should_RefuseUnknownKind() {
    actAs(0L, UserRole.TENANT_ADMIN, UserRole.AGENCY_ADMIN, UserRole.USER_ADMIN);

    assertThat(policy.mayUseTemplateKind(null)).isFalse();
  }

  private void actAs(Long tenantId, UserRole... roles) {
    caller.setTenantId(tenantId);
    caller.setRoles(Arrays.stream(roles).map(UserRole::getValue).collect(Collectors.toSet()));
  }
}
