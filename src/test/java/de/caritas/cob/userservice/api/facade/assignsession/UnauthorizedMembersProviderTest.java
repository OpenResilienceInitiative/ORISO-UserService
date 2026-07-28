package de.caritas.cob.userservice.api.facade.assignsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.Session;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnauthorizedMembersProviderTest {

  @InjectMocks UnauthorizedMembersProvider provider;
  @Mock ConsultantService consultantService;
  @Mock Session session;
  @Mock User user;
  @Mock Consultant assignedConsultant;
  @Mock Consultant retainedConsultant;
  @Mock Consultant obsoleteConsultant;

  @BeforeEach
  void setUp() {
    lenient().when(session.getUser()).thenReturn(user);
    lenient().when(user.getMatrixUserId()).thenReturn("@user:matrix.example");
    lenient().when(assignedConsultant.getMatrixUserId()).thenReturn("@assigned:matrix.example");
    lenient().when(retainedConsultant.getMatrixUserId()).thenReturn("@retained:matrix.example");
  }

  @Test
  void returnsOnlyKnownConsultantsWhoAreNoLongerAuthorized() {
    when(consultantService.getConsultantByMatrixUserId("@obsolete:matrix.example"))
        .thenReturn(Optional.of(obsoleteConsultant));

    var result =
        provider.obtainConsultantsToRemove(
            "!room:matrix.example",
            session,
            assignedConsultant,
            List.of(
                "@user:matrix.example",
                "@assigned:matrix.example",
                "@obsolete:matrix.example",
                "@technical-admin:matrix.example"),
            retainedConsultant);

    assertThat(result).containsExactly(obsoleteConsultant);
  }

  @Test
  void keepsTheExplicitlyRetainedConsultant() {
    var result =
        provider.obtainConsultantsToRemove(
            "!room:matrix.example",
            session,
            assignedConsultant,
            List.of("@retained:matrix.example"),
            retainedConsultant);

    assertThat(result).isEmpty();
  }

  @Test
  void returnsEmptyWhenTheRoomHasNoMembers() {
    var result =
        provider.obtainConsultantsToRemove(
            "!room:matrix.example", session, assignedConsultant, List.of());

    assertThat(result).isEmpty();
  }
}
