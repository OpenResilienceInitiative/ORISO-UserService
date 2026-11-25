package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** ORISO-UserService#1687: case handover only where it is the centre's case sharing mode. */
@ExtendWith(MockitoExtension.class)
class CaseSharingGateTest {

  @Mock private EffectiveAgencySettingsLookup lookup;
  @InjectMocks private CaseSharingGate gate;

  @Test
  void allowsCaseHandover_whenItIsTheCentresMode() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER);

    assertThatCode(this::requireMailberatung).doesNotThrowAnyException();
  }

  @Test
  void refusesCaseHandover_inATeamCounsellingCentre() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING);

    assertFeatureDisabled();
  }

  @Test
  void refusesCaseHandover_whenTheCentreChoseNone() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.NONE);

    assertFeatureDisabled();
  }

  @Test
  void refusesCaseHandover_inLiveChat_withoutAskingTheAgencyService() {
    assertThatThrownBy(() -> gate.requireCaseHandover(5L, 1L, ConversationType.LIVE_CHAT))
        .isInstanceOf(CustomValidationHttpStatusException.class);
    verify(lookup, never()).findEffectiveSettings(any(), any());
  }

  @Test
  void leavesTheDecisionToTheTraegerMaster_whenTheAgencyServiceServesNoMode() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenReturn(Optional.of(new Settings()));

    assertThatCode(this::requireMailberatung).doesNotThrowAnyException();
  }

  @Test
  void refusesCaseHandover_whenAServedPolicyHasNoValue() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenReturn(Optional.of(new Settings(new AgencyCaseSharingPolicy())));

    assertFeatureDisabled();
  }

  @Test
  void refusesCaseHandover_whenTheCentresSettingsAreNotServed() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenReturn(Optional.empty());

    assertFeatureDisabled();
  }

  @Test
  void refusesCaseHandover_whenTheAgencyServiceRefusesTheProjection() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenThrow(new ForbiddenException("refused"));

    assertFeatureDisabled();
  }

  @Test
  void refusesCaseHandover_onAnotherClientError() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

    assertFeatureDisabled();
  }

  @ParameterizedTest
  @ValueSource(ints = {408, 429})
  void answersUnavailable_onARetryableClientError(int status) {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new HttpClientErrorException(HttpStatus.valueOf(status)));

    assertThatThrownBy(this::requireMailberatung).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void answersUnavailable_onAServerErrorOrTimeout() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY))
        .thenThrow(new ResourceAccessException("timeout"));

    assertThatThrownBy(this::requireMailberatung).isInstanceOf(ServiceUnavailableException.class);
    assertThatThrownBy(this::requireMailberatung).isInstanceOf(ServiceUnavailableException.class);
  }

  @Test
  void aCaseStampedAsCaseHandoverKeepsItAfterTheCentreSwitched() {
    // FE#2494 *4: the stamp decides; the centre's current model is not read.
    gate.requireCaseHandover(
        5L, 1L, ConversationType.AGENCY_COUNSELLING, CaseSharingModel.CASE_HANDOVER);
    verifyNoInteractions(lookup);
  }

  @ParameterizedTest
  @EnumSource(
      value = CaseSharingModel.class,
      names = {"TEAM_COUNSELLING", "NONE"})
  void aCaseStampedAsAnotherModelNeverOffersCaseHandover(CaseSharingModel stamped) {
    assertThatThrownBy(
            () -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING, stamped))
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            error -> assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN));
  }

  @Test
  void aStampedCaseStaysMailberatungOnly() {
    assertThatThrownBy(
            () ->
                gate.requireCaseHandover(
                    5L, 1L, ConversationType.LIVE_CHAT, CaseSharingModel.CASE_HANDOVER))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @Test
  void anUnstampedCaseFallsBackToTheCentresCurrentModel() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING);
    assertThatThrownBy(
            () -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING, null))
        .isInstanceOf(CustomValidationHttpStatusException.class);
  }

  @ParameterizedTest
  @EnumSource(AgencyCaseSharingPolicy.ValueEnum.class)
  void currentModel_namesTheServedModel(AgencyCaseSharingPolicy.ValueEnum served) {
    givenMode(served);
    assertThat(gate.currentModel(5L, 1L)).contains(CaseSharingModel.valueOf(served.name()));
  }

  @Test
  void currentModel_isUnknownOnlyWhileTheSettingIsUnreachable() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenThrow(new ResourceAccessException("down"));
    assertThat(gate.currentModel(5L, 1L)).isEmpty();
  }

  @Test
  void currentModel_isNoneForARefusedOrNoLongerServedCentre() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new ForbiddenException("another tenant"))
        .thenReturn(Optional.empty());
    assertThat(gate.currentModel(5L, 1L)).contains(CaseSharingModel.NONE);
    assertThat(gate.currentModel(5L, 1L)).contains(CaseSharingModel.NONE);
  }

  @Test
  void currentModel_isCaseHandoverForALegacyCentreWithoutAPolicy() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenReturn(Optional.of(new Settings()));
    assertThat(gate.currentModel(5L, 1L)).contains(CaseSharingModel.CASE_HANDOVER);
  }

  @Test
  void currentModel_isNoneForAServedPolicyWithoutAValue() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenReturn(Optional.of(new Settings(new AgencyCaseSharingPolicy())));
    assertThat(gate.currentModel(5L, 1L)).contains(CaseSharingModel.NONE);
  }

  /** CodeRabbit: before a type change, only a declared setting is a model; a refusal is none. */
  @Test
  void requirePreSwitchModel_returnsTheDeclaredSetting() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER);
    assertThat(gate.requirePreSwitchModel(5L, 1L)).isEqualTo(CaseSharingModel.CASE_HANDOVER);
    givenMode(AgencyCaseSharingPolicy.ValueEnum.NONE);
    assertThat(gate.requirePreSwitchModel(5L, 1L)).isEqualTo(CaseSharingModel.NONE);
    givenMode(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING);
    assertThat(gate.requirePreSwitchModel(5L, 1L)).isEqualTo(CaseSharingModel.NONE);
    when(lookup.findEffectiveSettings(5L, 1L)).thenReturn(Optional.of(new Settings()));
    assertThat(gate.requirePreSwitchModel(5L, 1L)).isEqualTo(CaseSharingModel.CASE_HANDOVER);
  }

  @Test
  void requirePreSwitchModel_refusesWhenTheModelCannotBeEstablished() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new ForbiddenException("another tenant"))
        .thenThrow(new ResourceAccessException("down"))
        .thenThrow(new IllegalStateException("unexpected"))
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(new Settings(new AgencyCaseSharingPolicy())));
    for (int read = 0; read < 5; read++) {
      org.junit.jupiter.api.Assertions.assertThrows(
          de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException.class,
          () -> gate.requirePreSwitchModel(5L, 1L));
    }
  }

  @Test
  void modelForNewCase_isTeamberatungForATeamCaseWithoutARead() {
    assertThat(gate.modelForNewCase(5L, 1L, true)).isEqualTo(CaseSharingModel.TEAM_COUNSELLING);
    verifyNoInteractions(lookup);
  }

  @Test
  void modelForNewCase_takesTheCentresModelForAnyOtherCase() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.NONE);
    assertThat(gate.modelForNewCase(5L, 1L, false)).isEqualTo(CaseSharingModel.NONE);
    givenMode(AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER);
    assertThat(gate.modelForNewCase(5L, 1L, false)).isEqualTo(CaseSharingModel.CASE_HANDOVER);
  }

  @Test
  void modelForNewCase_neverStampsANonTeamCaseAsTeamberatungNorInfersHandover() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING);
    assertThat(gate.modelForNewCase(5L, 1L, false)).isEqualTo(CaseSharingModel.NONE);
  }

  @Test
  void modelForNewCase_leavesTheStampToTheSnapshotOnlyWhileTheCentreIsUnreachable() {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenThrow(new ResourceAccessException("down"))
        .thenThrow(new ForbiddenException("refused"))
        .thenReturn(Optional.empty());
    assertThat(gate.modelForNewCase(5L, 1L, false)).isNull();
    assertThat(gate.modelForNewCase(5L, 1L, false)).isEqualTo(CaseSharingModel.NONE);
    assertThat(gate.modelForNewCase(5L, 1L, false)).isEqualTo(CaseSharingModel.NONE);
    assertThat(gate.modelForNewCase(null, 1L, false)).isEqualTo(CaseSharingModel.NONE);
  }

  private void requireMailberatung() {
    gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING);
  }

  private void assertFeatureDisabled() {
    assertThatThrownBy(this::requireMailberatung)
        .isInstanceOfSatisfying(
            CustomValidationHttpStatusException.class,
            error -> {
              assertThat(error.getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(error.getCustomHttpHeaders().getFirst("X-Reason"))
                  .isEqualTo(HttpStatusExceptionReason.FEATURE_DISABLED.name());
            });
  }

  private void givenMode(AgencyCaseSharingPolicy.ValueEnum mode) {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenReturn(Optional.of(new Settings(new AgencyCaseSharingPolicy().value(mode))));
  }
}
