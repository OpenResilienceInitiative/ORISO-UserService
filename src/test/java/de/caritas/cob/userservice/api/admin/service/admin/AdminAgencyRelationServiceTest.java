package de.caritas.cob.userservice.api.admin.service.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.agencyadminserivce.generated.web.model.AgencyAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminAgencyRelationDTO;
import de.caritas.cob.userservice.api.admin.service.admin.create.agencyrelation.CreateAdminAgencyRelationService;
import de.caritas.cob.userservice.api.admin.service.admin.update.agencyrelation.SynchronizeAdminAgencyRelation;
import de.caritas.cob.userservice.api.admin.service.agency.AgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.AdminAgency;
import de.caritas.cob.userservice.api.model.AdminAgency.AdminAgencyBase;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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
class AdminAgencyRelationServiceTest {

  @Mock private AdminAgencyRepository adminAgencyRepository;
  @Mock private AgencyAdminService agencyAdminService;
  @Mock private CreateAdminAgencyRelationService createAdminAgencyRelationService;
  @Mock private SynchronizeAdminAgencyRelation synchronizeAdminAgencyRelation;

  @InjectMocks private AdminAgencyRelationService service;

  @Mock private AdminCallerScope adminCallerScope;

  // ─── createAdminAgencyRelation ────────────────────────────────────────────

  @Test
  void createAdminAgencyRelation_Should_DelegateToCreateService() {
    var dto = new CreateAdminAgencyRelationDTO();

    service.createAdminAgencyRelation("admin1", dto);

    verify(createAdminAgencyRelationService).create("admin1", dto);
  }

  // ─── deleteAdminAgencyRelation ────────────────────────────────────────────

  @Test
  void deleteAdminAgencyRelation_Should_ThrowException_When_RelationNotFound() {
    when(adminAgencyRepository.findByAdminIdAndAgencyId("admin1", 99L)).thenReturn(List.of());

    assertThatThrownBy(() -> service.deleteAdminAgencyRelation("admin1", 99L))
        .isInstanceOf(CustomValidationHttpStatusException.class);

    verify(adminAgencyRepository, never()).deleteByAdminIdAndAgencyId(any(), any());
  }

  @Test
  void deleteAdminAgencyRelation_Should_Delete_When_RelationFound() {
    AdminAgency relation = new AdminAgency();
    when(adminAgencyRepository.findByAdminIdAndAgencyId("admin1", 5L))
        .thenReturn(List.of(relation));

    service.deleteAdminAgencyRelation("admin1", 5L);

    verify(adminAgencyRepository).deleteByAdminIdAndAgencyId("admin1", 5L);
  }

  // ─── synchronizeAdminAgenciesRelation ────────────────────────────────────

  @Test
  void synchronizeAdminAgenciesRelation_Should_DelegateToSynchronizeService() {
    var dtos = List.of(new CreateAdminAgencyRelationDTO());

    service.synchronizeAdminAgenciesRelation("admin1", dtos);

    verify(synchronizeAdminAgencyRelation).synchronizeAdminAgenciesRelation("admin1", dtos);
  }

  // ─── caller scope ─────────────────────────────────────────────────────────

  @Test
  void createAdminAgencyRelation_Should_NotCreate_When_ScopeDenies() {
    var dto = new CreateAdminAgencyRelationDTO().agencyId(5L);
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(List.of(5L));

    assertThatThrownBy(() -> service.createAdminAgencyRelation("admin1", dto))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(createAdminAgencyRelationService);
  }

  @Test
  void deleteAdminAgencyRelation_Should_NotDelete_When_ScopeDenies() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayActOnAdmin("admin1");

