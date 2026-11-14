package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionBlockersDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Target;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class TenantDeletionBlockerServiceTest {

  private static final long TENANT_ID = 121L;
  private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");

  @Mock private AdminScope adminScope;
  @Mock private AgencyAdminService agencyAdminService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private UserRepository userRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private AccountInviteRepository accountInviteRepository;

  private TenantDeletionBlockerService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantDeletionBlockerService(
            adminScope,
            agencyAdminService,
            consultantRepository,
            userRepository,
            adminRepository,
            accountInviteRepository,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void findBlockers_Should_reportEveryKindLeftAndNotBeDeletable_When_theTraegerStillHasThem() {
    when(agencyAdminService.countActiveAgenciesOfTenant(TENANT_ID)).thenReturn(3L);
    when(consultantRepository.countByTenantIdAndDeleteDateIsNull(TENANT_ID)).thenReturn(5L);
    when(userRepository.countActiveAdviceSeekersOfTenant(TENANT_ID)).thenReturn(7L);
    when(adminRepository.countByTenantIdAndType(TENANT_ID, AdminType.AGENCY)).thenReturn(2L);
    when(adminRepository.countByTenantIdAndType(TENANT_ID, AdminType.TENANT)).thenReturn(1L);
    when(accountInviteRepository.countOpenInvitesOfTenant(eq(TENANT_ID), any(), any()))
        .thenReturn(4L);

    var blockers = service.findBlockers(TENANT_ID);

    assertThat(blockers.getDeletable()).isFalse();
    assertThat(blockers.getAgencies()).isEqualTo(3L);
    assertThat(blockers.getConsultants()).isEqualTo(5L);
    assertThat(blockers.getAdviceSeekers()).isEqualTo(7L);
    assertThat(blockers.getAgencyAdmins()).isEqualTo(2L);
    assertThat(blockers.getTenantAdmins()).isEqualTo(1L);
    assertThat(blockers.getOpenInvites()).isEqualTo(4L);
    verify(adminScope).assertMay(Target.tenant(TENANT_ID));
  }

  @Test
  void findBlockers_Should_beDeletable_When_everySourceOfTheTraegerReportsNothingLeft() {
    givenLeftInTenant(Map.of());

    var blockers = service.findBlockers(TENANT_ID);

    assertThat(blockers.getDeletable()).isTrue();
    verify(agencyAdminService).countActiveAgenciesOfTenant(TENANT_ID);
    verify(consultantRepository).countByTenantIdAndDeleteDateIsNull(TENANT_ID);
    verify(userRepository).countActiveAdviceSeekersOfTenant(TENANT_ID);
    verify(adminRepository).countByTenantIdAndType(TENANT_ID, AdminType.AGENCY);
    verify(adminRepository).countByTenantIdAndType(TENANT_ID, AdminType.TENANT);
    verify(accountInviteRepository).countOpenInvitesOfTenant(eq(TENANT_ID), any(), any());
  }

  /** Each kind blocks on its own, so a term missing from the verdict fails one of these. */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void findBlockers_Should_notBeDeletableAndReportOnlyThatKind_When_onlyOneKindIsLeft(Kind kind) {
    givenLeftInTenant(Map.of(kind, 1L));

    var blockers = service.findBlockers(TENANT_ID);

    assertThat(blockers.getDeletable()).isFalse();
    for (Kind each : Kind.values()) {
      assertThat(each.of(blockers)).as(each.name()).isEqualTo(each == kind ? 1L : 0L);
    }
  }

  enum Kind {
    AGENCIES(TenantDeletionBlockersDTO::getAgencies),
    CONSULTANTS(TenantDeletionBlockersDTO::getConsultants),
    ADVICE_SEEKERS(TenantDeletionBlockersDTO::getAdviceSeekers),
    AGENCY_ADMINS(TenantDeletionBlockersDTO::getAgencyAdmins),
    TENANT_ADMINS(TenantDeletionBlockersDTO::getTenantAdmins),
    OPEN_INVITES(TenantDeletionBlockersDTO::getOpenInvites);

    private final Function<TenantDeletionBlockersDTO, Long> field;

    Kind(Function<TenantDeletionBlockersDTO, Long> field) {
      this.field = field;
    }

    Long of(TenantDeletionBlockersDTO blockers) {
      return field.apply(blockers);
    }
  }

  /** Stubs every count source explicitly for {@link #TENANT_ID}; kinds not given are 0. */
  private void givenLeftInTenant(Map<Kind, Long> left) {
    when(agencyAdminService.countActiveAgenciesOfTenant(TENANT_ID))
        .thenReturn(left.getOrDefault(Kind.AGENCIES, 0L));
    when(consultantRepository.countByTenantIdAndDeleteDateIsNull(TENANT_ID))
        .thenReturn(left.getOrDefault(Kind.CONSULTANTS, 0L));
    when(userRepository.countActiveAdviceSeekersOfTenant(TENANT_ID))
        .thenReturn(left.getOrDefault(Kind.ADVICE_SEEKERS, 0L));
    when(adminRepository.countByTenantIdAndType(TENANT_ID, AdminType.AGENCY))
        .thenReturn(left.getOrDefault(Kind.AGENCY_ADMINS, 0L));
    when(adminRepository.countByTenantIdAndType(TENANT_ID, AdminType.TENANT))
        .thenReturn(left.getOrDefault(Kind.TENANT_ADMINS, 0L));
    when(accountInviteRepository.countOpenInvitesOfTenant(eq(TENANT_ID), any(), any()))
        .thenReturn(left.getOrDefault(Kind.OPEN_INVITES, 0L));
  }

  @Test
  void findBlockers_Should_countOnlyInvitesThatCanStillBeAcceptedAtTheCurrentTime() {
    service.findBlockers(TENANT_ID);

    verify(accountInviteRepository)
        .countOpenInvitesOfTenant(
            TENANT_ID,
            TenantDeletionBlockerService.OPEN_INVITE_STATUSES,
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  @Test
  void findBlockers_Should_refuseAndCountNothing_When_theTraegerIsOutsideTheCallersReach() {
    doThrow(new ForbiddenException("out of scope")).when(adminScope).assertMay(any(Target.class));

    assertThatThrownBy(() -> service.findBlockers(TENANT_ID))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(
        agencyAdminService,
        consultantRepository,
        userRepository,
        adminRepository,
        accountInviteRepository);
  }

  @Test
  void findBlockers_Should_fail_When_AgencyServiceCannotBeAsked() {
    when(agencyAdminService.countActiveAgenciesOfTenant(anyLong()))
        .thenThrow(new RestClientException("AgencyService down"));

    assertThatThrownBy(() -> service.findBlockers(TENANT_ID))
        .isInstanceOf(RestClientException.class);
  }
}
