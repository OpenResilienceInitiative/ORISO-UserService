package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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
