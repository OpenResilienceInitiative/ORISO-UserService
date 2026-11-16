package de.caritas.cob.userservice.api.admin.service.contactperson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactCoverageDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactCoverageRequestDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyContactPersonAdminDTO.EffectiveSourceEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.AgencyDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ContactAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.UpdateTenantContactPersonDTO;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Agencies;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Platform;
import de.caritas.cob.userservice.api.admin.service.admin.AdminScope.Tenant;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.AgencyContactPerson;
import de.caritas.cob.userservice.api.model.TenantContactPerson;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import de.caritas.cob.userservice.api.port.out.AgencyContactPersonRepository;
import de.caritas.cob.userservice.api.port.out.TenantContactPersonRepository;
import de.caritas.cob.userservice.api.service.agency.AgencyService;
import de.caritas.cob.userservice.api.service.contactperson.CentreContact;
import de.caritas.cob.userservice.api.service.contactperson.ContactPair;
import de.caritas.cob.userservice.api.service.contactperson.ContactPersonResolver;
import de.caritas.cob.userservice.api.service.contactperson.TraegerContact;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Who may read and set contact persons (ORISO-Admin#1534): platform admins everywhere, Träger
 * admins within their Träger, agency admins only for their own centres and never the Träger
 * default.
 */
@ExtendWith(MockitoExtension.class)
class ContactPersonAdminServiceTest {

  static final long AGENCY = 7L;
  static final long TENANT = 3L;

  @Mock AdminScope adminScope;
  @Mock ContactPersonResolver resolver;
  @Mock AgencyService agencyService;
  @Mock AgencyContactPersonRepository agencyContactPersonRepository;
  @Mock TenantContactPersonRepository tenantContactPersonRepository;
  @Mock AdminRepository adminRepository;

  ContactPersonAdminService service;

  @BeforeEach
  void setUp() {
    service =
        new ContactPersonAdminService(
            adminScope,
            resolver,
            agencyService,
            agencyContactPersonRepository,
            tenantContactPersonRepository,
            adminRepository);
    lenient()
        .when(resolver.resolveCentre(any(), any()))
        .thenAnswer(
            call ->
                new CentreContact(
                    call.getArgument(0),
                    call.getArgument(1),
                    ContactPair.NONE,
                    ContactPair.NONE,
                    null));
    lenient().when(resolver.activeCounsellors(any(), any())).thenReturn(Map.of());
  }

  @Test
  void foreignCentreIsRefusedBeforeAnythingIsRead() {
    doThrow(new ForbiddenException("out of scope")).when(adminScope).assertMay(any());

    assertThatThrownBy(() -> service.getAgencyContactPerson(AGENCY, null))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> service.updateAgencyContactPerson(AGENCY, null, new UpdateContactPersonDTO()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.getAgencyContactPersonCandidates(AGENCY, null))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(resolver, agencyContactPersonRepository);
  }

  @Test
  void traegerAdminAlwaysUsesTheOwnTraeger_And_ignoresTheTenantIdParameter() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));

    var result = service.getAgencyContactPerson(AGENCY, 99L);

    assertThat(result.getTenantId()).isEqualTo(TENANT);
    verify(resolver).resolveCentre(AGENCY, TENANT);
    verifyNoInteractions(agencyService);
  }

  @Test
  void agencyAdminUsesTheOwnTraeger() {
    when(adminScope.current()).thenReturn(new Agencies(TENANT, Set.of(AGENCY)));

    service.getAgencyContactPerson(AGENCY, 99L);

    verify(resolver).resolveCentre(AGENCY, TENANT);
  }

  @Test
  void platformAdmin_Should_getTheCentresTraegerFromAgencyService_And_aMatchingHintPasses() {
    when(adminScope.current()).thenReturn(new Platform());
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency(AGENCY, 5L));

    service.getAgencyContactPerson(AGENCY, null);
    service.getAgencyContactPerson(AGENCY, 5L);

    verify(resolver, org.mockito.Mockito.times(2)).resolveCentre(AGENCY, 5L);
  }

  @Test
  void platformAdmin_Should_beRefused_When_theTenantHintDisagreesWithAgencyService() {
    when(adminScope.current()).thenReturn(new Platform());
    when(agencyService.getAgencyWithoutCaching(AGENCY)).thenReturn(agency(AGENCY, 5L));

    assertThatThrownBy(() -> service.getAgencyContactPerson(AGENCY, 4L))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY, 4L, new UpdateContactPersonDTO().contactAdminId("a-1")))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(resolver, agencyContactPersonRepository);
  }

  @Test
  void platformAdmin_Should_notSetAContactForACentreAgencyServiceDoesNotKnow() {
    when(adminScope.current()).thenReturn(new Platform());

    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY, 5L, new UpdateContactPersonDTO().contactAdminId("a-1")))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(agencyContactPersonRepository);
  }

  @Test
  void getAgencyContactPerson_Should_reportOwnPairAndTraegerDefault() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    var contact = admin("bst-1", AdminType.AGENCY);
    contact.setContactEmail("kontakt@bst.org");
    var deflt = admin("tr-1", AdminType.TENANT);
    when(resolver.resolveCentre(AGENCY, TENANT))
        .thenReturn(
            new CentreContact(
                AGENCY,
                TENANT,
                new ContactPair(contact, null),
                new ContactPair(deflt, null),
                null));

    var result = service.getAgencyContactPerson(AGENCY, null);

    assertThat(result.getEffectiveSource()).isEqualTo(EffectiveSourceEnum.OWN);
    assertThat(result.getContact().getAdminId()).isEqualTo("bst-1");
    assertThat(result.getContact().getLoginEmail()).isEqualTo("bst-1@login.example");
    assertThat(result.getContact().getContactEmail()).isEqualTo("kontakt@bst.org");
    assertThat(result.getContact().getDisplayEmail()).isEqualTo("kontakt@bst.org");
    assertThat(result.getContact().getType()).isEqualTo(ContactAdminDTO.TypeEnum.AGENCY);
    assertThat(result.getDeputy()).isNull();
    assertThat(result.getTraegerDefault().getContact().getAdminId()).isEqualTo("tr-1");
    // One counsellor lookup for everybody in the answer.
    verify(resolver).activeCounsellors(List.of("bst-1", "tr-1"), AGENCY);
  }

  @Test
  void updateAgencyContactPerson_Should_storeAValidPair() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    var contact = admin("bst-1", AdminType.AGENCY);
    var deputy = admin("tr-1", AdminType.TENANT);
    when(adminRepository.findById("bst-1")).thenReturn(Optional.of(contact));
    when(adminRepository.findById("tr-1")).thenReturn(Optional.of(deputy));
    when(resolver.isCentreCandidate(any(), eq(AGENCY), eq(TENANT))).thenReturn(true);
    when(agencyContactPersonRepository.findById(AGENCY)).thenReturn(Optional.empty());

    service.updateAgencyContactPerson(
        AGENCY, null, new UpdateContactPersonDTO().contactAdminId("bst-1").deputyAdminId("tr-1"));

    var saved = ArgumentCaptor.forClass(AgencyContactPerson.class);
    verify(agencyContactPersonRepository).save(saved.capture());
    assertThat(saved.getValue().getAgencyId()).isEqualTo(AGENCY);
    assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
    assertThat(saved.getValue().getContactAdminId()).isEqualTo("bst-1");
    assertThat(saved.getValue().getDeputyAdminId()).isEqualTo("tr-1");
  }

  @Test
  void updateAgencyContactPerson_Should_clearThePair_When_noContactIsGiven() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    when(agencyContactPersonRepository.existsById(AGENCY)).thenReturn(true);

    service.updateAgencyContactPerson(AGENCY, null, new UpdateContactPersonDTO());

    verify(agencyContactPersonRepository).deleteById(AGENCY);
    verify(agencyContactPersonRepository, never()).save(any());
  }

  @Test
  void updateAgencyContactPerson_Should_refuseADeputyWithoutContact() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));

    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY, null, new UpdateContactPersonDTO().deputyAdminId("tr-1")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void updateAgencyContactPerson_Should_refuseTheDeputyEqualToTheContact() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));

    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY,
                    null,
                    new UpdateContactPersonDTO().contactAdminId("bst-1").deputyAdminId("bst-1")))
        .isInstanceOf(BadRequestException.class);
  }

  @Test
  void updateAgencyContactPerson_Should_refuseAnAdminWhoIsNoCandidateOrUnknown() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    var foreign = admin("bst-x", AdminType.AGENCY);
    when(adminRepository.findById("bst-x")).thenReturn(Optional.of(foreign));
    when(adminRepository.findById("gone")).thenReturn(Optional.empty());
    when(resolver.isCentreCandidate(foreign, AGENCY, TENANT)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY, null, new UpdateContactPersonDTO().contactAdminId("bst-x")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(
            () ->
                service.updateAgencyContactPerson(
                    AGENCY, null, new UpdateContactPersonDTO().contactAdminId("gone")))
        .isInstanceOf(BadRequestException.class);
    verify(agencyContactPersonRepository, never()).save(any());
  }

  @Test
  void traegerDefault_Should_beRefusedForAForeignTraegerOrAnAgencyAdmin() {
    // AdminScope refuses Target.tenant for agency admins and for another Träger.
    doThrow(new ForbiddenException("out of scope")).when(adminScope).assertMay(any());

    assertThatThrownBy(() -> service.getTenantContactPerson(TENANT))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(
            () -> service.updateTenantContactPerson(TENANT, new UpdateTenantContactPersonDTO()))
        .isInstanceOf(ForbiddenException.class);
    assertThatThrownBy(() -> service.getTenantContactPersonCandidates(TENANT))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(tenantContactPersonRepository, resolver);
  }

  @Test
  void updateTenantContactPerson_Should_storePairAndNormalisedGeneralEmail() {
    var contact = admin("tr-1", AdminType.TENANT);
    when(adminRepository.findById("tr-1")).thenReturn(Optional.of(contact));
    when(resolver.isTraegerCandidate(contact, TENANT)).thenReturn(true);
    when(tenantContactPersonRepository.findById(TENANT)).thenReturn(Optional.empty());
    when(resolver.resolveTraeger(TENANT))
        .thenReturn(new TraegerContact(TENANT, new ContactPair(contact, null), "info@traeger.org"));

    var result =
        service.updateTenantContactPerson(
            TENANT,
            new UpdateTenantContactPersonDTO()
                .contactAdminId("tr-1")
                .generalContactEmail(" Info@Traeger.org "));

    var saved = ArgumentCaptor.forClass(TenantContactPerson.class);
    verify(tenantContactPersonRepository).save(saved.capture());
    assertThat(saved.getValue().getGeneralContactEmail()).isEqualTo("info@traeger.org");
    assertThat(saved.getValue().getContactAdminId()).isEqualTo("tr-1");
    assertThat(result.getContact().getAdminId()).isEqualTo("tr-1");
    assertThat(result.getGeneralContactEmail()).isEqualTo("info@traeger.org");
  }

  @Test
  void updateTenantContactPerson_Should_keepARowForTheGeneralEmailAlone_andDeleteAnEmptyOne() {
    when(tenantContactPersonRepository.findById(TENANT)).thenReturn(Optional.empty());
    when(resolver.resolveTraeger(TENANT))
        .thenReturn(new TraegerContact(TENANT, ContactPair.NONE, null));
    when(tenantContactPersonRepository.existsById(TENANT)).thenReturn(true);

    service.updateTenantContactPerson(
        TENANT, new UpdateTenantContactPersonDTO().generalContactEmail("info@traeger.org"));
    service.updateTenantContactPerson(
        TENANT, new UpdateTenantContactPersonDTO().generalContactEmail("  "));

    verify(tenantContactPersonRepository).save(any());
    verify(tenantContactPersonRepository).deleteById(TENANT);
  }

  @Test
  void updateTenantContactPerson_Should_keepTheGeneralEmail_When_theFieldIsAbsent() {
    var contact = admin("tr-1", AdminType.TENANT);
    when(adminRepository.findById("tr-1")).thenReturn(Optional.of(contact));
    when(resolver.isTraegerCandidate(contact, TENANT)).thenReturn(true);
    when(tenantContactPersonRepository.findById(TENANT))
        .thenReturn(
            Optional.of(
                TenantContactPerson.builder()
                    .tenantId(TENANT)
                    .generalContactEmail("info@traeger.org")
                    .build()));
    when(resolver.resolveTraeger(TENANT))
        .thenReturn(new TraegerContact(TENANT, ContactPair.NONE, null));

    service.updateTenantContactPerson(
        TENANT, new UpdateTenantContactPersonDTO().contactAdminId("tr-1"));

    var saved = ArgumentCaptor.forClass(TenantContactPerson.class);
    verify(tenantContactPersonRepository).save(saved.capture());
    assertThat(saved.getValue().getGeneralContactEmail()).isEqualTo("info@traeger.org");
  }

  @Test
  void coverage_Should_countOnlyOwnOrTraegerDefault() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    var someone = admin("tr-1", AdminType.TENANT);
    when(resolver.resolveCentres(anyMap()))
        .thenReturn(
            Map.of(
                1L,
                new CentreContact(
                    1L, TENANT, new ContactPair(someone, null), ContactPair.NONE, null),
                2L,
                new CentreContact(
                    2L, TENANT, ContactPair.NONE, new ContactPair(someone, null), null),
                3L,
                new CentreContact(3L, TENANT, ContactPair.NONE, ContactPair.NONE, null)));

    var result =
        service.getAgencyContactCoverage(
            new AgencyContactCoverageRequestDTO().agencyIds(List.of(1L, 2L, 3L)));

    assertThat(result)
        .extracting(AgencyContactCoverageDTO::getAgencyId, AgencyContactCoverageDTO::getCovered)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1L, true),
            org.assertj.core.groups.Tuple.tuple(2L, true),
            org.assertj.core.groups.Tuple.tuple(3L, false));
    assertThat(result.get(1).getSource())
        .isEqualTo(AgencyContactCoverageDTO.SourceEnum.TRAEGER_DEFAULT);
  }

  @Test
  void coverage_Should_refuseWhenAnyCentreIsForeign() {
    doThrow(new ForbiddenException("out of scope")).when(adminScope).assertMay(any());

    assertThatThrownBy(
            () ->
                service.getAgencyContactCoverage(
                    new AgencyContactCoverageRequestDTO().agencyIds(List.of(1L))))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(resolver);
  }

  @Test
  void coverage_Should_takeTheTraegerFromOneAgencyServiceLookup_And_ignoreUnverifiableHints() {
    when(adminScope.current()).thenReturn(new Platform());
    when(agencyService.getAgenciesWithoutCaching(anyList()))
        .thenReturn(List.of(agency(1L, 5L), agency(2L, 6L)));
    when(resolver.resolveCentres(anyMap())).thenReturn(Map.of());

    service.getAgencyContactCoverage(
        new AgencyContactCoverageRequestDTO()
            .agencyIds(List.of(1L, 2L, 3L))
            .tenantIdsByAgency(Map.of("1", 5L, "3", 9L)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<Long, Long>> request = ArgumentCaptor.forClass(Map.class);
    verify(resolver).resolveCentres(request.capture());
    assertThat(request.getValue())
        .containsEntry(1L, 5L)
        .containsEntry(2L, 6L)
        .containsEntry(3L, null);
    verify(agencyService).getAgenciesWithoutCaching(List.of(1L, 2L, 3L));
  }

  @Test
  void coverage_Should_refuseAHintThatDisagreesWithAgencyService() {
    when(adminScope.current()).thenReturn(new Platform());
    when(agencyService.getAgenciesWithoutCaching(anyList())).thenReturn(List.of(agency(1L, 5L)));

    assertThatThrownBy(
            () ->
                service.getAgencyContactCoverage(
                    new AgencyContactCoverageRequestDTO()
                        .agencyIds(List.of(1L))
                        .tenantIdsByAgency(Map.of("1", 4L))))
        .isInstanceOf(BadRequestException.class);
    verifyNoInteractions(resolver);
  }

  @Test
  void candidates_Should_markWhoIsAlsoACounsellorOfTheCentre() {
    when(adminScope.current()).thenReturn(new Tenant(TENANT));
    var both = admin("bst-1", AdminType.AGENCY);
    var adminOnly = admin("tr-1", AdminType.TENANT);
    when(resolver.centreCandidates(AGENCY, TENANT)).thenReturn(List.of(both, adminOnly));
    when(resolver.activeCounsellors(List.of("bst-1", "tr-1"), AGENCY))
        .thenReturn(Map.of("bst-1", new de.caritas.cob.userservice.api.model.Consultant()));

    var result = service.getAgencyContactPersonCandidates(AGENCY, null);

    assertThat(result)
        .extracting(ContactAdminDTO::getAdminId, ContactAdminDTO::getIsConsultant)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("bst-1", true),
            org.assertj.core.groups.Tuple.tuple("tr-1", false));
  }

  static AgencyDTO agency(long id, long tenantId) {
    var agency = new AgencyDTO();
    agency.setId(id);
    agency.setTenantId(tenantId);
    return agency;
  }

  static Admin admin(String id, AdminType type) {
    return Admin.builder()
        .id(id)
        .type(type)
        .tenantId(TENANT)
        .username(id)
        .firstName("Vor " + id)
        .lastName("Nach")
        .email(id + "@login.example")
        .build();
  }
}
