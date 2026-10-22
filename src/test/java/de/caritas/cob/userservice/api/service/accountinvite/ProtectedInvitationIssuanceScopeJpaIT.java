package de.caritas.cob.userservice.api.service.accountinvite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.AccountLifecycleCodec;
import de.caritas.cob.userservice.api.adapters.keycloak.lifecycle.KeycloakProtectedInvitations;
import de.caritas.cob.userservice.api.service.accountinvite.AccountInviteService.CreateAccountInviteCommand;
import de.caritas.cob.userservice.api.service.accountinvite.allocation.IdAllocationMode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Next real public/JPA canonical-scope counterfactuals; no actual native HTTP claim. */
@TestPropertySource(properties = "TASK_IDENTITY_MODEL=FIVE_ACTORS")
class ProtectedInvitationIssuanceScopeJpaIT extends ProtectedInvitationIssuanceJpaFixture {
  @Autowired ProtectedInvitationIssuance issuance;
  @Autowired AccountLifecycleCodec json;

  @Test
  void mixedCaseRecipientIsCommittedCanonicallyBeforeTheExactProposalAndHumanRpc() {
    doThrow(new KeycloakProtectedInvitations.RemoteFailure(0))
        .when(approvals)
        .approve(any(), anyString());
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.TENANT_ADMIN,
            null,
            "  PROTECTED-OWNER@EXAMPLE.TEST  ",
            "Ada",
            "Lovelace",
            null,
            null,
            30L,
            IdAllocationMode.AUTO,
            null);

    assertThatThrownBy(() -> service.createAndSendInvite(command, template.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    var invite = invites.findAll().getFirst();
    assertThat(invite.getRecipientEmail()).isEqualTo(RECIPIENT);
    assertThat(scope(invite.getId()).path("recipient").asText()).isEqualTo(RECIPIENT);
    assertThat(invite.getTokenHash()).isNull();
    verify(approvals).approve(any(), anyString());
    verifyNoInteractions(sender);
  }

  @Test
  void platformInvitationUsesActualPlatformTenantZeroAndNeverReservesATenant() {
    doThrow(new KeycloakProtectedInvitations.RemoteFailure(0))
        .when(approvals)
        .approve(any(), anyString());
    var command =
        new CreateAccountInviteCommand(
            AccountInviteTargetRole.PLATFORM_ADMIN,
            null,
            RECIPIENT,
            "Ada",
            "Lovelace",
            null,
            null,
            30L,
            null,
            null);

    assertThatThrownBy(() -> service.createAndSendInvite(command, template.getId()))
        .isInstanceOf(KeycloakProtectedInvitations.RemoteFailure.class);

    var invite = invites.findAll().getFirst();
    assertThat(invite.getTenantId()).isEqualTo(0L);
    assertThat(invite.getTenantIdReservationToken()).isNull();
    JsonNode scope = scope(invite.getId());
    assertThat(scope.path("targetTenantIds").get(0).isIntegralNumber()).isTrue();
    assertThat(scope.path("targetTenantIds").get(0).longValue()).isZero();
    assertThat(scope.path("tenantMode").asText()).isEqualTo("EXISTING");
    assertThat(scope.path("reservationBinding").isNull()).isTrue();
    assertThat(scope.path("agencyBinding").isNull()).isTrue();
    verifyNoInteractions(tenantIds, agencyIds, sender);
  }

  @Test
  void restrictionMetadataCanDescribeAnExpiredDraftWithoutHumanBearerOrApproval() {
    var invite = service.createInvite(command());
    invite.setExpiresAt(LocalDateTime.now().minusDays(1));
    invite = invites.saveAndFlush(invite);
    String originalCreator = invite.getCreatedByUserId();
    clearInvocations(human, approvals, sender);

    var frozen = issuance.restrictionProposal(invite);

    assertThat(frozen).isNotNull();
    assertThat(frozen.issuerSubject()).isEqualTo(originalCreator);
    assertThat(frozen.requestJson()).doesNotContain("synthetic-immediate-human-memory-only");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM protected_invitation_authorization", Integer.class))
        .isZero();
    verifyNoInteractions(human, approvals, sender);
  }

  @Test
  void restrictionMetadataNeverSubstitutesTheCurrentHumanWhenOriginalCreatorIsUnknown() {
    var invite = service.createInvite(command());
    invite.setCreatedByUserId(null);
    clearInvocations(human, approvals, sender);

    assertThatThrownBy(() -> issuance.restrictionProposal(invite))
        .isInstanceOf(IllegalArgumentException.class);

    verifyNoInteractions(human, approvals, sender);
  }

  private JsonNode scope(long inviteId) {
    return json.read(
            jdbc.queryForObject(
                "SELECT request_json FROM protected_invitation_authorization WHERE invite_id=?",
                String.class,
                inviteId),
            JsonNode.class)
        .path("scope");
  }
}
