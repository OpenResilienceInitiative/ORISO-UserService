package de.caritas.cob.userservice.api.service.chat;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ChatDTO;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import de.caritas.cob.userservice.tenantservice.generated.web.model.Settings;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class GroupChatFeatureGateTest {

  private static final long TENANT_ID = 7L;
  private static final long AGENCY_ID = 42L;

  @Mock private TenantService tenantService;
  @Mock private EffectiveAgencySettingsLookup effectiveAgencySettingsLookup;
  @Mock private Consultant consultant;
  private GroupChatFeatureGate gate;

  @BeforeEach
  void setUp() {
    gate = new GroupChatFeatureGate(tenantService, effectiveAgencySettingsLookup);
  }

  // --- Traeger (tenant) level, unchanged behaviour -------------------------------------------

  @Test
  void enabledTenantMayCreateSelfHelpGroupSeries() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(true, true, true));

    assertThatCode(() -> gate.requireEnabled(consultant, circleRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void disabledTenantMayNotCreateSelfHelpGroupSeries() {
    givenTenantGroupChatV2(false);

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Self-help group chat is disabled for this tenant");
    verify(effectiveAgencySettingsLookup, never()).findEffectiveSettings(anyLong());
  }

  @Test
  void missingTenantContextFailsClosed() {
    assertThatThrownBy(() -> gate.requireEnabled(null, circleRequest()))
        .isInstanceOf(ForbiddenException.class);
  }

  // --- Beratungsstelle (agency) level, effective values from the AgencyService ---------------

  @Test
  void agencyWithCirclesOffRefusesCircleCreation() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(true, true, false));

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Conversation circles are disabled for agency 42");
  }

  @Test
  void agencyWithCirclesOffStillAllowsInternalGroupChat() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(true, true, false));

    assertThatCode(() -> gate.requireEnabled(consultant, internalGroupRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void agencyWithInternalGroupChatOffRefusesInternalGroupCreation() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(true, false, true));

    assertThatThrownBy(() -> gate.requireEnabled(consultant, internalGroupRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Internal group chats are disabled for agency 42");
  }

  @Test
  void agencyThatAllowsBothFormatsPasses() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(true, true, true));

    assertThatCode(() -> gate.requireEnabled(consultant, internalGroupRequest()))
        .doesNotThrowAnyException();
    assertThatCode(() -> gate.requireEnabled(consultant, circleRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void agencyWithGroupChatV2OffRefusesBothFormatsEvenIfFormatFlagsSayOn() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(false, true, true));

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Group chats are disabled for agency 42");
    assertThatThrownBy(() -> gate.requireEnabled(consultant, internalGroupRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Group chats are disabled for agency 42");
  }

  @Test
  void missingFormatFlagFallsBackToAgencyGroupChatV2() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(false, null, null));

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Group chats are disabled for agency 42");
  }

  @Test
  void agencyWithoutAnyRestrictionPasses() {
    givenTenantGroupChatV2(true);
    givenAgencySettings(agencySettings(null, null, null));

    assertThatCode(() -> gate.requireEnabled(consultant, circleRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void unknownAgencyLeavesTheTenantDecision() {
    givenTenantGroupChatV2(true);
    when(effectiveAgencySettingsLookup.findEffectiveSettings(AGENCY_ID))
        .thenReturn(Optional.empty());

    assertThatCode(() -> gate.requireEnabled(consultant, circleRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void agencyServiceFailureFallsBackToTenantThatAllows() {
    givenTenantGroupChatV2(true);
    when(effectiveAgencySettingsLookup.findEffectiveSettings(AGENCY_ID))
        .thenThrow(new ResourceAccessException("connection refused"));

    assertThatCode(() -> gate.requireEnabled(consultant, circleRequest()))
        .doesNotThrowAnyException();
  }

  @Test
  void agencyServiceFailureFallsBackToTenantThatRefuses() {
    givenTenantGroupChatV2(false);

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequest()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Self-help group chat is disabled for this tenant");
  }

  @Test
  void requestWithoutAgencyIdUsesTheConsultantsFirstAgency() {
    givenTenantGroupChatV2(true);
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setAgencyId(AGENCY_ID);
    when(consultant.getConsultantAgencies()).thenReturn(Set.of(consultantAgency));
    givenAgencySettings(agencySettings(true, true, false));

    assertThatThrownBy(() -> gate.requireEnabled(consultant, circleRequestWithoutAgency()))
        .isInstanceOf(ForbiddenException.class)
        .hasMessage("Conversation circles are disabled for agency 42");
  }

  @Test
  void requestWithoutAnyAgencyLeavesTheTenantDecision() {
    givenTenantGroupChatV2(true);
    when(consultant.getConsultantAgencies()).thenReturn(Set.of());

    assertThatCode(() -> gate.requireEnabled(consultant, circleRequestWithoutAgency()))
        .doesNotThrowAnyException();
    verify(effectiveAgencySettingsLookup, never()).findEffectiveSettings(anyLong());
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void givenTenantGroupChatV2(boolean enabled) {
    when(consultant.getTenantId()).thenReturn(TENANT_ID);
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .name("Tenant")
                .settings(new Settings().featureGroupChatV2Enabled(enabled)));
  }

  private void givenAgencySettings(
      de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings settings) {
    when(effectiveAgencySettingsLookup.findEffectiveSettings(AGENCY_ID))
        .thenReturn(Optional.of(settings));
  }

  private static de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings
      agencySettings(Boolean v2, Boolean internalGroupChat, Boolean selfHelpGroups) {
    return new de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings()
        .featureGroupChatV2Enabled(v2)
        .featureInternalGroupChatEnabled(internalGroupChat)
        .featureSelfHelpGroupsEnabled(selfHelpGroups);
  }

  /** A conversation circle: repetitive series, as {@code ChatConverter} classifies SELF_HELP. */
  private static ChatDTO circleRequest() {
    return ChatDTO.builder().topic("Circle").agencyId(AGENCY_ID).repetitive(true).build();
  }

  private static ChatDTO circleRequestWithoutAgency() {
    return ChatDTO.builder().topic("Circle").repetitive(true).build();
  }

  /** An internal team chat: no repetition fields, as {@code ChatConverter} classifies. */
  private static ChatDTO internalGroupRequest() {
    return ChatDTO.builder().topic("Team").agencyId(AGENCY_ID).build();
  }
}
