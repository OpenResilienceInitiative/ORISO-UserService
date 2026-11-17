package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationExpiryService;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome;
import de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class DeletedUnitInviteRevocationTest {

  @Mock private AccountInviteRepository invites;
  @Mock private AgencyInviteLinkRepository links;
  @Mock private ObjectProvider<DirectInvitationExpiryService> invitationEnd;
  @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;
  @InjectMocks private DeletedUnitInviteRevocation revocation;

  @Test
  void aScheduledAgencyDeletion_Should_RevokeThatAgency() {
    revocation.onDeletionReported(
        DeletionAuditTargetType.AGENCY, "268", DeletionAuditOutcome.SCHEDULED);

    verify(invites)
        .revokeOpenInvitesOfUnit(
            eq(null), eq(268L), any(), eq(DeletedUnitInviteRevocation.AGENCY_DELETED), any());
    verify(links).findByAgencyIdAndStatus(268L, "ACTIVE");
  }

  @Test
  void anExecutedTenantDeletion_Should_RevokeThatTenant() {
    revocation.onDeletionReported(
        DeletionAuditTargetType.TENANT, "4", DeletionAuditOutcome.EXECUTED);

    verify(invites)
        .revokeOpenInvitesOfUnit(
            eq(4L), eq(null), any(), eq(DeletedUnitInviteRevocation.TENANT_DELETED), any());
  }

  @Test
  void aCancelledOrPartlyFailedDeletion_Should_RevokeNothing() {
    revocation.onDeletionReported(
        DeletionAuditTargetType.AGENCY, "268", DeletionAuditOutcome.CANCELLED);
    revocation.onDeletionReported(
        DeletionAuditTargetType.TENANT, "4", DeletionAuditOutcome.PARTIAL_FAILURE);

    verifyNoInteractions(invites, links);
  }

  @Test
  void aFailure_Should_NeverReachTheReporter() {
    when(invites.revokeOpenInvitesOfUnit(any(), anyLong(), any(), anyString(), any()))
        .thenThrow(new IllegalStateException("database down"));

    org.assertj.core.api.Assertions.assertThat(
            revocation.onDeletionReported(
                DeletionAuditTargetType.AGENCY, "268", DeletionAuditOutcome.SCHEDULED))
        .isFalse();
  }

  @Test
  void anUnreadableTargetId_Should_BeIgnored() {
    assertThatCode(
            () ->
                revocation.onDeletionReported(
                    DeletionAuditTargetType.AGENCY, "not-a-number", DeletionAuditOutcome.SCHEDULED))
        .doesNotThrowAnyException();
    verifyNoInteractions(invites, links);
  }
}
