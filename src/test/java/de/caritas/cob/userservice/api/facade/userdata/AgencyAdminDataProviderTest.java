package de.caritas.cob.userservice.api.facade.userdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UserDataResponseDTO;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class AgencyAdminDataProviderTest {

  private static final String ADMIN_ID = "agency-admin-id";

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private KeycloakUserDataProvider keycloakUserDataProvider;
  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AgencyService agencyService;
  @InjectMocks private AgencyAdminDataProvider agencyAdminDataProvider;

  private UserDataResponseDTO keycloakData;

  @BeforeEach
  void setUp() {
    keycloakData =
        UserDataResponseDTO.builder().userId(ADMIN_ID).agencies(new ArrayList<>()).build();
    when(authenticatedUser.getUserId()).thenReturn(ADMIN_ID);
    when(keycloakUserDataProvider.retrieveAuthenticatedUserData()).thenReturn(keycloakData);
  }

  @Test
  void retrieveData_Should_ResolveAssignedAgenciesFromAdminAgencyRelation() {
    when(adminAgencyRepository.findByAdminId(ADMIN_ID))
        .thenReturn(
            List.of(
                AdminAgency.builder().agencyId(3L).build(),
                AdminAgency.builder().agencyId(8L).build()));
    var agencies = List.of(new AgencyDTO().id(3L).name("A"), new AgencyDTO().id(8L).name("B"));
    when(agencyService.getAgencies(List.of(3L, 8L))).thenReturn(agencies);

    var result = agencyAdminDataProvider.retrieveData();

    assertThat(result).isSameAs(keycloakData);
    assertThat(result.getAgencies()).containsExactlyElementsOf(agencies);
  }

  @Test
  void retrieveData_Should_SkipNullAndDuplicateAgencyIds() {
    when(adminAgencyRepository.findByAdminId(ADMIN_ID))
        .thenReturn(
            List.of(
                AdminAgency.builder().agencyId(null).build(),
                AdminAgency.builder().agencyId(4L).build(),
                AdminAgency.builder().agencyId(4L).build()));
    when(agencyService.getAgencies(List.of(4L))).thenReturn(List.of(new AgencyDTO().id(4L)));

    var result = agencyAdminDataProvider.retrieveData();

    assertThat(result.getAgencies()).extracting(AgencyDTO::getId).containsExactly(4L);
  }

  @Test
  void retrieveData_Should_ReturnEmptyAgencies_When_AdminHasNoAssignment() {
    when(adminAgencyRepository.findByAdminId(ADMIN_ID)).thenReturn(List.of());

    var result = agencyAdminDataProvider.retrieveData();

    assertThat(result.getAgencies()).isEmpty();
    verify(agencyService, never()).getAgencies(anyList());
  }

  @Test
  void retrieveData_Should_DegradeToEmptyAgencies_When_AgencyServiceAnswersWithError() {
    when(adminAgencyRepository.findByAdminId(ADMIN_ID))
        .thenReturn(List.of(AdminAgency.builder().agencyId(3L).build()));
    when(agencyService.getAgencies(List.of(3L)))
        .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

    var result = agencyAdminDataProvider.retrieveData();

    assertThat(result.getAgencies()).isEmpty();
  }

  @Test
  void retrieveData_Should_DegradeToEmptyAgencies_When_AgencyServiceIsUnreachable() {
    when(adminAgencyRepository.findByAdminId(ADMIN_ID))
        .thenReturn(List.of(AdminAgency.builder().agencyId(3L).build()));
    when(agencyService.getAgencies(List.of(3L)))
        .thenThrow(new ResourceAccessException("connection refused"));

    var result = agencyAdminDataProvider.retrieveData();

    assertThat(result.getAgencies()).isEmpty();
  }
}
