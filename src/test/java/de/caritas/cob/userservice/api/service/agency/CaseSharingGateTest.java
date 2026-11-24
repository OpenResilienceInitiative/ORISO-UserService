package de.caritas.cob.userservice.api.service.agency;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyserivce.generated.web.model.AgencyCaseSharingPolicy;
import de.caritas.cob.userservice.agencyserivce.generated.web.model.Settings;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.ConversationType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** ORISO-UserService#1687: case handover only where it is the centre's case sharing mode. */
@ExtendWith(MockitoExtension.class)
class CaseSharingGateTest {

  @Mock private EffectiveAgencySettingsLookup lookup;
  @InjectMocks private CaseSharingGate gate;

  @Test
  void allowsCaseHandover_whenItIsTheCentresMode() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.CASE_HANDOVER);

    assertThatCode(() -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING))
        .doesNotThrowAnyException();
  }

  @Test
  void refusesCaseHandover_inATeamCounsellingCentre() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.TEAM_COUNSELLING);

    assertThatThrownBy(() -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void refusesCaseHandover_whenTheCentreChoseNone() {
    givenMode(AgencyCaseSharingPolicy.ValueEnum.NONE);

    assertThatThrownBy(() -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void refusesCaseHandover_inLiveChat_withoutAskingTheAgencyService() {
    assertThatThrownBy(() -> gate.requireCaseHandover(5L, 1L, ConversationType.LIVE_CHAT))
        .isInstanceOf(ForbiddenException.class);
    verify(lookup, never()).findEffectiveSettings(any(), any());
  }

  @Test
  void leavesTheDecisionToTheTraegerMaster_whenTheAgencyServiceServesNoMode() {
    when(lookup.findEffectiveSettings(5L, 1L)).thenReturn(Optional.of(new Settings()));

    assertThatCode(() -> gate.requireCaseHandover(5L, 1L, ConversationType.AGENCY_COUNSELLING))
        .doesNotThrowAnyException();
  }

  private void givenMode(AgencyCaseSharingPolicy.ValueEnum mode) {
    when(lookup.findEffectiveSettings(5L, 1L))
        .thenReturn(
            Optional.of(new Settings().caseSharing(new AgencyCaseSharingPolicy().value(mode))));
  }
}
