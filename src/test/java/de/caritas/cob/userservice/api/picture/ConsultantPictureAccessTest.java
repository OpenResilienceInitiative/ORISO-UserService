package de.caritas.cob.userservice.api.picture;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ConsultantPictureAccessTest {
  final AuthenticatedUser caller = new AuthenticatedUser();
  final ConsultantRepository consultants = mock(ConsultantRepository.class);
  final AdminUserFacade admins = mock(AdminUserFacade.class);
  final ConsultantAgencyAdminService agencies = mock(ConsultantAgencyAdminService.class);
  final ConsultantPictureAccess access =
      new ConsultantPictureAccess(caller, consultants, admins, agencies);
  final Consultant target = new Consultant();

  ConsultantPictureAccessTest() {
    target.setId("target");
    target.setTenantId(1L);
    caller.setUserId("caller");
    caller.setTenantId(1L);
    caller.setGrantedAuthorities(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
    caller.setRoles(Set.of("user-admin"));
    when(consultants.findByIdAndDeleteDateIsNull("target")).thenReturn(Optional.of(target));
    ReflectionTestUtils.setField(access, "multitenancy", true);
  }

  @Test
  void sameTenantAdminCanReadAndWrite() {
    access.check("target", false);
    access.check("target", true);
  }

  @Test
  void adviceSeekersCannotRead() {
    caller.setGrantedAuthorities(Set.of(USER_DEFAULT));
    assertThatThrownBy(() -> access.check("target", false))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    verifyNoInteractions(consultants);
  }

  @Test
  void colleaguesCanReadButCannotWrite() {
    caller.setGrantedAuthorities(Set.of(CONSULTANT_DEFAULT));
    access.check("target", false);
    assertThatThrownBy(() -> access.check("target", true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
  }

  @Test
  void wrongOrMissingTenantFailsClosed() {
    caller.setTenantId(2L);
    assertThatThrownBy(() -> access.check("target", false))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    caller.setTenantId(null);
    assertThatThrownBy(() -> access.check("target", true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
  }

  @Test
  void restrictedAgencyAdminMustShareTargetAgencyForReadAndWrite() {
    caller.setRoles(Set.of("user-admin", "restricted-agency-admin"));
    when(admins.findAdminUserAgencyIds("caller")).thenReturn(List.of(1L));
    when(agencies.findConsultantAgencyIds("target")).thenReturn(List.of(2L));
    assertThatThrownBy(() -> access.check("target", false))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    assertThatThrownBy(() -> access.check("target", true))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    when(agencies.findConsultantAgencyIds("target")).thenReturn(List.of(1L));
    access.check("target", true);
  }
}
