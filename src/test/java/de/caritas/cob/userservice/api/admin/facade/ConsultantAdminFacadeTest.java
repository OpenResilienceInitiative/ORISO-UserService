package de.caritas.cob.userservice.api.admin.facade;

import static de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO.AgencyTypeEnum.DEFAULT_AGENCY;
import static de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO.AgencyTypeEnum.TEAM_AGENCY;
import static de.caritas.cob.userservice.api.exception.httpresponses.customheader.HttpStatusExceptionReason.CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.Lists;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminFullResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyTypeDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantAgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.FieldEnum;
import de.caritas.cob.userservice.api.admin.service.admin.AdminCallerScope;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminFilterService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.agencyrelation.ConsultantAgencyRelationCreatorService;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.CustomValidationHttpStatusException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ConsultantAdminFacadeTest {

  public static final Long AGENCY_ID_1 = 1L;
  public static final Long AGENCY_ID_2 = 2L;
  @InjectMocks private ConsultantAdminFacade consultantAdminFacade;

  @Mock private ConsultantAdminService consultantAdminService;

  @Mock
  private de.caritas.cob.userservice.api.service.consultant.ConsultantChatIdentityService
      consultantChatIdentityService;

  @Mock private ConsultantAdminFilterService consultantAdminFilterService;

  @Mock private ConsultantAgencyAdminService consultantAgencyAdminService;

  @Mock private ConsultantAgencyRelationCreatorService relationCreatorService;

  @Mock private AuthenticatedUser authenticatedUser;

  @Mock private AdminUserFacade adminUserFacade;

  @Mock private AgencyService agencyService;

  @Mock private AdminCallerScope adminCallerScope;

  @Test
  void findConsultant_Should_useConsultantAdminService() {
    this.consultantAdminFacade.findConsultant("");

    verify(this.consultantAdminService).findConsultantById(any());
  }

  @Test
  void findFilteredConsultants_Should_useConsultantAdminFilterService() {
    this.consultantAdminFacade.findFilteredConsultants(
        1, 1, new ConsultantFilter(), new Sort().field(FieldEnum.EMAIL));

    verify(this.consultantAdminFilterService).findFilteredConsultants(eq(1), eq(1), any(), any());
  }

  @Test
  void findFilteredConsultants_Should_useAgencyScopedQuery_When_callerIsRestrictedToAgencies() {
    when(adminCallerScope.agencyRestriction()).thenReturn(Optional.of(Set.of(1L)));

    consultantAdminFacade.findFilteredConsultants(1, 1, new ConsultantFilter(), null);

    verify(consultantAdminFilterService)
        .findFilteredConsultants(eq(1), eq(1), any(), any(), eq(Set.of(1L)));
    verify(consultantAdminFilterService, never())
        .findFilteredConsultants(any(), any(), any(), any());
  }

  @Test
  void updateConsultant_Should_notUpdate_When_counsellorIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayActOnConsultant("c-1");

    assertThrows(
        ForbiddenException.class, () -> consultantAdminFacade.updateConsultant("c-1", null));

    verifyNoInteractions(consultantAdminService);
  }

  @Test
  void markConsultantAgencyForDeletion_Should_notDelete_When_agencyIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(List.of(1L));

    assertThrows(
        ForbiddenException.class,
        () -> consultantAdminFacade.markConsultantAgencyForDeletion("c-1", 1L));

    verifyNoInteractions(consultantAgencyAdminService);
  }

  @Test
  void findConsultantAgencies_Should_useConsultantAdminFilterService() {
    var consultantId = "1da238c6-cd46-4162-80f1-bff74eafeAAA";

    this.consultantAgencyAdminService.findConsultantAgencies(consultantId);

    verify(this.consultantAgencyAdminService).findConsultantAgencies(consultantId);
  }

  @Test
  void createNewConsultant_Should_useConsultantAdminServiceCorrectly() {
    this.consultantAdminFacade.createNewConsultant(null);

    verify(this.consultantAdminService).createNewConsultant(null);
  }

  @Test
  void createNewConsultant_Should_RejectUnauthorizedAgenciesBeforeCreatingIdentity() {
    CreateConsultantDTO dto = new CreateConsultantDTO().agencyIds(List.of(2L));
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(List.of(2L));

    assertThrows(ForbiddenException.class, () -> consultantAdminFacade.createNewConsultant(dto));

    verify(consultantAdminService, never()).createNewConsultant(any());
  }

  @Test
  void findConsultantAgencies_Should_useConsultantAgencyAdminServiceCorrectly() {
    this.consultantAdminFacade.findConsultantAgencies(null);

    verify(this.consultantAgencyAdminService).findConsultantAgencies(null);
  }

  @Test
  void createNewConsultantAgency_Should_useConsultantAgencyAdminServiceCorrectly() {
    var agency = new CreateConsultantAgencyDTO().agencyId(1L);

    this.consultantAdminFacade.createNewConsultantAgency("c-1", agency);

    verify(this.relationCreatorService).createNewConsultantAgency("c-1", agency);
  }

  @Test
  void createNewConsultantAgency_Should_notCreate_When_counsellorIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayActOnConsultant("c-1");

    assertThrows(
        ForbiddenException.class,
        () ->
            consultantAdminFacade.createNewConsultantAgency(
                "c-1", new CreateConsultantAgencyDTO().agencyId(1L)));

    verifyNoInteractions(relationCreatorService);
  }

  @Test
  void createNewConsultantAgency_Should_notCreate_When_agencyIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(List.of(1L));

    assertThrows(
        ForbiddenException.class,
        () ->
            consultantAdminFacade.createNewConsultantAgency(
                "c-1", new CreateConsultantAgencyDTO().agencyId(1L)));

    verifyNoInteractions(relationCreatorService);
  }

  @Test
  void repairConsultantChatIdentity_Should_notProvision_When_counsellorIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayActOnConsultant("c-1");

    assertThrows(
        ForbiddenException.class, () -> consultantAdminFacade.repairConsultantChatIdentity("c-1"));

    verifyNoInteractions(consultantChatIdentityService, consultantAdminService);
  }

  @Test
  void updateConsultant_Should_useConsultantAdminServiceCorrectly() {
    this.consultantAdminFacade.updateConsultant(null, null);

    verify(this.consultantAdminService).updateConsultant(any(), any());
  }

  @Test
  void
      changeAgencyType_Should_callMarkAllAssignedConsultantsAsTeamConsultant_When_typeIsTeamAgency() {
    this.consultantAdminFacade.changeAgencyType(1L, new AgencyTypeDTO().agencyType(TEAM_AGENCY));

    verify(this.consultantAgencyAdminService).markAllAssignedConsultantsAsTeamConsultant(1L);
  }

  @Test
  void
      changeAgencyType_Should_callRemoveConsultantsFromTeamSessionsByAgencyId_When_typeIsDefaultAgency() {
    this.consultantAdminFacade.changeAgencyType(1L, new AgencyTypeDTO().agencyType(DEFAULT_AGENCY));

    verify(this.consultantAgencyAdminService).removeConsultantsFromTeamSessionsByAgencyId(1L);
  }

  @Test
  void markConsultantAgencyForDeletion_Should_callMarkConsultantAgencyForDeletion() {
    this.consultantAdminFacade.markConsultantAgencyForDeletion("1", 1L);

    verify(this.consultantAgencyAdminService).markConsultantAgencyForDeletion("1", 1L);
  }

  @Test
  void markConsultantForDeletion_Should_callMarkConsultantForDeletion() {
    this.consultantAdminFacade.markConsultantForDeletion("1", true);

    verify(this.consultantAdminService).markConsultantForDeletion("1", true);
  }

  @Test
  void markConsultantForDeletion_Should_throwForbidden_When_counsellorIsOutOfScope() {
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayActOnConsultant("consultant-1");

    assertThrows(
        ForbiddenException.class,
        () -> this.consultantAdminFacade.markConsultantForDeletion("consultant-1", false));

    verify(this.consultantAdminService, never()).markConsultantForDeletion(any(), any());
  }

  @Test
  void findConsultantsForAgency_Should_callConsultantAgencyAdminService() {
    this.consultantAdminFacade.findConsultantsForAgency("1");

    verify(this.consultantAgencyAdminService).findConsultantsForAgency(1L);
  }

  @Test
  void
      checkAssignedAgenciesMatchConsultantTenant_Should_Throw_BadRequestException_When_TenantDoesNotMatch() {
    // given
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", true);
    ConsultantAdminResponseDTO consultant =
        new ConsultantAdminResponseDTO().embedded(new ConsultantDTO());
    consultant.getEmbedded().setTenantId(1);

    when(consultantAdminService.findConsultantById("consultantId")).thenReturn(consultant);

    List<CreateConsultantAgencyDTO> agencyList = new ArrayList<>();
    CreateConsultantAgencyDTO agency1 = new CreateConsultantAgencyDTO();
    agency1.setAgencyId(AGENCY_ID_1);
    agencyList.add(agency1);

    CreateConsultantAgencyDTO agency2 = new CreateConsultantAgencyDTO();
    agency2.setAgencyId(AGENCY_ID_2);
    agencyList.add(agency2);

    when(agencyService.getAgency(AGENCY_ID_1)).thenReturn(createAgencyWithTenant(1L));
    when(agencyService.getAgency(AGENCY_ID_2)).thenReturn(createAgencyWithTenant(2L));

    // when, then
    assertThrows(
        BadRequestException.class,
        () -> {
          consultantAdminFacade.checkAssignedAgenciesMatchConsultantTenant(
              "consultantId", agencyList);
        });

    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", false);
  }

  @Test
  void checkAssignedAgenciesMatchConsultantTenant_Should_PassCheck_When_TenantMatches() {
    // given
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", true);
    ConsultantAdminResponseDTO consultant =
        new ConsultantAdminResponseDTO().embedded(new ConsultantDTO());
    consultant.getEmbedded().setTenantId(1);

    when(consultantAdminService.findConsultantById("consultantId")).thenReturn(consultant);

    List<CreateConsultantAgencyDTO> agencyList = new ArrayList<>();
    CreateConsultantAgencyDTO agency1 = new CreateConsultantAgencyDTO();
    agency1.setAgencyId(AGENCY_ID_1);
    agencyList.add(agency1);

    CreateConsultantAgencyDTO agency2 = new CreateConsultantAgencyDTO();
    agency2.setAgencyId(AGENCY_ID_2);
    agencyList.add(agency2);

    when(agencyService.getAgency(AGENCY_ID_1)).thenReturn(createAgencyWithTenant(1L));
    when(agencyService.getAgency(AGENCY_ID_2)).thenReturn(createAgencyWithTenant(1L));

    // when
    consultantAdminFacade.checkAssignedAgenciesMatchConsultantTenant("consultantId", agencyList);

    // then
    Mockito.verify(agencyService, times(2)).getAgency(any());

    // tear down
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", false);
  }

  @Test
  void checkAssignedAgenciesMatchConsultantTenant_Should_PassCheck_When_MultitenancyIsDisabled() {
    // given
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", false);

    List<CreateConsultantAgencyDTO> agencyList = new ArrayList<>();
    CreateConsultantAgencyDTO agency1 = new CreateConsultantAgencyDTO();
    agency1.setAgencyId(AGENCY_ID_1);
    agencyList.add(agency1);

    CreateConsultantAgencyDTO agency2 = new CreateConsultantAgencyDTO();
    agency2.setAgencyId(AGENCY_ID_2);
    agencyList.add(agency2);

    // when
    consultantAdminFacade.checkAssignedAgenciesMatchConsultantTenant("consultantId", agencyList);

    // then
    Mockito.verifyNoInteractions(agencyService);
  }

  private AgencyDTO createAgencyWithTenant(Long tenantId) {
    AgencyDTO agency = new AgencyDTO();
    agency.setTenantId(tenantId);
    return agency;
  }

  // ---------------------------------------------------------------------------
  // Extended coverage — 2026-07-06
  // ---------------------------------------------------------------------------

  @Test
  void markConsultantAgenciesForDeletion_Should_useConsultantAgencyAdminServiceCorrectly() {
    List<Long> agencyIds = Lists.newArrayList(1L, 2L);

    consultantAdminFacade.markConsultantAgenciesForDeletion("consultantId", agencyIds);

    verify(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion("consultantId", agencyIds);
  }

  @Test
  void pauseConsultantDeletion_Should_useConsultantAdminServiceCorrectly() {
    consultantAdminFacade.pauseConsultantDeletion("consultantId", "reason", 3, "admin");

    verify(consultantAdminService).pauseConsultantDeletion("consultantId", "reason", 3, "admin");
  }

  @Test
  void prepareConsultantAgencyRelation_Should_prepareEachAgencyInList() {
    List<CreateConsultantAgencyDTO> agencies =
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(1L),
            new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAdminFacade.prepareConsultantAgencyRelation("consultantId", agencies);

    verify(relationCreatorService, times(2)).prepareConsultantAgencyRelation(any());
  }

  @Test
  void completeConsultantAgencyAssigment_Should_completeEachAgencyInList() {
    List<CreateConsultantAgencyDTO> agencies =
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(1L),
            new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAdminFacade.completeConsultantAgencyAssigment("consultantId", agencies);

    verify(relationCreatorService, times(2)).completeConsultantAgencyAssigment(any(), any());
  }

  @Test
  void filterAgencyListForDeletion_Should_returnPersistedAgenciesNotInNewList() {
    ConsultantAgencyResponseDTO persisted = new ConsultantAgencyResponseDTO();
    persisted.setEmbedded(
        Lists.newArrayList(
            agencyAdminFullResponse(1L), agencyAdminFullResponse(2L), agencyAdminFullResponse(3L)));
    when(consultantAgencyAdminService.findConsultantAgencies("consultantId")).thenReturn(persisted);

    List<Long> result =
        consultantAdminFacade.filterAgencyListForDeletion(
            "consultantId", Lists.newArrayList(new CreateConsultantAgencyDTO().agencyId(1L)));

    assertThat(result, containsInAnyOrder(2L, 3L));
  }

  @Test
  void filterAgencyListForDeletion_Should_returnEmptyList_When_AllPersistedAgenciesAreInNewList() {
    ConsultantAgencyResponseDTO persisted = new ConsultantAgencyResponseDTO();
    persisted.setEmbedded(Lists.newArrayList(agencyAdminFullResponse(1L)));
    when(consultantAgencyAdminService.findConsultantAgencies("consultantId")).thenReturn(persisted);

    List<Long> result =
        consultantAdminFacade.filterAgencyListForDeletion(
            "consultantId", Lists.newArrayList(new CreateConsultantAgencyDTO().agencyId(1L)));

    assertThat(result, empty());
  }

  @Test
  void filterAgencyListForCreation_Should_removeAgenciesAlreadyPersisted() {
    ConsultantAgencyResponseDTO persisted = new ConsultantAgencyResponseDTO();
    persisted.setEmbedded(Lists.newArrayList(agencyAdminFullResponse(1L)));
    when(consultantAgencyAdminService.findConsultantAgencies("consultantId")).thenReturn(persisted);
    List<CreateConsultantAgencyDTO> newList =
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(1L),
            new CreateConsultantAgencyDTO().agencyId(2L));

    consultantAdminFacade.filterAgencyListForCreation("consultantId", newList);

    List<Long> remainingIds =
        newList.stream()
            .map(CreateConsultantAgencyDTO::getAgencyId)
            .collect(java.util.stream.Collectors.toList());
    assertThat(remainingIds, contains(2L));
  }

  @Test
  void setConsultantAgencies_Should_createMissingAndDeleteRemovedAndSkipExisting() {
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList(1L, 2L));
    // desired set: keep 1, drop 2, add 3
    List<CreateConsultantAgencyDTO> desired =
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(1L),
            new CreateConsultantAgencyDTO().agencyId(3L));

    consultantAdminFacade.setConsultantAgencies("consultantId", desired);

    verify(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion("consultantId", Lists.newArrayList(2L));
    verify(relationCreatorService)
        .createNewConsultantAgency(eq("consultantId"), argThatAgencyId(3L));
    verify(relationCreatorService, never())
        .createNewConsultantAgency(eq("consultantId"), argThatAgencyId(1L));
    verify(consultantAgencyAdminService, never()).findConsultantAgencies(any());
  }

  @Test
  void setConsultantAgencies_Should_notDelete_When_NothingRemoved() {
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList(1L));

    consultantAdminFacade.setConsultantAgencies(
        "consultantId",
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(1L),
            new CreateConsultantAgencyDTO().agencyId(2L)));

    verify(consultantAgencyAdminService, never())
        .markConsultantAgenciesForDeletion(any(), anyList());
    verify(relationCreatorService)
        .createNewConsultantAgency(eq("consultantId"), argThatAgencyId(2L));
    verify(consultantAgencyAdminService, never()).findConsultantAgencies(any());
  }

  @Test
  void setConsultantAgencies_Should_propagateValidationError() {
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList());
    doThrow(new BadRequestException("topic not covered"))
        .when(relationCreatorService)
        .createNewConsultantAgency(eq("consultantId"), any());

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAdminFacade.setConsultantAgencies(
                "consultantId", Lists.newArrayList(new CreateConsultantAgencyDTO().agencyId(9L))));
  }

  @Test
  void setConsultantAgencies_Should_notCreateAnything_When_ADeletionIsRejected() {
    // The deletion leg runs first. When it is rejected (last consultant of a still
    // active agency), no relation may be created either - a half-applied agency set makes the
    // following consultant update validate topics against agencies the admin did not select.
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList(1L, 2L));
    doThrow(
            new CustomValidationHttpStatusException(
                CONSULTANT_IS_THE_LAST_OF_AGENCY_AND_AGENCY_IS_STILL_ACTIVE))
        .when(consultantAgencyAdminService)
        .markConsultantAgenciesForDeletion(eq("consultantId"), anyList());

    assertThrows(
        CustomValidationHttpStatusException.class,
        () ->
            consultantAdminFacade.setConsultantAgencies(
                "consultantId", Lists.newArrayList(new CreateConsultantAgencyDTO().agencyId(3L))));

    verify(relationCreatorService, never()).createNewConsultantAgency(any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void setConsultantAgencies_Should_checkOnlyAddedAndRemovedAgencies() {
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList(1L, 2L));

    consultantAdminFacade.setConsultantAgencies(
        "consultantId",
        Lists.newArrayList(
            new CreateConsultantAgencyDTO().agencyId(2L),
            new CreateConsultantAgencyDTO().agencyId(3L)));

    ArgumentCaptor<Collection<Long>> checked = ArgumentCaptor.forClass(Collection.class);
    verify(adminCallerScope).assertMayUseAgencies(checked.capture());
    assertThat(Set.copyOf(checked.getValue()), is(Set.of(1L, 3L)));
  }

  @Test
  void setConsultantAgencies_Should_notChangeAnything_When_anAgencyIsOutOfScope() {
    when(consultantAgencyAdminService.findConsultantAgencyIds("consultantId"))
        .thenReturn(Lists.newArrayList(1L));
    doThrow(new ForbiddenException("out of scope"))
        .when(adminCallerScope)
        .assertMayUseAgencies(any());

    assertThrows(
        ForbiddenException.class,
        () ->
            consultantAdminFacade.setConsultantAgencies(
                "consultantId", Lists.newArrayList(new CreateConsultantAgencyDTO().agencyId(3L))));

    verify(consultantAgencyAdminService, never()).markConsultantAgenciesForDeletion(any(), any());
    verifyNoInteractions(relationCreatorService);
  }

  @Test
  void setConsultantAgencies_Should_beTransactional_SoRejectedDeletionsRollBack() throws Exception {
    var method =
        ConsultantAdminFacade.class.getMethod("setConsultantAgencies", String.class, List.class);

    assertThat(method.getAnnotation(Transactional.class), notNullValue());
  }

  @Test
  void findFilteredConsultants_Should_useDefaultSort_When_SortIsNull() {
    var searchResult = new ConsultantSearchResultDTO();
    when(consultantAdminFilterService.findFilteredConsultants(any(), any(), any(), any()))
        .thenReturn(searchResult);

    consultantAdminFacade.findFilteredConsultants(1, 1, new ConsultantFilter(), null);

    verify(consultantAdminFilterService)
        .findFilteredConsultants(eq(1), eq(1), any(), argThatSortHasField(FieldEnum.LAST_NAME));
  }

  @Test
  void findFilteredConsultants_Should_useDefaultSort_When_SortFieldIsUnknown() {
    var searchResult = new ConsultantSearchResultDTO();
    when(consultantAdminFilterService.findFilteredConsultants(any(), any(), any(), any()))
        .thenReturn(searchResult);
    Sort sortWithNullField = new Sort();

    consultantAdminFacade.findFilteredConsultants(1, 1, new ConsultantFilter(), sortWithNullField);

    verify(consultantAdminFilterService)
        .findFilteredConsultants(eq(1), eq(1), any(), argThatSortHasField(FieldEnum.LAST_NAME));
  }

  @Test
  void findFilteredConsultants_Should_notMergeAgencies_When_ResultIsNull() {
    when(consultantAdminFilterService.findFilteredConsultants(any(), any(), any(), any()))
        .thenReturn(null);

    consultantAdminFacade.findFilteredConsultants(
        1, 1, new ConsultantFilter(), new Sort().field(FieldEnum.EMAIL));

    verify(consultantAgencyAdminService, never()).appendAgenciesForConsultants(any());
  }

  @Test
  void
      checkAssignedAgenciesMatchConsultantTenant_Should_ThrowBadRequestException_When_ConsultantNotFound() {
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", true);
    when(consultantAdminService.findConsultantById("consultantId")).thenReturn(null);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAdminFacade.checkAssignedAgenciesMatchConsultantTenant(
                "consultantId", new ArrayList<>()));

    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", false);
  }

  @Test
  void
      checkAssignedAgenciesMatchConsultantTenant_Should_ThrowBadRequestException_When_ConsultantHasNoTenantAssigned() {
    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", true);
    ConsultantAdminResponseDTO consultant =
        new ConsultantAdminResponseDTO().embedded(new ConsultantDTO());
    when(consultantAdminService.findConsultantById("consultantId")).thenReturn(consultant);

    assertThrows(
        BadRequestException.class,
        () ->
            consultantAdminFacade.checkAssignedAgenciesMatchConsultantTenant(
                "consultantId", new ArrayList<>()));

    ReflectionTestUtils.setField(consultantAdminFacade, "multiTenancyEnabled", false);
  }

  private AgencyAdminFullResponseDTO agencyAdminFullResponse(Long id) {
    var response = new AgencyAdminFullResponseDTO();
    var agencyAdminResponseDTO =
        new de.caritas.cob.userservice.api.adapters.web.dto.AgencyAdminResponseDTO();
    agencyAdminResponseDTO.setId(id);
    response.setEmbedded(agencyAdminResponseDTO);
    return response;
  }

  private Sort argThatSortHasField(FieldEnum expectedField) {
    return org.mockito.ArgumentMatchers.argThat(sort -> sort.getField() == expectedField);
  }

  private CreateConsultantAgencyDTO argThatAgencyId(Long expectedAgencyId) {
    return org.mockito.ArgumentMatchers.argThat(
        dto -> dto != null && expectedAgencyId.equals(dto.getAgencyId()));
  }
}
