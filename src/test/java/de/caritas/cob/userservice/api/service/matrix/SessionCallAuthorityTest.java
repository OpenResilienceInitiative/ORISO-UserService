package de.caritas.cob.userservice.api.service.matrix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.MatrixCallBinding;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.workflow.accountinactivity.AccountInactivityService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CodeRabbit 4585785683: the binding's owner vouches only under the unique-identity rule. */
class SessionCallAuthorityTest {
  private static final String COUNSELLOR = "@counsellor:example";

  private final SessionRepository sessions = mock(SessionRepository.class);
  private final ConsultantRepository consultants = mock(ConsultantRepository.class);
  private final AccountInactivityService accounts = mock(AccountInactivityService.class);
  private final SessionCallAuthority authority =
      new SessionCallAuthority(sessions, consultants, accounts);

  private final Consultant assigned = consultant("counsellor", 7L);

  @BeforeEach
  void setUp() {
    var session = new Session();
    session.setId(45L);
    session.setTenantId(7L);
    session.setConsultant(assigned);
    when(sessions.findById(45L)).thenReturn(Optional.of(session));
    when(consultants.findByIdAndDeleteDateIsNull("counsellor")).thenReturn(Optional.of(assigned));
    when(accounts.snapshot(anyString())).thenReturn(Optional.empty());
  }

  @Test
  void theAssignedCounsellorWithAMatrixIdOfTheirOwnGrantsAuthority() {
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(COUNSELLOR))
        .thenReturn(List.of(assigned, consultant("elsewhere", 8L)));

    assertThat(authority.grantsGuestAuthority(binding())).isTrue();
  }

  @Test
  void anAssignedCounsellorWhoseMatrixIdAnotherAccountOfTheTraegerSharesGrantsNothing() {
    when(consultants.findAllByMatrixUserIdAndDeleteDateIsNull(COUNSELLOR))
        .thenReturn(List.of(assigned, consultant("twin", 7L)));

    assertThat(authority.grantsGuestAuthority(binding())).isFalse();
    assertThat(authority.isAssignedCounsellor(45L, 7L, COUNSELLOR)).isFalse();
  }

  @Test
  void theSessionOfASessionCallIsLockedFresh() {
    authority.lockSessionOf(binding());

    org.mockito.Mockito.verify(sessions).lockFresh(45L);
  }

  private static MatrixCallBinding binding() {
    return MatrixCallBinding.builder()
        .id(11L)
        .sessionId(45L)
        .tenantId(7L)
        .callerMatrixId(COUNSELLOR)
        .build();
  }

  private static Consultant consultant(String id, long tenant) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(COUNSELLOR);
    consultant.setTenantId(tenant);
    return consultant;
  }
}
