package de.caritas.cob.userservice.api.admin.service.admin.create.agencyrelation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.admin.service.admin.search.RetrieveAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.NoContentException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CreateAdminAgencyRelationServiceTest {

  @Mock private RetrieveAdminService retrieveAdminService;
  @Mock private AgencyService agencyService;
  @Mock private AdminAgencyRepository adminAgencyRepository;

  @InjectMocks private CreateAdminAgencyRelationService service;

  // ─── Happy path ───────────────────────────────────────────────────────────

  @Test
  void create_Should_SaveAdminAgency_When_AdminAndAgencyExist() {
    var admin = buildAdmin("admin-1");
    var agency = new AgencyDTO().id(5L);
    var dto = buildRelationDTO(5L);

    when(retrieveAdminService.findAdmin("admin-1", AdminType.AGENCY)).thenReturn(admin);
    when(agencyService.getAgencyWithoutCaching(5L)).thenReturn(agency);

    service.create("admin-1", dto);

    verify(adminAgencyRepository).save(any(AdminAgency.class));
  }

  @Test
  void create_Should_SaveWithCorrectAgencyId_When_Called() {
    var admin = buildAdmin("admin-2");
    var agency = new AgencyDTO().id(99L);
    var dto = buildRelationDTO(99L);

    when(retrieveAdminService.findAdmin("admin-2", AdminType.AGENCY)).thenReturn(admin);
    when(agencyService.getAgencyWithoutCaching(99L)).thenReturn(agency);

    service.create("admin-2", dto);

    ArgumentCaptor<AdminAgency> captor = ArgumentCaptor.forClass(AdminAgency.class);
    verify(adminAgencyRepository).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getAgencyId()).isEqualTo(99L);
  }

  @Test
  void create_Should_SaveWithCorrectAdmin_When_Called() {
    var admin = buildAdmin("admin-3");
    var agency = new AgencyDTO().id(7L);
    var dto = buildRelationDTO(7L);

    when(retrieveAdminService.findAdmin("admin-3", AdminType.AGENCY)).thenReturn(admin);
    when(agencyService.getAgencyWithoutCaching(7L)).thenReturn(agency);

    service.create("admin-3", dto);

    ArgumentCaptor<AdminAgency> captor = ArgumentCaptor.forClass(AdminAgency.class);
    verify(adminAgencyRepository).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getAdmin()).isEqualTo(admin);
  }

  @Test
  void create_Should_SetCreateAndUpdateDates_When_Saving() {
    var admin = buildAdmin("admin-4");
    var agency = new AgencyDTO().id(1L);
    var dto = buildRelationDTO(1L);

    when(retrieveAdminService.findAdmin("admin-4", AdminType.AGENCY)).thenReturn(admin);
    when(agencyService.getAgencyWithoutCaching(1L)).thenReturn(agency);

    service.create("admin-4", dto);

    ArgumentCaptor<AdminAgency> captor = ArgumentCaptor.forClass(AdminAgency.class);
    verify(adminAgencyRepository).save(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getCreateDate()).isNotNull();
    org.assertj.core.api.Assertions.assertThat(captor.getValue().getUpdateDate()).isNotNull();
  }

  // ─── Agency not found ─────────────────────────────────────────────────────

  @Test
  void create_Should_ThrowBadRequestException_When_AgencyNotFound() {
    var admin = buildAdmin("admin-5");
    var dto = buildRelationDTO(999L);

    when(retrieveAdminService.findAdmin("admin-5", AdminType.AGENCY)).thenReturn(admin);
    when(agencyService.getAgencyWithoutCaching(999L)).thenReturn(null);

    assertThatThrownBy(() -> service.create("admin-5", dto))
        .isInstanceOf(BadRequestException.class);
    verify(adminAgencyRepository, never()).save(any());
  }

  // ─── Admin not found (delegated to RetrieveAdminService) ─────────────────

  @Test
  void create_Should_PropagateException_When_AdminNotFound() {
    var dto = buildRelationDTO(5L);

    when(retrieveAdminService.findAdmin("missing-admin", AdminType.AGENCY))
        .thenThrow(new NoContentException("not found"));

    assertThatThrownBy(() -> service.create("missing-admin", dto))
        .isInstanceOf(NoContentException.class);
    verify(adminAgencyRepository, never()).save(any());
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private Admin buildAdmin(String id) {
    return Admin.builder()
        .id(id)
        .username("user-" + id)
        .firstName("First")
        .lastName("Last")
        .email(id + "@test.de")
        .type(AdminType.AGENCY)
        .build();
  }

  private de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO
      buildRelationDTO(Long agencyId) {
    var dto = new de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO();
    dto.setAgencyId(agencyId);
    return dto;
  }
}
