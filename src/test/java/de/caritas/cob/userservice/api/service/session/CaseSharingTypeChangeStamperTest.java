package de.caritas.cob.userservice.api.service.session;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ServiceUnavailableException;
import de.caritas.cob.userservice.api.model.CaseSharingModel;
import de.caritas.cob.userservice.api.model.Session.RegistrationType;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.service.agency.CaseSharingGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** FE#2494 *4: a centre's type change stamps its unstamped cases with the pre-switch model. */
@ExtendWith(MockitoExtension.class)
class CaseSharingTypeChangeStamperTest {

  @Mock private SessionRepository sessions;
  @Mock private CaseSharingGate gate;
  @InjectMocks private CaseSharingTypeChangeStamper stamper;

  @Test
  void aDefaultCentreBecomingTeamStampsItsCurrentHandoverModelFirst() {
    when(gate.requirePreSwitchModel(5L, 7L)).thenReturn(CaseSharingModel.CASE_HANDOVER);

    stamper.stampBeforeTypeChange(5L, 7L, true);

    verify(sessions)
        .stampUnstampedCasesOfAgency(
            5L, true, CaseSharingModel.TEAM_COUNSELLING, RegistrationType.ANONYMOUS);
    verify(sessions)
        .stampUnstampedCasesOfAgency(
            5L, false, CaseSharingModel.CASE_HANDOVER, RegistrationType.ANONYMOUS);
  }

  @Test
  void aTeamCentreBecomingDefaultStampsItsOtherCasesNoneWithoutAHandoverRight() {
    stamper.stampBeforeTypeChange(5L, 7L, false);

    verify(sessions)
        .stampUnstampedCasesOfAgency(
            5L, true, CaseSharingModel.TEAM_COUNSELLING, RegistrationType.ANONYMOUS);
    verify(sessions)
        .stampUnstampedCasesOfAgency(5L, false, CaseSharingModel.NONE, RegistrationType.ANONYMOUS);
    verifyNoInteractions(gate);
  }

  @Test
  void anUnreachableCentreRefusesTheSwitchAndLeavesItsOtherCasesUnstamped() {
    when(gate.requirePreSwitchModel(5L, 7L))
        .thenThrow(new ServiceUnavailableException("not established"));

    assertThrows(
        ServiceUnavailableException.class, () -> stamper.stampBeforeTypeChange(5L, 7L, true));

    verify(sessions, never())
        .stampUnstampedCasesOfAgency(
            anyLong(),
            eq(false),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }
}
