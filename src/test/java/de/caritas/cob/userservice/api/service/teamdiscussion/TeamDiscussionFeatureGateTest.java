package de.caritas.cob.userservice.api.service.teamdiscussion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.admin.service.tenant.TenantService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ChatAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.EffectiveAgencySettingsLookup;
import de.caritas.cob.userservice.api.service.chat.MembersOnlyChatAccess;
import de.caritas.cob.userservice.api.service.conversationfeature.ConversationFeatureGate;
import de.caritas.cob.userservice.api.tenant.TenantContext;
import de.caritas.cob.userservice.tenantservice.generated.web.model.RestrictedTenantDTO;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

/**
 * ORISO-Frontend#2491: the team chat is gated like Supervision — Träger AND Beratungsstelle {@code
 * featureTeamDiscussionEnabled}, unset = on, unreadable = 403, outage = 503 — and the deploy
 * property {@code team-discussion.enabled} stays a global kill switch.
 */
class TeamDiscussionFeatureGateTest {

  private static final long TENANT_ID = 7L;
  private static final long AGENCY_ID = 31L;

  private final TenantService tenantService = mock(TenantService.class);
  private final EffectiveAgencySettingsLookup agencySettings =
      mock(EffectiveAgencySettingsLookup.class);
  private final TeamDiscussionFeatureGate gate =
      new TeamDiscussionFeatureGate(
          new ConversationFeatureGate(
              tenantService,
              agencySettings,
              mock(ChatAgencyRepository.class),
              mock(MembersOnlyChatAccess.class)));

  @AfterEach
  void clearTenantContext() {
    TenantContext.clear();
  }

  private TeamDiscussionFeatureGate withKillSwitch(boolean enabled) {
    ReflectionTestUtils.setField(gate, "teamDiscussionEnabled", enabled);
    return gate;
  }

  private void traeger(Boolean flag) {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .name("Traeger")
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureTeamDiscussionEnabled(flag)));
  }

  private void agency(Boolean flag) {
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(Optional.of(new Settings().featureTeamDiscussionEnabled(flag)));
  }

  private static Session enquiry() {
    var session = new Session();
    session.setTenantId(TENANT_ID);
    session.setAgencyId(AGENCY_ID);
    return session;
  }

  private static void assertForbidden(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            ex -> assertThat(ex.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void traegerAndBeratungsstelleOn_allows() {
    traeger(true);
    agency(true);
    assertThatCode(() -> withKillSwitch(true).requireEnabled(enquiry())).doesNotThrowAnyException();
  }

  @Test
  void traegerOff_refuses_withoutAskingTheBeratungsstelle() {
    traeger(false);
    assertForbidden(() -> withKillSwitch(true).requireEnabled(enquiry()));
    verify(agencySettings, never()).findEffectiveSettings(anyLong(), anyLong());
  }

  @Test
  void beratungsstelleOff_refuses_evenWhenTheTraegerIsOn() {
    traeger(true);
    agency(false);
    assertForbidden(() -> withKillSwitch(true).requireEnabled(enquiry()));
  }

  @Test
  void beratungsstelleUnset_isOn() {
    // An older AgencyService serves no featureTeamDiscussionEnabled at all.
    traeger(true);
    agency(null);
    assertThatCode(() -> withKillSwitch(true).requireEnabled(enquiry())).doesNotThrowAnyException();
  }

  @Test
  void bothUnset_isOn() {
    traeger(null);
    agency(null);
    assertThatCode(() -> withKillSwitch(true).requireEnabled(enquiry())).doesNotThrowAnyException();
  }

  @Test
  void beratungsstelleNotServed_refuses() {
    traeger(true);
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID)).thenReturn(Optional.empty());
    assertForbidden(() -> withKillSwitch(true).requireEnabled(enquiry()));
  }

  @Test
  void agencyServiceOutage_isServiceUnavailable() {
    traeger(true);
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ResourceAccessException("down"));
    assertThatThrownBy(() -> withKillSwitch(true).requireEnabled(enquiry()))
        .isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void killSwitchOff_refuses_beforeAnySettingIsRead() {
    assertForbidden(() -> withKillSwitch(false).requireEnabled(enquiry()));
    verifyNoInteractions(tenantService, agencySettings);
  }

  // ---- featureTeamDiscussionAfterAcceptEnabled: unset = off, master must be on ----

  private void afterAccept(Boolean traegerValue, Boolean agencyValue) {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .name("Traeger")
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureTeamDiscussionAfterAcceptEnabled(traegerValue)));
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenReturn(
            Optional.of(new Settings().featureTeamDiscussionAfterAcceptEnabled(agencyValue)));
  }

  @Test
  void afterAccept_unsetIsOff() {
    afterAccept(null, null);
    assertThat(withKillSwitch(true).continuesAfterAccept(enquiry())).isFalse();
  }

  @Test
  void afterAccept_onForTraegerAndBeratungsstelle_continues() {
    afterAccept(true, true);
    assertThat(withKillSwitch(true).continuesAfterAccept(enquiry())).isTrue();
  }

  @Test
  void afterAccept_beratungsstelleUnset_isOff() {
    afterAccept(true, null);
    assertThat(withKillSwitch(true).continuesAfterAccept(enquiry())).isFalse();
  }

  @Test
  void afterAccept_traegerOff_isOff() {
    afterAccept(false, true);
    assertThat(withKillSwitch(true).continuesAfterAccept(enquiry())).isFalse();
  }

  @Test
  void afterAccept_onlyWhileTheMasterIsOn() {
    when(tenantService.getRestrictedTenantDataFresh(TENANT_ID))
        .thenReturn(
            new RestrictedTenantDTO()
                .id(TENANT_ID)
                .name("Traeger")
                .settings(
                    new de.caritas.cob.userservice.tenantservice.generated.web.model.Settings()
                        .featureTeamDiscussionEnabled(false)
                        .featureTeamDiscussionAfterAcceptEnabled(true)));
    assertThat(withKillSwitch(true).continuesAfterAccept(enquiry())).isFalse();
    assertThat(withKillSwitch(false).continuesAfterAccept(enquiry())).isFalse();
  }

  @Test
  void afterAccept_outageIsNotAnAnswer() {
    afterAccept(true, true);
    when(agencySettings.findEffectiveSettings(AGENCY_ID, TENANT_ID))
        .thenThrow(new ResourceAccessException("down"));
    assertThatThrownBy(() -> withKillSwitch(true).continuesAfterAccept(enquiry()))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
