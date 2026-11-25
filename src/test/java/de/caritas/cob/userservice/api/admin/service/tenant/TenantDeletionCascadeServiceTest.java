package de.caritas.cob.userservice.api.admin.service.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.TenantDeletionFailureDTO.KindEnum;
import de.caritas.cob.userservice.api.admin.facade.AskerUserAdminFacade;
import de.caritas.cob.userservice.api.admin.facade.ConsultantAdminFacade;
import de.caritas.cob.userservice.api.admin.service.admin.delete.DeleteAdminService;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation;
import de.caritas.cob.userservice.api.service.accountinvite.DeletedUnitInviteRevocation.Revoked;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class TenantDeletionCascadeServiceTest {

  private static final long TENANT_ID = 121L;

  @Mock private DeletedUnitInviteRevocation inviteRevocation;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private ConsultantAdminFacade consultantAdminFacade;
  @Mock private UserRepository userRepository;
  @Mock private AskerUserAdminFacade askerUserAdminFacade;
  @Mock private AdminRepository adminRepository;
  @Mock private DeleteAdminService deleteAdminService;
  @Mock private AgencyAdminService agencyAdminService;

  private TenantDeletionCascadeService service;

  @BeforeEach
  void setUp() {
    service =
        new TenantDeletionCascadeService(
            inviteRevocation,
            consultantRepository,
            consultantAdminFacade,
            userRepository,
            askerUserAdminFacade,
            adminRepository,
            deleteAdminService,
            agencyAdminService);
  }

  @Test
  void cascade_Should_markEveryChildOfTheTraeger_inParentBeforeAgencyOrder() {
    when(inviteRevocation.revokeForTenant(TENANT_ID)).thenReturn(new Revoked(2, 1));
    when(consultantRepository.findByTenantIdAndDeleteDateIsNull(TENANT_ID))
        .thenReturn(List.of(consultant("c1"), consultant("c2")));
    when(userRepository.findActiveAdviceSeekersOfTenant(TENANT_ID))
        .thenReturn(List.of(asker("u1")));
    when(adminRepository.findByTenantIdAndType(TENANT_ID, AdminType.AGENCY))
        .thenReturn(List.of(admin("aa1")));
    when(adminRepository.findByTenantIdAndType(TENANT_ID, AdminType.TENANT))
        .thenReturn(List.of(admin("ta1")));
    when(agencyAdminService.findActiveAgencyIdsOfTenant(TENANT_ID)).thenReturn(List.of(7L, 8L));

    var result = service.cascade(TENANT_ID);

    assertThat(result.getComplete()).isTrue();
    assertThat(result.getInvites()).isEqualTo(3L);
    assertThat(result.getConsultants()).isEqualTo(2L);
    assertThat(result.getAdviceSeekers()).isEqualTo(1L);
    assertThat(result.getAgencyAdmins()).isEqualTo(1L);
    assertThat(result.getTenantAdmins()).isEqualTo(1L);
    assertThat(result.getAgencies()).isEqualTo(2L);
    assertThat(result.getFailures()).isEmpty();

    // Invites first, so no account appears in the Träger while it is emptied; then each agency
    // through its own cascade (Admin#1524), then whatever no agency covered.
    var order =
        inOrder(
            inviteRevocation,
            consultantAdminFacade,
            askerUserAdminFacade,
            deleteAdminService,
            agencyAdminService);
    order.verify(inviteRevocation).revokeForTenant(TENANT_ID);
    order.verify(agencyAdminService).markAgencyForDeletion(7L);
    order.verify(agencyAdminService).markAgencyForDeletion(8L);
    order.verify(consultantAdminFacade).markConsultantForDeletion("c1", true);
    order.verify(consultantAdminFacade).markConsultantForDeletion("c2", true);
    order.verify(askerUserAdminFacade).markAskerForDeletion("u1");
    order.verify(deleteAdminService).deleteAgencyAdmin("aa1");
    order.verify(deleteAdminService).deleteTenantAdmin("ta1");
  }

  @Test
  void cascade_Should_carryOnAndReportTheFailure_When_oneChildCannotBeMarked() {
    when(inviteRevocation.revokeForTenant(TENANT_ID)).thenReturn(new Revoked(0, 0));
    when(consultantRepository.findByTenantIdAndDeleteDateIsNull(TENANT_ID))
        .thenReturn(List.of(consultant("c1"), consultant("c2")));
    doThrow(new IllegalStateException("Matrix down"))
        .when(consultantAdminFacade)
        .markConsultantForDeletion("c1", true);
    when(agencyAdminService.findActiveAgencyIdsOfTenant(TENANT_ID)).thenReturn(List.of(7L));
    doThrow(new RestClientException("409")).when(agencyAdminService).markAgencyForDeletion(7L);

    var result = service.cascade(TENANT_ID);

    assertThat(result.getComplete()).isFalse();
    assertThat(result.getConsultants()).isEqualTo(1L);
    assertThat(result.getAgencies()).isZero();
    assertThat(result.getFailures())
        .extracting("kind", "id", "reason")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(KindEnum.AGENCY, "7", "RestClientException"),
            org.assertj.core.groups.Tuple.tuple(
                KindEnum.CONSULTANT, "c1", "IllegalStateException"));
    verify(consultantAdminFacade).markConsultantForDeletion("c2", true);
  }

  @Test
  void cascade_Should_reportTheFailure_When_theAgenciesCannotBeListed() {
    when(inviteRevocation.revokeForTenant(TENANT_ID)).thenReturn(new Revoked(0, 0));
    when(agencyAdminService.findActiveAgencyIdsOfTenant(TENANT_ID))
        .thenThrow(new RestClientException("down"));

    var result = service.cascade(TENANT_ID);

    assertThat(result.getComplete()).isFalse();
    assertThat(result.getFailures())
        .extracting("kind", "id")
        .containsExactly(org.assertj.core.groups.Tuple.tuple(KindEnum.AGENCY, null));
  }

  @Test
  void cascade_Should_doNothing_When_theTraegerIsAlreadyEmpty() {
    when(inviteRevocation.revokeForTenant(TENANT_ID)).thenReturn(new Revoked(0, 0));
    when(agencyAdminService.findActiveAgencyIdsOfTenant(TENANT_ID)).thenReturn(List.of());

    var result = service.cascade(TENANT_ID);

    assertThat(result.getComplete()).isTrue();
    assertThat(result.getConsultants()).isZero();
    verifyNoInteractions(consultantAdminFacade, askerUserAdminFacade, deleteAdminService);
  }

  @ParameterizedTest
  @ValueSource(longs = {0L, -1L})
  void cascade_Should_refuse_When_theTenantIsThePlatformOrInvalid(long tenantId) {
    assertThatThrownBy(() -> service.cascade(tenantId)).isInstanceOf(BadRequestException.class);
    verifyNoInteractions(inviteRevocation, consultantRepository, agencyAdminService);
  }

  private static Consultant consultant(String id) {
    var consultant = new Consultant();
    consultant.setId(id);
    return consultant;
  }

  private static User asker(String id) {
    var user = new User();
    user.setUserId(id);
    return user;
  }

  private static Admin admin(String id) {
    var admin = new Admin();
    admin.setId(id);
    return admin;
  }
}
