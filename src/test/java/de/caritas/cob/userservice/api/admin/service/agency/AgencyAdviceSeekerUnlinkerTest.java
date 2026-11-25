package de.caritas.cob.userservice.api.admin.service.agency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.Gone;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.Kept;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdviceSeekerUnlinker.OnlyHere;
import de.caritas.cob.userservice.api.model.User;
import de.caritas.cob.userservice.api.port.out.SessionRepository;
import de.caritas.cob.userservice.api.port.out.UserAgencyRepository;
import de.caritas.cob.userservice.api.port.out.UserRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class AgencyAdviceSeekerUnlinkerTest {

  private static final Long AGENCY = 7L;
  private static final String USER = "asker";
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 24, 10, 0);

  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserAgencyRepository userAgencyRepository;
  @Mock private PlatformTransactionManager transactionManager;

  private AgencyAdviceSeekerUnlinker unlinker;

  @BeforeEach
  void setUp() {
    unlinker =
        new AgencyAdviceSeekerUnlinker(
            userRepository, sessionRepository, userAgencyRepository, transactionManager);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void unlink_Should_takeSessionsAndMembershipHere_underTheRowLock_When_linkedElsewhere() {
    when(userRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(new User()));
    when(sessionRepository.hasActiveSessionOutsideAgency(USER, AGENCY)).thenReturn(false);
    when(userAgencyRepository.hasMembershipOutsideAgency(USER, AGENCY)).thenReturn(true);
    when(sessionRepository.markSessionsOfUserInAgencyForDeletion(USER, AGENCY, NOW)).thenReturn(2);

    var outcome = unlinker.unlink(USER, AGENCY, NOW);

    assertThat(outcome).isEqualTo(new Kept(2));
    var order =
        inOrder(transactionManager, userRepository, sessionRepository, userAgencyRepository);
    order.verify(transactionManager).getTransaction(any());
    order.verify(userRepository).findByUserIdForUpdate(USER);
    order.verify(sessionRepository).markSessionsOfUserInAgencyForDeletion(USER, AGENCY, NOW);
    order.verify(userAgencyRepository).deleteMembershipsOfUserInAgency(USER, AGENCY);
    order.verify(transactionManager).commit(any());
  }

  @Test
  void unlink_Should_takeNothing_When_theAdviceSeekerBelongsOnlyHere() {
    when(userRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(new User()));
    when(sessionRepository.hasActiveSessionOutsideAgency(USER, AGENCY)).thenReturn(false);
    when(userAgencyRepository.hasMembershipOutsideAgency(USER, AGENCY)).thenReturn(false);

    var outcome = unlinker.unlink(USER, AGENCY, NOW);

    // Their link here is what lets the next call find them if marking the account fails.
    assertThat(outcome).isInstanceOf(OnlyHere.class);
    verify(sessionRepository, never()).markSessionsOfUserInAgencyForDeletion(any(), any(), any());
    verify(userAgencyRepository, never()).deleteMembershipsOfUserInAgency(any(), any());
  }

  @Test
  void unlink_Should_doNothing_When_theAdviceSeekerIsAlreadyMarked() {
    var marked = new User();
    marked.setDeleteDate(NOW.minusDays(1));
    when(userRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.of(marked));

    assertThat(unlinker.unlink(USER, AGENCY, NOW)).isInstanceOf(Gone.class);
    verify(sessionRepository, never()).markSessionsOfUserInAgencyForDeletion(any(), any(), any());
  }

  @Test
  void unlink_Should_doNothing_When_theAdviceSeekerIsGone() {
    when(userRepository.findByUserIdForUpdate(USER)).thenReturn(Optional.empty());

    assertThat(unlinker.unlink(USER, AGENCY, NOW)).isInstanceOf(Gone.class);
    verify(userAgencyRepository, never()).deleteMembershipsOfUserInAgency(any(), any());
  }
}
