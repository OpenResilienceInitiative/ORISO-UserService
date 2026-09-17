package de.caritas.cob.userservice.api.picture;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantPicture;
import de.caritas.cob.userservice.api.port.out.ConsultantPictureRepository;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Issue #1049: the publish decision and the advice-seeker read that depends on it. */
class ConsultantPictureVisibilityTest {
  final AuthenticatedUser caller = new AuthenticatedUser();
  final ConsultantRepository consultants = mock(ConsultantRepository.class);
  final ConsultantPictureRepository pictures = mock(ConsultantPictureRepository.class);
  final AdminUserFacade admins = mock(AdminUserFacade.class);
  final ConsultantAgencyAdminService agencies = mock(ConsultantAgencyAdminService.class);
  final EntityManager entityManager = mock(EntityManager.class);
  final ConsultantPictureAccess access =
      new ConsultantPictureAccess(caller, consultants, admins, agencies);
  final ConsultantPictureStore store =
      new ConsultantPictureStore(consultants, pictures, access, entityManager);
  final Consultant target = new Consultant();
  final byte[] bytes = {1, 2, 3};
  ConsultantPicture picture = new ConsultantPicture("target", bytes, "image/png");

  ConsultantPictureVisibilityTest() {
    target.setId("target");
    target.setTenantId(1L);
    caller.setUserId("caller");
    caller.setTenantId(1L);
    caller.setGrantedAuthorities(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
    caller.setRoles(Set.of("user-admin"));
    ReflectionTestUtils.setField(access, "multitenancy", true);
    when(consultants.findByIdAndDeleteDateIsNull("target")).thenReturn(Optional.of(target));
    when(consultants.findPictureOwnerForUpdate("target")).thenReturn(Optional.of(target));
    when(pictures.findById("target")).thenAnswer(invocation -> Optional.of(picture));
  }

  void asAdviceSeeker() {
    caller.setGrantedAuthorities(Set.of(USER_DEFAULT));
    caller.setRoles(Set.of("user"));
  }

  @Test
  void aFreshlyStoredPictureIsInternalOnly() {
    assertThat(new ConsultantPicture("target", bytes, "image/png").isInternalOnly()).isTrue();
    assertThat(store.readInternalOnly("target")).isTrue();
  }

  @Test
  void anInternalOnlyPictureIsInvisibleToAdviceSeekers() {
    asAdviceSeeker();
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void publishingLetsAdviceSeekersReadTheExactBytesAndWithdrawalIsImmediate() {
    store.writeInternalOnly("target", false);
    assertThat(store.readInternalOnly("target")).isFalse();
    asAdviceSeeker();
    assertThat(store.readPublished("target").getBytes()).isEqualTo(bytes);

    caller.setGrantedAuthorities(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
    caller.setRoles(Set.of("user-admin"));
    store.writeInternalOnly("target", true);
    asAdviceSeeker();
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void publishingTouchesNothingButTheFlag() {
    LocalDateTime before = picture.getUpdatedAt();
    store.writeInternalOnly("target", false);
    assertThat(picture.getBytes()).isEqualTo(bytes);
    assertThat(picture.getContentType()).isEqualTo("image/png");
    assertThat(picture.getUpdatedAt()).isAfterOrEqualTo(before);
  }

  @Test
  void replacingAPublishedPictureMakesItInternalAgain() {
    store.writeInternalOnly("target", false);
    store.replace("target", bytes, "image/png");
    var stored = org.mockito.ArgumentCaptor.forClass(ConsultantPicture.class);
    verify(pictures).save(stored.capture());
    assertThat(stored.getValue().isInternalOnly()).isTrue();
  }

  @Test
  void anAdviceSeekerFromAnotherTenantNeverSeesAPublishedPicture() {
    store.writeInternalOnly("target", false);
    asAdviceSeeker();
    caller.setTenantId(2L);
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anUnauthenticatedOrRolelessCallerNeverSeesAPublishedPicture() {
    store.writeInternalOnly("target", false);
    caller.setGrantedAuthorities(Set.of());
    caller.setRoles(Set.of());
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void aDeletedConsultantHasNoPublishedPicture() {
    store.writeInternalOnly("target", false);
    target.setDeleteDate(LocalDateTime.now());
    when(consultants.findByIdAndDeleteDateIsNull("target")).thenReturn(Optional.empty());
    asAdviceSeeker();
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void anAdviceSeekerCannotChangeTheFlag() {
    asAdviceSeeker();
    assertThatThrownBy(() -> store.writeInternalOnly("target", false))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
    assertThatThrownBy(() -> store.readInternalOnly("target"))
        .isInstanceOf(
            de.caritas.cob.userservice.api.exception.httpresponses.ForbiddenException.class);
  }

  @Test
  void aConsultantWithoutAPictureHasNoFlagAndNoPublishedBytes() {
    when(pictures.findById("target")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> store.readInternalOnly("target"))
        .isInstanceOf(NotFoundException.class);
    asAdviceSeeker();
    assertThatThrownBy(() -> store.readPublished("target")).isInstanceOf(NotFoundException.class);
  }
}