    assertThatThrownBy(() -> service.deleteAdminAgencyRelation("admin1", 5L))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(adminAgencyRepository);
  }

  @Test
  void synchronizeAdminAgenciesRelation_Should_NotSynchronize_When_ScopeDenies() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(any());

    assertThatThrownBy(
            () -> service.synchronizeAdminAgenciesRelation("admin1", relationsTo(5L, 6L)))
        .isInstanceOf(ForbiddenException.class);

    verifyNoInteractions(synchronizeAdminAgencyRelation);
  }

  @Test
  void synchronizeAdminAgenciesRelation_Should_CheckOnlyRemovedAgencies_When_AgenciesRemoved() {
    givenExistingAgencies(5L, 6L);

    service.synchronizeAdminAgenciesRelation("admin1", relationsTo(5L));

    assertThat(checkedAgencies()).containsExactly(6L);
  }

  @Test
  void synchronizeAdminAgenciesRelation_Should_CheckNothing_When_AgenciesUnchanged() {
    givenExistingAgencies(5L, 6L);

    service.synchronizeAdminAgenciesRelation("admin1", relationsTo(6L, 5L));

    assertThat(checkedAgencies()).isEmpty();
  }

  @Test
  void synchronizeAdminAgenciesRelation_Should_CheckAllExistingAgencies_When_ListIsNull() {
    givenExistingAgencies(5L, 6L);

    service.synchronizeAdminAgenciesRelation("admin1", null);

    assertThat(checkedAgencies()).containsExactlyInAnyOrder(5L, 6L);
  }

  @Test
  void synchronizeAdminAgenciesRelation_Should_CheckAddedAndRemovedAgencies_When_Mixed() {
    givenExistingAgencies(5L, 6L);

    service.synchronizeAdminAgenciesRelation("admin1", relationsTo(6L, 7L));

    assertThat(checkedAgencies()).containsExactlyInAnyOrder(5L, 7L);
  }

  // ─── appendAgenciesForAdmins ──────────────────────────────────────────────

  @Test
  void appendAgenciesForAdmins_Should_DoNothing_When_AdminsSetIsEmpty() {
    when(adminAgencyRepository.findByAdminIdIn(any())).thenReturn(List.of());
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of());

    service.appendAgenciesForAdmins(Set.of());

    verify(adminAgencyRepository).findByAdminIdIn(Set.of());
  }

  @Test
  void appendAgenciesForAdmins_Should_SetAgencies_When_AdminHasMatchingAgency() {
    AdminDTO admin = buildAdmin("admin1");

    AdminAgencyBase rel = mock(AdminAgencyBase.class);
    when(rel.getAdminId()).thenReturn("admin1");
    when(rel.getAgencyId()).thenReturn(10L);

    AgencyAdminResponseDTO generatedAgency = new AgencyAdminResponseDTO();
    generatedAgency.setId(10L);
    generatedAgency.setName("Test Agency");

    when(adminAgencyRepository.findByAdminIdIn(Set.of("admin1"))).thenReturn(List.of(rel));
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of(generatedAgency));

    service.appendAgenciesForAdmins(Set.of(admin));

    assertThat(admin.getAgencies()).hasSize(1);
    assertThat(admin.getAgencies().get(0).getId()).isEqualTo(10L);
    assertThat(admin.getAgencies().get(0).getName()).isEqualTo("Test Agency");
  }

  @Test
  void appendAgenciesForAdmins_Should_SetEmptyAgencies_When_NoAgencyMatchesAdmin() {
    AdminDTO admin = buildAdmin("admin1");

    AdminAgencyBase rel = mock(AdminAgencyBase.class);
    when(rel.getAdminId()).thenReturn("admin2"); // different admin
    when(rel.getAgencyId()).thenReturn(10L);

    AgencyAdminResponseDTO generatedAgency = new AgencyAdminResponseDTO();
    generatedAgency.setId(10L);

    when(adminAgencyRepository.findByAdminIdIn(Set.of("admin1"))).thenReturn(List.of(rel));
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of(generatedAgency));

    service.appendAgenciesForAdmins(Set.of(admin));

    assertThat(admin.getAgencies()).isEmpty();
  }

  @Test
  void appendAgenciesForAdmins_Should_SetEmptyAgencies_When_NoAdminAgencyRelationsExist() {
    AdminDTO admin = buildAdmin("admin1");

    when(adminAgencyRepository.findByAdminIdIn(Set.of("admin1"))).thenReturn(List.of());
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of());

    service.appendAgenciesForAdmins(Set.of(admin));

    assertThat(admin.getAgencies()).isEmpty();
  }

  @Test
  void appendAgenciesForAdmins_Should_ResolveAgenciesPerAdmin_When_MultipleAdmins() {
    AdminDTO admin1 = buildAdmin("admin1");
    AdminDTO admin2 = buildAdmin("admin2");

    AdminAgencyBase rel1 = mock(AdminAgencyBase.class);
    when(rel1.getAdminId()).thenReturn("admin1");
    when(rel1.getAgencyId()).thenReturn(10L);

    AdminAgencyBase rel2 = mock(AdminAgencyBase.class);
    when(rel2.getAdminId()).thenReturn("admin2");
    when(rel2.getAgencyId()).thenReturn(20L);

    AgencyAdminResponseDTO agency10 = new AgencyAdminResponseDTO();
    agency10.setId(10L);
    AgencyAdminResponseDTO agency20 = new AgencyAdminResponseDTO();
    agency20.setId(20L);

    when(adminAgencyRepository.findByAdminIdIn(any())).thenReturn(List.of(rel1, rel2));
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of(agency10, agency20));

    service.appendAgenciesForAdmins(Set.of(admin1, admin2));

    assertThat(admin1.getAgencies()).hasSize(1);
    assertThat(admin1.getAgencies().get(0).getId()).isEqualTo(10L);
    assertThat(admin2.getAgencies()).hasSize(1);
    assertThat(admin2.getAgencies().get(0).getId()).isEqualTo(20L);
  }

  @Test
  void appendAgenciesForAdmins_Should_NotSetAgency_When_AgencyIdDoesNotMatchAnyFromService() {
    AdminDTO admin = buildAdmin("admin1");

    AdminAgencyBase rel = mock(AdminAgencyBase.class);
    when(rel.getAdminId()).thenReturn("admin1");
    when(rel.getAgencyId()).thenReturn(99L); // ID not in returned agencies

    AgencyAdminResponseDTO agency = new AgencyAdminResponseDTO();
    agency.setId(10L); // different ID

    when(adminAgencyRepository.findByAdminIdIn(Set.of("admin1"))).thenReturn(List.of(rel));
    when(agencyAdminService.retrieveAllAgencies()).thenReturn(List.of(agency));

    service.appendAgenciesForAdmins(Set.of(admin));

    assertThat(admin.getAgencies()).isEmpty();
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private void givenExistingAgencies(Long... agencyIds) {
    when(adminAgencyRepository.findByAdminId("admin1"))
        .thenReturn(
            Arrays.stream(agencyIds)
                .map(id -> AdminAgency.builder().agencyId(id).build())
                .toList());
  }

  private static List<CreateAdminAgencyRelationDTO> relationsTo(Long... agencyIds) {
    return Arrays.stream(agencyIds)
        .map(id -> new CreateAdminAgencyRelationDTO().agencyId(id))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private Collection<Long> checkedAgencies() {
    ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(adminCallerScope).assertMayUseAgencies(captor.capture());
    return captor.getValue();
  }

  private AdminDTO buildAdmin(String id) {
    AdminDTO admin = new AdminDTO();
    admin.setId(id);
    return admin;
  }
}
