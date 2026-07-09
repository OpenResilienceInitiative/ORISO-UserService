package de.caritas.cob.userservice.api.adapters.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.LanguageResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.ConsultantDtoMapper;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.in.AccountManaging;
import de.caritas.cob.userservice.api.service.ConsultantAgencyService;
import de.caritas.cob.userservice.api.service.ConsultantService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class UserConsultantControllerDelegateTest {

  private static final long AGENCY_ID = 42L;
  private static final String ADMIN_ID = "admin-id";
  private static final UUID CONSULTANT_ID = UUID.fromString("65c1095e-b977-493a-a34f-064b729d1d6c");

  @Mock private AuthenticatedUser authenticatedUser;
  @Mock private ConsultantAgencyService consultantAgencyService;
  @Mock private AccountManaging accountManager;
  @Mock private ConsultantDtoMapper consultantDtoMapper;
  @Mock private ConsultantService consultantService;
  @Mock private AdminUserFacade adminUserFacade;

  @InjectMocks private UserConsultantControllerDelegate delegate;

  @Test
  void getLanguagesShouldReturnMappedLanguages() {
    var languageCodes = Set.of("de", "en");
    var languageResponse = new LanguageResponseDTO();
    when(consultantAgencyService.getLanguageCodesOfAgency(AGENCY_ID)).thenReturn(languageCodes);
    when(consultantDtoMapper.languageResponseDtoOf(languageCodes)).thenReturn(languageResponse);

    var response = delegate.getLanguages(AGENCY_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(languageResponse);
  }

  @Test
  void getConsultantsShouldReturnOkWhenConsultantsExist() {
    var consultants = List.of(new ConsultantResponseDTO());
    when(consultantAgencyService.getConsultantsOfAgency(AGENCY_ID)).thenReturn(consultants);

    var response = delegate.getConsultants(AGENCY_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(consultants);
  }

  @Test
  void getConsultantsShouldReturnNoContentWhenConsultantsAreMissing() {
    when(consultantAgencyService.getConsultantsOfAgency(AGENCY_ID)).thenReturn(List.of());

    var response = delegate.getConsultants(AGENCY_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void searchConsultantsShouldFilterAgenciesForRestrictedAdmin() {
    var resultMap = Map.<String, Object>of("consultants", List.of(), "totalElements", 1);
    var searchResult =
        new ConsultantSearchResultDTO()
            .embedded(
                List.of(
                    new ConsultantAdminResponseDTO()
                        .embedded(
                            new ConsultantDTO()
                                .agencies(
                                    List.of(
                                        new AgencyAdminResponseDTO().id(1L),
                                        new AgencyAdminResponseDTO().id(2L))))));
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(true);
    when(authenticatedUser.getUserId()).thenReturn(ADMIN_ID);
    when(adminUserFacade.findAdminUserAgencyIds(ADMIN_ID)).thenReturn(List.of(1L));
    when(consultantDtoMapper.mappedFieldOf("LASTNAME")).thenReturn("lastName");
    when(accountManager.findConsultantsByInfix(
            "person@example.org", true, List.of(1L), 0, 20, "lastName", true))
        .thenReturn(resultMap);
    when(consultantDtoMapper.consultantSearchResultOf(
            resultMap, "person%40example.org", 1, 20, "LASTNAME", "asc"))
        .thenReturn(searchResult);

    var response = delegate.searchConsultants("person%40example.org", 1, 20, "LASTNAME", "asc");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(searchResult);
    assertThat(searchResult.getEmbedded().get(0).getEmbedded().getAgencies())
        .extracting(AgencyAdminResponseDTO::getId)
        .containsExactly(1L);
  }

  @Test
  void searchConsultants_nonRestrictedAdmin_noAgencyFilteringApplied() {
    // Non-restricted admins see all agencies attached to each consultant result.
    var resultMap = Map.<String, Object>of("consultants", List.of(), "totalElements", 1);
    var searchResult =
        new ConsultantSearchResultDTO()
            .embedded(
                List.of(
                    new ConsultantAdminResponseDTO()
                        .embedded(
                            new ConsultantDTO()
                                .agencies(
                                    List.of(
                                        new AgencyAdminResponseDTO().id(1L),
                                        new AgencyAdminResponseDTO().id(2L))))));
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(consultantDtoMapper.mappedFieldOf("LASTNAME")).thenReturn("lastName");
    when(accountManager.findConsultantsByInfix("smith", false, List.of(), 0, 20, "lastName", true))
        .thenReturn(resultMap);
    when(consultantDtoMapper.consultantSearchResultOf(resultMap, "smith", 1, 20, "LASTNAME", "asc"))
        .thenReturn(searchResult);

    var response = delegate.searchConsultants("smith", 1, 20, "LASTNAME", "asc");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(searchResult.getEmbedded().get(0).getEmbedded().getAgencies())
        .extracting(AgencyAdminResponseDTO::getId)
        .containsExactly(1L, 2L);
    verifyNoInteractions(adminUserFacade);
  }

  @Test
  void searchConsultants_nonEmailQuery_urlDecodePathUsed() {
    // Non-email search queries are URL-decoded before hitting the account manager.
    var resultMap = Map.<String, Object>of("consultants", List.of(), "totalElements", 0);
    var searchResult = new ConsultantSearchResultDTO();
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(consultantDtoMapper.mappedFieldOf("FIRSTNAME")).thenReturn("firstName");
    when(accountManager.findConsultantsByInfix(
            "Müller", false, List.of(), 0, 20, "firstName", true))
        .thenReturn(resultMap);
    when(consultantDtoMapper.consultantSearchResultOf(
            resultMap, "M%C3%BCller", 1, 20, "FIRSTNAME", "asc"))
        .thenReturn(searchResult);

    var response = delegate.searchConsultants("M%C3%BCller", 1, 20, "FIRSTNAME", "asc");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    verify(accountManager)
        .findConsultantsByInfix("Müller", false, List.of(), 0, 20, "firstName", true);
  }

  @Test
  void searchConsultants_orderDesc_isAscendingFalsePassedToService() {
    // Descending sort order is forwarded as isAscending=false to the search service.
    var resultMap = Map.<String, Object>of("consultants", List.of(), "totalElements", 0);
    var searchResult = new ConsultantSearchResultDTO();
    when(authenticatedUser.hasRestrictedAgencyPriviliges()).thenReturn(false);
    when(consultantDtoMapper.mappedFieldOf("LASTNAME")).thenReturn("lastName");
    when(accountManager.findConsultantsByInfix("smith", false, List.of(), 0, 20, "lastName", false))
        .thenReturn(resultMap);
    when(consultantDtoMapper.consultantSearchResultOf(
            resultMap, "smith", 1, 20, "LASTNAME", "desc"))
        .thenReturn(searchResult);

    var response = delegate.searchConsultants("smith", 1, 20, "LASTNAME", "desc");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    var isAscendingCaptor = ArgumentCaptor.forClass(Boolean.class);
    verify(accountManager)
        .findConsultantsByInfix(
            eq("smith"),
            eq(false),
            eq(List.of()),
            eq(0),
            eq(20),
            eq("lastName"),
            isAscendingCaptor.capture());
    assertThat(isAscendingCaptor.getValue()).isFalse();
  }

  @Test
  void getConsultantPublicDataShouldReturnMappedConsultant() {
    var consultant = new Consultant();
    var agencies = List.of(new AgencyDTO().id(AGENCY_ID));
    var consultantResponse = new ConsultantResponseDTO();
    when(consultantService.getConsultant(CONSULTANT_ID.toString()))
        .thenReturn(Optional.of(consultant));
    when(consultantAgencyService.getOnlineAgenciesOfConsultant(CONSULTANT_ID.toString()))
        .thenReturn(agencies);
    when(consultantDtoMapper.consultantResponseDtoOf(consultant, agencies, false))
        .thenReturn(consultantResponse);

    var response = delegate.getConsultantPublicData(CONSULTANT_ID);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isSameAs(consultantResponse);
  }

  @Test
  void getConsultantPublicDataShouldThrowNotFoundWhenConsultantDoesNotExist() {
    when(consultantService.getConsultant(CONSULTANT_ID.toString())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> delegate.getConsultantPublicData(CONSULTANT_ID))
        .isInstanceOf(NotFoundException.class);
  }
}
