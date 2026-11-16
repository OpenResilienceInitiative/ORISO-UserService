package de.caritas.cob.userservice.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactDTO.SourceEnum;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactPersonDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyContactPersonDTO.RoleEnum;
import de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.model.Admin.AdminType;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.AdminAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantAgencyRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.contactperson.CentreContact;
import de.caritas.cob.userservice.api.service.contactperson.ContactPair;
import de.caritas.cob.userservice.api.service.contactperson.ContactPersonResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Whom a counsellor sees as contact at their centre (ORISO-Admin#1534): own pair, Träger default,
 * all agency admins, all Träger admins, and with nobody left the platform support e-mail.
 */
@ExtendWith(MockitoExtension.class)
class AgencyContactPersonForConsultantTest {

  static final long AGENCY = 7L;
  static final long TENANT = 3L;
  static final String CALLER = "caller-1";
  static final String MATRIX_OK = "@admin:matrix.example.org";

  @Mock ConsultantAgencyRepository consultantAgencyRepository;
  @Mock AdminAgencyRepository adminAgencyRepository;
  @Mock ConsultantRepository consultantRepository;
  @Mock ContactPersonResolver resolver;

  AgencyAdminContactService service;

  @BeforeEach
  void setUp() {
    service =
        new AgencyAdminContactService(
            consultantAgencyRepository, adminAgencyRepository, consultantRepository, resolver);
    lenient()
        .when(
            consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
                CALLER, AGENCY))
        .thenReturn(true);
    var caller = new Consultant();
    caller.setId(CALLER);
    caller.setTenantId(TENANT);
    lenient()
        .when(consultantRepository.findByIdAndDeleteDateIsNull(CALLER))
        .thenReturn(Optional.of(caller));
    lenient().when(resolver.activeCounsellors(any(), any())).thenReturn(java.util.Map.of());
  }

  @Test
  void callerOutsideTheCentreIsRefused() {
    when(consultantAgencyRepository.existsByConsultantIdAndAgencyIdAndDeleteDateIsNull(
            CALLER, AGENCY))
        .thenReturn(false);

    assertThatThrownBy(() -> service.contactPersonFor(CALLER, AGENCY))
        .isInstanceOf(ForbiddenException.class);
    verifyNoInteractions(resolver);
  }

  @Test
  void ownPairComesFirst_withTheDisplayEmailAndRoles() {
    var contact = admin("bst-1", AdminType.AGENCY);
    contact.setContactEmail("kontakt@bst.org");
    var deputy = admin("tr-1", AdminType.TENANT);
    centre(new ContactPair(contact, deputy), ContactPair.NONE, "info@traeger.org");

    var result = service.contactPersonFor(CALLER, AGENCY);

    assertThat(result.getSource()).isEqualTo(SourceEnum.OWN);
    assertThat(result.getTraegerContactEmail()).isEqualTo("info@traeger.org");
    assertThat(result.getPeople())
        .extracting(
            ConsultantAgencyContactPersonDTO::getRole,
            ConsultantAgencyContactPersonDTO::getDisplayName,
            ConsultantAgencyContactPersonDTO::getEmail)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                RoleEnum.CONTACT, "Vor bst-1 Nach", "kontakt@bst.org"),
            org.assertj.core.groups.Tuple.tuple(
                RoleEnum.DEPUTY, "Vor tr-1 Nach", "tr-1@login.example"));
    verify(resolver, never()).agencyAdminsOf(any());
  }

  @Test
  void traegerDefaultApplies_When_theCentreHasNoOwnPair() {
    centre(ContactPair.NONE, new ContactPair(admin("tr-1", AdminType.TENANT), null), null);

    var result = service.contactPersonFor(CALLER, AGENCY);

    assertThat(result.getSource()).isEqualTo(SourceEnum.TRAEGER_DEFAULT);
    assertThat(result.getPeople())
        .singleElement()
        .satisfies(
            p -> {
              assertThat(p.getRole()).isEqualTo(RoleEnum.CONTACT);
              assertThat(p.getIsConsultant()).isFalse();
              assertThat(p.getChatConsultantId()).isNull();
            });
  }

  @Test
  void allAgencyAdminsOfTheCentre_When_noPairIsSet() {
    centre(ContactPair.NONE, ContactPair.NONE, null);
    when(resolver.agencyAdminsOf(AGENCY))
        .thenReturn(List.of(admin("bst-1", AdminType.AGENCY), admin("bst-2", AdminType.AGENCY)));

    var result = service.contactPersonFor(CALLER, AGENCY);

    assertThat(result.getSource()).isEqualTo(SourceEnum.FALLBACK_AGENCY_ADMINS);
    assertThat(result.getPeople())
        .extracting(ConsultantAgencyContactPersonDTO::getRole)
        .containsExactly(RoleEnum.ADMIN, RoleEnum.ADMIN);
    verify(resolver, never()).traegerAdminsOf(any());
  }

  @Test
  void allTraegerAdmins_When_theCentreHasNoAgencyAdmin() {
    centre(ContactPair.NONE, ContactPair.NONE, null);
    when(resolver.agencyAdminsOf(AGENCY)).thenReturn(List.of());
    when(resolver.traegerAdminsOf(TENANT)).thenReturn(List.of(admin("tr-1", AdminType.TENANT)));

    var result = service.contactPersonFor(CALLER, AGENCY);

    assertThat(result.getSource()).isEqualTo(SourceEnum.FALLBACK_TENANT_ADMINS);
    assertThat(result.getPeople()).hasSize(1);
  }

  @Test
  void supportEmail_When_nobodyIsLeft() {
    centre(ContactPair.NONE, ContactPair.NONE, "info@traeger.org");
    when(resolver.agencyAdminsOf(AGENCY)).thenReturn(List.of());
    when(resolver.traegerAdminsOf(TENANT)).thenReturn(List.of());

    var result = service.contactPersonFor(CALLER, AGENCY);

    assertThat(result.getSource()).isEqualTo(SourceEnum.SUPPORT_EMAIL);
    assertThat(result.getPeople()).isEmpty();
    assertThat(result.getTraegerContactEmail()).isEqualTo("info@traeger.org");
  }

  @Test
  void alsoCounsellorWithChatIdentityGetsAChatTarget() {
    var contact = admin("bst-1", AdminType.AGENCY);
    centre(new ContactPair(contact, null), ContactPair.NONE, null);
    when(resolver.activeCounsellors(any(), any()))
        .thenReturn(java.util.Map.of("bst-1", counsellor("bst-1", MATRIX_OK)));

    var person = service.contactPersonFor(CALLER, AGENCY).getPeople().get(0);

    assertThat(person.getIsConsultant()).isTrue();
    assertThat(person.getChatConsultantId()).isEqualTo("bst-1");
  }

  @Test
  void alsoCounsellorWithoutChatIdentityGetsNoChatTarget() {
    centre(new ContactPair(admin("bst-1", AdminType.AGENCY), null), ContactPair.NONE, null);
    when(resolver.activeCounsellors(any(), any()))
        .thenReturn(java.util.Map.of("bst-1", counsellor("bst-1", null)));

    var person = service.contactPersonFor(CALLER, AGENCY).getPeople().get(0);

    assertThat(person.getIsConsultant()).isTrue();
    assertThat(person.getChatConsultantId()).isNull();
  }

  @Test
  void theCallerIsShownAsContact_But_neverAsChatTarget() {
    centre(new ContactPair(admin(CALLER, AdminType.AGENCY), null), ContactPair.NONE, null);
    when(resolver.activeCounsellors(any(), any()))
        .thenReturn(java.util.Map.of(CALLER, counsellor(CALLER, MATRIX_OK)));

    var person = service.contactPersonFor(CALLER, AGENCY).getPeople().get(0);

    assertThat(person.getRole()).isEqualTo(RoleEnum.CONTACT);
    assertThat(person.getIsConsultant()).isTrue();
    assertThat(person.getChatConsultantId()).isNull();
  }

  @Test
  void counsellorIdentitiesAreLookedUpOnceForAllPeople() {
    centre(ContactPair.NONE, ContactPair.NONE, null);
    when(resolver.agencyAdminsOf(AGENCY))
        .thenReturn(List.of(admin("bst-1", AdminType.AGENCY), admin("bst-2", AdminType.AGENCY)));

    service.contactPersonFor(CALLER, AGENCY);

    verify(resolver).activeCounsellors(List.of("bst-1", "bst-2"), AGENCY);
  }

  void centre(ContactPair own, ContactPair traegerDefault, String traegerEmail) {
    when(resolver.resolveCentre(AGENCY, TENANT))
        .thenReturn(new CentreContact(AGENCY, TENANT, own, traegerDefault, traegerEmail));
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

  static Consultant counsellor(String id, String matrixUserId) {
    var consultant = new Consultant();
    consultant.setId(id);
    consultant.setMatrixUserId(matrixUserId);
    return consultant;
  }
}
