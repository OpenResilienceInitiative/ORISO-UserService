package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgencyAdminContactServiceTest {

  private static final long AGENCY = 7L;
  private static final String CALLER = "caller-1";
  private static final String MATRIX_OK = "@admin:matrix.example.org";

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private ConsultantRepository consultantRepository;

  @InjectMocks private AgencyAdminContactService service;

  @Test
  void callerOutsideTheCentreIsRefusedAndNothingIsLookedUp() {
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CALLER, AGENCY))
        .thenReturn(false);

    assertThatThrownBy(() -> service.contactsFor(CALLER, AGENCY))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(adminAgencyRepository, consultantRepository);
  }

  @Test
  void missingCallerOrAgencyIsRefused() {
    assertThatThrownBy(() -> service.contactsFor(null, AGENCY))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.contactsFor(CALLER, null))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(consultantAgencyRepository, adminAgencyRepository);
  }

  @Test
  void returnsEveryReachableAdminWithIdAndDisplayNameOnly() {
    callerBelongs();
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY))
        .thenReturn(List.of("admin-1", "admin-2"));
    when(consultantRepository.findByIdAndDeleteDateIsNull("admin-1"))
        .thenReturn(Optional.of(consultant("admin-1", "Maria", "Muster", MATRIX_OK, AGENCY)));
    when(consultantRepository.findByIdAndDeleteDateIsNull("admin-2"))
        .thenReturn(Optional.of(consultant("admin-2", "Ali", "Beispiel", MATRIX_OK, AGENCY)));

    var result = service.contactsFor(CALLER, AGENCY);

    assertThat(result)
        .extracting("consultantId", "displayName")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("admin-1", "Maria Muster"),
            org.assertj.core.groups.Tuple.tuple("admin-2", "Ali Beispiel"));
  }

  @Test
  void skipsAdminsThatCannotBeInvited() {
    callerBelongs();
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY))
        .thenReturn(List.of("no-consultant", "no-matrix", "other-centre", CALLER, "ok"));
    when(consultantRepository.findByIdAndDeleteDateIsNull("no-consultant"))
        .thenReturn(Optional.empty());
    when(consultantRepository.findByIdAndDeleteDateIsNull("no-matrix"))
        .thenReturn(Optional.of(consultant("no-matrix", "A", "B", null, AGENCY)));
    when(consultantRepository.findByIdAndDeleteDateIsNull("other-centre"))
        .thenReturn(Optional.of(consultant("other-centre", "C", "D", MATRIX_OK, 99L)));
    when(consultantRepository.findByIdAndDeleteDateIsNull("ok"))
        .thenReturn(Optional.of(consultant("ok", "E", "F", MATRIX_OK, AGENCY)));

    var result = service.contactsFor(CALLER, AGENCY);

    assertThat(result).extracting("consultantId").containsExactly("ok");
    verify(consultantRepository, never()).findByIdAndDeleteDateIsNull(CALLER);
  }

  @Test
  void noAdminsGivesAnEmptyListNotAnError() {
    callerBelongs();
    when(adminAgencyRepository.findAgencyAdminIdsByAgencyId(AGENCY)).thenReturn(List.of());

    assertThat(service.contactsFor(CALLER, AGENCY)).isEmpty();
    verify(consultantRepository, never()).findByIdAndDeleteDateIsNull(any());
  }

  private void callerBelongs() {
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CALLER, AGENCY))
        .thenReturn(true);
  }

  private static Consultant consultant(
      String id, String first, String last, String matrixUserId, long agencyId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setFirstName(first);
    consultant.setLastName(last);
    consultant.setMatrixUserId(matrixUserId);
    consultant.setConsultantAgencies(
        Set.of(ConsultantAgency.builder().agencyId(agencyId).consultant(consultant).build()));
    return consultant;
  }
}
