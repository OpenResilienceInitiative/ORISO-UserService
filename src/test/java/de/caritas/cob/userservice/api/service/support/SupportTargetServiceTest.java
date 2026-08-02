package de.caritas.cob.userservice.api.service.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService;
import de.caritas.cob.userservice.api.admin.service.admin.GlobalSupportAdminUserService.SupportAdminNotOperationalException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAgency;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class SupportTargetServiceTest {

  private static final PageRequest PAGE = PageRequest.of(0, 20);

  @InjectMocks private SupportTargetService supportTargetService;

  @Mock private ConsultantAgencyRepository consultantAgencyRepository;
  @Mock private GlobalSupportAdminUserService globalSupportAdminUserService;

  @Test
  void search_Should_ReturnOneEntryPerConsultantAgencyPairWithMinimalData() {
    when(consultantAgencyRepository.findSupportTargets("mar", PAGE))
        .thenReturn(new PageImpl<>(List.of(assignment("c-1", 7L), assignment("c-1", 9L))));

    var result = supportTargetService.search("mar", PAGE);

    // Support is always requested for a consultant at one concrete agency, so the same person
    // appears once per assignment.
    assertThat(result.getContent()).hasSize(2);
    assertThat(result.getContent())
        .extracting(SupportTargetService.SupportTargetItem::getAgencyId)
        .containsExactly(7L, 9L);
    assertThat(result.getContent().get(0).getConsultantId()).isEqualTo("c-1");
    verify(globalSupportAdminUserService).requireOperationalSupportAdmin();
  }

  @Test
  void search_Should_BeUnreachableForANonOperationalSupportAdmin() {
    doThrow(new SupportAdminNotOperationalException("disabled"))
        .when(globalSupportAdminUserService)
        .requireOperationalSupportAdmin();

    assertThatThrownBy(() -> supportTargetService.search("mar", PAGE))
        .isInstanceOf(SupportAdminNotOperationalException.class);
    // The gate runs before any consultant data is read at all.
    verify(consultantAgencyRepository, never()).findSupportTargets(anyString(), any());
  }

  @Test
  void search_Should_TreatAMissingQueryAsAnEmptyInfix() {
    when(consultantAgencyRepository.findSupportTargets("", PAGE))
        .thenReturn(new PageImpl<>(List.of()));

    supportTargetService.search(null, PAGE);

    verify(consultantAgencyRepository).findSupportTargets("", PAGE);
  }

  private ConsultantAgency assignment(String consultantId, Long agencyId) {
    var consultant = new Consultant();
    consultant.setId(consultantId);
    consultant.setFirstName("Mara");
    consultant.setLastName("Muster");
    consultant.setEmail("mara@example.org");
    var consultantAgency = new ConsultantAgency();
    consultantAgency.setConsultant(consultant);
    consultantAgency.setAgencyId(agencyId);
    return consultantAgency;
  }
}
