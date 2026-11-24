package de.caritas.cob.userservice.api.workflow.delete.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.IdentityClientConfig;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import de.caritas.cob.userservice.api.service.tenant.TenantDeletionFinishClient;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import de.caritas.cob.userservice.api.workflow.delete.service.TenantDeletionFinisher.Outcome;
import de.caritas.cob.userservice.api.workflow.delete.service.TenantDeletionFinisher.Step;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantDeletionFinisherTest {

  private static final long TRAEGER = 121L;

  @Mock private TenantDeletionFinishClient tenantService;
  @Mock private ConsultantRepository consultantRepository;
  @Mock private UserRepository userRepository;
  @Mock private AdminRepository adminRepository;
  @Mock private DeletionLifecycleService deletionLifecycleService;
  @Mock private DeletionAuditService deletionAuditService;
  @Mock private IdentityClientConfig identity;
  @InjectMocks private TenantDeletionFinisher finisher;

  @BeforeEach
  void fiveActors() {
    when(identity.isFiveActorMode()).thenReturn(true);
    when(tenantService.findTenantsMarkedForDeletion()).thenReturn(List.of(TRAEGER));
  }

  @Test
  void finish_Should_wait_While_anyAccountOfTheTraegerIsLeft() {
    when(userRepository.countAdviceSeekersOfTenant(TRAEGER)).thenReturn(1L);

    assertThat(finisher.finishMarkedTenants())
        .containsExactly(new Outcome(TRAEGER, Step.WAITING_FOR_ACCOUNTS));

    verify(tenantService, never()).removeTenant(anyLong());
    verifyNoInteractions(deletionLifecycleService, deletionAuditService);
  }

  @Test
  void finish_Should_wait_While_anAdminOfTheTraegerIsLeft() {
    when(adminRepository.countByTenantIdAndType(TRAEGER, AdminType.TENANT)).thenReturn(1L);

    assertThat(finisher.finishMarkedTenants())
        .containsExactly(new Outcome(TRAEGER, Step.WAITING_FOR_ACCOUNTS));
    verify(tenantService, never()).removeTenant(anyLong());
  }

  @Test
  void finish_Should_markTheTechnicalUsers_When_onlyTheyAreLeft() {
    var technical = user("group-chat-system-121", null);
    when(userRepository.findTechnicalUsersOfTenant(TRAEGER)).thenReturn(List.of(technical));

    assertThat(finisher.finishMarkedTenants())
        .containsExactly(new Outcome(TRAEGER, Step.MARKED_TECHNICAL_USERS));

    var order = inOrder(deletionLifecycleService, userRepository);
    order.verify(deletionLifecycleService).beginUserDeletion(technical, null);
    order.verify(userRepository).save(technical);
    verify(tenantService, never()).removeTenant(anyLong());
  }

  @Test
  void finish_Should_wait_While_theMarkedTechnicalUsersAreNotGoneYet() {
    when(userRepository.findTechnicalUsersOfTenant(TRAEGER))
        .thenReturn(List.of(user("group-chat-system-121", LocalDateTime.now())));

    assertThat(finisher.finishMarkedTenants())
        .containsExactly(new Outcome(TRAEGER, Step.WAITING_FOR_TECHNICAL_USERS));
    verify(tenantService, never()).removeTenant(anyLong());
  }

  @Test
  void finish_Should_removeTheTraegerAndLogIt_When_nothingIsLeft() {
    assertThat(finisher.finishMarkedTenants()).containsExactly(new Outcome(TRAEGER, Step.REMOVED));

    var order = inOrder(tenantService, deletionAuditService);
    order.verify(tenantService).removeTenant(TRAEGER);
    order
        .verify(deletionAuditService)
        .recordAsSystem(
            DeletionAuditTargetType.TENANT, "121", TRAEGER, DeletionAuditOutcome.EXECUTED);
  }

  @Test
  void finish_Should_notLogTheRemoval_When_TenantServiceRefuses() {
    doThrow(new IllegalStateException("Träger deletion removal failed: HTTP 409"))
        .when(tenantService)
        .removeTenant(TRAEGER);

    assertThat(finisher.finishMarkedTenants()).containsExactly(new Outcome(TRAEGER, Step.FAILED));
    verifyNoInteractions(deletionAuditService);
  }

  @Test
  void finish_Should_doNothing_Without_fiveActorMode() {
    when(identity.isFiveActorMode()).thenReturn(false);

    assertThat(finisher.finishMarkedTenants()).isEmpty();

    verifyNoInteractions(tenantService, deletionAuditService);
  }

  @Test
  void finish_Should_doNothing_When_theListCannotBeRead() {
    when(tenantService.findTenantsMarkedForDeletion())
        .thenThrow(new IllegalStateException("Träger deletion list failed"));

    assertThat(finisher.finishMarkedTenants()).isEmpty();
    verify(tenantService, never()).removeTenant(anyLong());
  }

  private static User user(String id, LocalDateTime deleteDate) {
    var user = new User();
    user.setUserId(id);
    user.setDeleteDate(deleteDate);
    return user;
  }
}
