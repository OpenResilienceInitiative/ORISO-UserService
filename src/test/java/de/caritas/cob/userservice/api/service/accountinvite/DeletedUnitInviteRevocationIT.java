package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.AccountInvite;
import de.caritas.cob.userservice.api.model.AgencyInviteLink;
import de.caritas.cob.userservice.api.port.out.AccountInviteRepository;
import de.caritas.cob.userservice.api.port.out.AgencyInviteLinkRepository;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import de.caritas.cob.userservice.api.service.agencyinvitelink.DirectInvitationExpiryService;
import de.caritas.cob.userservice.api.service.agencyinvitelink.InviteLinkStatus;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deleting a Träger or a Beratungsstelle revokes its open invitations and switches its invite links
 * off at once (ORISO-Admin#1522, ORISO-UserService#1617 part 2).
 */
@DataJpaTest
@TestPropertySource(properties = {"spring.profiles.active=testing", "multitenancy.enabled=false"})
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(DeletedUnitInviteRevocation.class)
class DeletedUnitInviteRevocationIT {

  private static final long TENANT = 7L;
  private static final long OTHER_TENANT = 8L;
  private static final long AGENCY = 70L;
  private static final long OTHER_AGENCY = 71L;

  @Autowired private DeletedUnitInviteRevocation revocation;
  @Autowired private AccountInviteRepository invites;
  @Autowired private AgencyInviteLinkRepository links;

  @MockitoBean private DirectInvitationExpiryService invitationEnd;

  @AfterEach
  void cleanUp() {
    invites.deleteAll();
    links.deleteAll();
  }

  @Test
  void aDeletedAgency_Should_LoseItsOpenInvites_AndFreeTheirAddresses() {
    AccountInvite draft = invite(TENANT, AGENCY, AccountInviteStatus.DRAFT);
    AccountInvite sent = invite(TENANT, AGENCY, AccountInviteStatus.EMAIL_SENT);
    AccountInvite waiting = invite(TENANT, AGENCY, AccountInviteStatus.WAITING_FOR_UNIT);

    var result = revocation.revokeForAgency(AGENCY);

    assertThat(result.invites()).isEqualTo(3);
    for (AccountInvite invite : new AccountInvite[] {draft, sent, waiting}) {
      AccountInvite stored = reload(invite);
      assertThat(stored.getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
      assertThat(stored.getActiveRecipientKey()).isNull();
      assertThat(stored.getRevokedAt()).isNotNull();
      assertThat(stored.getProvisioningFailureReason())
          .isEqualTo(DeletedUnitInviteRevocation.AGENCY_DELETED);
    }
  }

  @Test
  void aDeletedAgency_Should_LeaveSettledInvitesAndOtherAgenciesAlone() {
    AccountInvite accepted = invite(TENANT, AGENCY, AccountInviteStatus.ACCEPTED);
    AccountInvite expired = invite(TENANT, AGENCY, AccountInviteStatus.EXPIRED);
    AccountInvite elsewhere = invite(TENANT, OTHER_AGENCY, AccountInviteStatus.EMAIL_SENT);

    var result = revocation.revokeForAgency(AGENCY);

    assertThat(result.invites()).isZero();
    assertThat(reload(accepted).getStatus()).isEqualTo(AccountInviteStatus.ACCEPTED);
    assertThat(reload(expired).getStatus()).isEqualTo(AccountInviteStatus.EXPIRED);
    assertThat(reload(elsewhere).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void aSetupLinkWhoseAccountIsBeingSetUp_Should_NotBeTouched() {
    AccountInvite inProgress =
        invites.save(
            row(TENANT, AGENCY, AccountInviteStatus.EMAIL_SENT)
                .purpose(AccountInvitePurpose.EXISTING_ACCOUNT_SETUP)
                .provisioningStatus(AccountInviteProvisioningStatus.IN_PROGRESS)
                .build());

    revocation.revokeForAgency(AGENCY);

    assertThat(reload(inProgress).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  @Test
  void aDeletedAgency_Should_SwitchOffItsActiveLinks_AndEndInvitationAccess() {
    AgencyInviteLink active = link(TENANT, AGENCY, InviteLinkStatus.ACTIVE, null);
    AgencyInviteLink withAppointment =
        link(TENANT, AGENCY, InviteLinkStatus.ACTIVE, UUID.randomUUID());
    AgencyInviteLink expired = link(TENANT, AGENCY, InviteLinkStatus.EXPIRED, null);
    AgencyInviteLink elsewhere = link(TENANT, OTHER_AGENCY, InviteLinkStatus.ACTIVE, null);

    var result = revocation.revokeForAgency(AGENCY);

    assertThat(result.links()).isEqualTo(2);
    assertThat(status(active)).isEqualTo(InviteLinkStatus.DISABLED.name());
    assertThat(status(withAppointment)).isEqualTo(InviteLinkStatus.DISABLED.name());
    assertThat(status(expired)).isEqualTo(InviteLinkStatus.EXPIRED.name());
    assertThat(status(elsewhere)).isEqualTo(InviteLinkStatus.ACTIVE.name());
    org.mockito.Mockito.verify(invitationEnd)
        .revoke(
            org.mockito.ArgumentMatchers.argThat(
                link -> link.getId().equals(withAppointment.getId())));
  }

  @Test
  void aDeletedTenant_Should_LoseItsOpenInvitesAndLinks_AndNoOtherTenants() {
    AccountInvite traegerAdmin = invite(TENANT, null, AccountInviteStatus.EMAIL_SENT);
    AccountInvite counsellor = invite(TENANT, AGENCY, AccountInviteStatus.DRAFT);
    AccountInvite otherTenant = invite(OTHER_TENANT, OTHER_AGENCY, AccountInviteStatus.DRAFT);
    AgencyInviteLink liveChat = link(TENANT, null, InviteLinkStatus.ACTIVE, null);

    var result = revocation.revokeForTenant(TENANT);

    assertThat(result.invites()).isEqualTo(2);
    assertThat(result.links()).isEqualTo(1);
    assertThat(reload(traegerAdmin).getProvisioningFailureReason())
        .isEqualTo(DeletedUnitInviteRevocation.TENANT_DELETED);
    assertThat(reload(counsellor).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(reload(otherTenant).getStatus()).isEqualTo(AccountInviteStatus.DRAFT);
    assertThat(status(liveChat)).isEqualTo(InviteLinkStatus.DISABLED.name());
  }

  @Test
  void repeatingTheRevocation_Should_ChangeNothing() {
    invite(TENANT, AGENCY, AccountInviteStatus.EMAIL_SENT);
    link(TENANT, AGENCY, InviteLinkStatus.ACTIVE, null);
    revocation.revokeForAgency(AGENCY);

    var second = revocation.revokeForAgency(AGENCY);

    assertThat(second.invites()).isZero();
    assertThat(second.links()).isZero();
  }

  @Test
  void thePlatformTenant_Should_NeverBeRevoked() {
    AccountInvite platformAdmin = invite(0L, null, AccountInviteStatus.EMAIL_SENT);

    var result = revocation.revokeForTenant(0L);

    assertThat(result.invites()).isZero();
    assertThat(reload(platformAdmin).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
  }

  /**
   * Review of ORISO-UserService#1675: the invites and the links of one deletion commit together. If
   * switching a link off fails, nothing is half done and the caller learns it, so a retry finishes
   * the job.
   */
  @Test
  void aFailureWhileSwitchingOffLinks_Should_RollBackTheRevokedInvites_AndReportIt() {
    AccountInvite sent = invite(TENANT, AGENCY, AccountInviteStatus.EMAIL_SENT);
    AgencyInviteLink withAppointment =
        link(TENANT, AGENCY, InviteLinkStatus.ACTIVE, UUID.randomUUID());
    org.mockito.Mockito.doThrow(new IllegalStateException("matrix down"))
        .when(invitationEnd)
        .revoke(org.mockito.ArgumentMatchers.any());

    boolean done =
        revocation.onDeletionReported(
            de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType.AGENCY,
            String.valueOf(AGENCY),
            de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome.SCHEDULED);

    assertThat(done).isFalse();
    assertThat(reload(sent).getStatus()).isEqualTo(AccountInviteStatus.EMAIL_SENT);
    assertThat(reload(sent).getActiveRecipientKey()).isNotNull();
    assertThat(status(withAppointment)).isEqualTo(InviteLinkStatus.ACTIVE.name());

    org.mockito.Mockito.reset(invitationEnd);
    assertThat(
            revocation.onDeletionReported(
                de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditTargetType.AGENCY,
                String.valueOf(AGENCY),
                de.caritas.cob.userservice.api.workflow.delete.model.DeletionAuditOutcome
                    .SCHEDULED))
        .isTrue();
    assertThat(reload(sent).getStatus()).isEqualTo(AccountInviteStatus.REVOKED);
    assertThat(status(withAppointment)).isEqualTo(InviteLinkStatus.DISABLED.name());
  }

  // --- helpers ----------------------------------------------------------------------------------

  private AccountInvite invite(Long tenantId, Long agencyId, AccountInviteStatus status) {
    return invites.save(row(tenantId, agencyId, status).build());
  }

  private static AccountInvite.AccountInviteBuilder row(
      Long tenantId, Long agencyId, AccountInviteStatus status) {
    String email = UUID.randomUUID() + "@example.org";
    boolean open =
        status == AccountInviteStatus.DRAFT
            || status == AccountInviteStatus.EMAIL_SENT
            || status == AccountInviteStatus.WAITING_FOR_UNIT;
    return AccountInvite.builder()
        .targetRole(
            agencyId == null
                ? AccountInviteTargetRole.TENANT_ADMIN
                : AccountInviteTargetRole.COUNSELLOR)
        .tenantId(tenantId)
        .recipientEmail(email)
        .activeRecipientKey(open ? email : null)
        .agencyId(agencyId)
        .agencyIdAllocationMode(agencyId == null ? null : IdAllocationMode.EXISTING)
        .status(status)
        .expiresAt(LocalDateTime.now().plusDays(30))
        .provisioningStatus(AccountInviteProvisioningStatus.PENDING)
        .emailVerificationStatus(EmailVerificationStatus.PENDING)
        .twoFactorStatus(TwoFactorGateStatus.PENDING_SETUP)
        .createDate(LocalDateTime.now())
        .updateDate(LocalDateTime.now());
  }

  private AgencyInviteLink link(
      Long tenantId, Long agencyId, InviteLinkStatus status, UUID appointmentId) {
    return links.save(
        AgencyInviteLink.builder()
            .token(UUID.randomUUID().toString())
            .tenantId(tenantId)
            .agencyId(agencyId)
            .linkKind(agencyId == null ? "EXTERNAL_INBOUND" : "COUNSELLOR")
            .chatType(agencyId == null ? "LIVE_CHAT" : "CHAT")
            .anonymity("ANONYMOUS")
            .consultantId(agencyId == null ? null : "counsellor-1")
            .createdByUserId("admin-1")
            .createDate(LocalDateTime.now())
            .status(status.name())
            .appointmentId(appointmentId)
            .build());
  }

  private AccountInvite reload(AccountInvite invite) {
    return invites.findById(invite.getId()).orElseThrow();
  }

  private String status(AgencyInviteLink link) {
    return links.findById(link.getId()).orElseThrow().getStatus();
  }
}
