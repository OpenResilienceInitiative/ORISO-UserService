package de.caritas.cob.userservice.api.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.exception.httpresponses.ConflictException;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.PublicSlugStatus;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.port.out.ReservedPublicSlugRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsultantPublicSlugServiceTest {

  private static final String CONSULTANT_ID = "65c1095e-b977-493a-a34f-064b729d1d6c";

  @Mock private ConsultantRepository consultantRepository;
  @Mock private ReservedPublicSlugRepository reservedPublicSlugRepository;

  @InjectMocks private ConsultantPublicSlugService consultantPublicSlugService;

  @Test
  void resolveActiveConsultant_ShouldResolveUuidById() {
    var consultant = consultantBuilder().build();
    when(consultantRepository.findByIdAndDeleteDateIsNull(CONSULTANT_ID))
        .thenReturn(Optional.of(consultant));

    var result = consultantPublicSlugService.resolveActiveConsultant(CONSULTANT_ID);

    assertThat(result.orElseThrow(), is(consultant));
  }

  @Test
  void resolveActiveConsultant_ShouldResolveSlugLowercase() {
    var consultant = consultantBuilder().publicSlug("nikunj-rohit").build();
    when(consultantRepository.findByPublicSlugAndDeleteDateIsNull("nikunj-rohit"))
        .thenReturn(Optional.of(consultant));

    var result = consultantPublicSlugService.resolveActiveConsultant("Nikunj-Rohit");

    assertThat(result.orElseThrow(), is(consultant));
  }

  @Test
  void applyAdminSlug_ShouldSetApprovedSlug() {
    var consultant = consultantBuilder().build();

    consultantPublicSlugService.applyAdminSlug(consultant, "Nikunj-Rohit");

    assertThat(consultant.getPublicSlug(), is("nikunj-rohit"));
    assertThat(consultant.getPublicSlugStatus(), is(PublicSlugStatus.APPROVED));
    assertThat(consultant.getPendingPublicSlug(), is((String) null));
  }

  @Test
  void requestSlug_ShouldSetPendingSlug() {
    var consultant = consultantBuilder().build();

    consultantPublicSlugService.requestSlug(consultant, "nikunj-rohit");

    assertThat(consultant.getPendingPublicSlug(), is("nikunj-rohit"));
    assertThat(consultant.getPublicSlugStatus(), is(PublicSlugStatus.PENDING));
  }

  @Test
  void requestSlug_ShouldRejectInvalidCharacters() {
    var consultant = consultantBuilder().build();

    assertThrows(
        BadRequestException.class,
        () -> consultantPublicSlugService.requestSlug(consultant, "nikunj_123"));
  }

  // ORISO-UserService#1534: digits are allowed inside and as whole segments.
  @ParameterizedTest
  @ValueSource(strings = {"team-2", "beratung-24", "qa-shazia-2810", "2810", "a1-b2"})
  void requestSlug_ShouldAcceptLowercaseLettersAndDigits(String slug) {
    var consultant = consultantBuilder().build();

    consultantPublicSlugService.requestSlug(consultant, slug);

    assertThat(consultant.getPendingPublicSlug(), is(slug));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"-a", "a-", "a--b", "qa shazia", "qa_shazia", "qa.2810", "beratung-ü", "-", "2-"})
  void requestSlug_ShouldRejectMalformedSlug(String slug) {
    var consultant = consultantBuilder().build();

    assertThrows(
        BadRequestException.class, () -> consultantPublicSlugService.requestSlug(consultant, slug));
  }

  @ParameterizedTest
  @ValueSource(strings = {"-a", "a-", "a--b", "qa shazia", "qa_shazia"})
  void applyAdminSlug_ShouldRejectMalformedSlug(String slug) {
    var consultant = consultantBuilder().build();

    assertThrows(
        BadRequestException.class,
        () -> consultantPublicSlugService.applyAdminSlug(consultant, slug));
  }

  /*
   * A name that parses as a UUID would be looked up as a consultant id first and never reach the
   * slug lookup, so the link could never open. Digits make such names possible.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1-2-3-4-5", "12345678-1234-1234-1234-123456789012"})
  void applyAdminSlug_ShouldRejectSlugThatParsesAsUuid(String slug) {
    var consultant = consultantBuilder().build();

    assertThrows(
        BadRequestException.class,
        () -> consultantPublicSlugService.applyAdminSlug(consultant, slug));
  }

  @Test
  void requestSlug_ShouldRejectReservedSlug() {
    var consultant = consultantBuilder().build();
    when(reservedPublicSlugRepository.existsBySlugAndActiveTrue("admin")).thenReturn(true);

    assertThrows(
        BadRequestException.class,
        () -> consultantPublicSlugService.requestSlug(consultant, "admin"));
  }

  @Test
  void requestSlug_ShouldRejectDuplicateActiveSlug() {
    var consultant = consultantBuilder().build();
    when(consultantRepository.existsByPublicSlugAndIdNotAndDeleteDateIsNull(
            "nikunj-rohit", CONSULTANT_ID))
        .thenReturn(true);

    assertThrows(
        ConflictException.class,
        () -> consultantPublicSlugService.requestSlug(consultant, "nikunj-rohit"));
  }

  @Test
  void applyAdminSlug_ShouldClearSlugWhenBlank() {
    var consultant =
        consultantBuilder()
            .publicSlug("old-slug")
            .pendingPublicSlug("new-slug")
            .publicSlugStatus(PublicSlugStatus.PENDING)
            .build();

    consultantPublicSlugService.applyAdminSlug(consultant, "");

    assertThat(consultant.getPublicSlug(), is((String) null));
    assertThat(consultant.getPendingPublicSlug(), is((String) null));
    assertThat(consultant.getPublicSlugStatus(), is((PublicSlugStatus) null));
  }

  @Test
  void rejectPendingSlug_ShouldKeepActiveSlugAndMarkRejected() {
    var consultant =
        consultantBuilder()
            .publicSlug("old-slug")
            .pendingPublicSlug("new-slug")
            .publicSlugStatus(PublicSlugStatus.PENDING)
            .build();

    consultantPublicSlugService.rejectPendingSlug(consultant);

    assertThat(consultant.getPublicSlug(), is("old-slug"));
    assertThat(consultant.getPendingPublicSlug(), is((String) null));
    assertThat(consultant.getPublicSlugStatus(), is(PublicSlugStatus.REJECTED));
  }

  private Consultant.ConsultantBuilder consultantBuilder() {
    return Consultant.builder()
        .id(CONSULTANT_ID)
        .matrixUserId("@member:matrix.example")
        .username("max.mustermann")
        .firstName("Max")
        .lastName("Mustermann")
        .email("max.mustermann@example.com");
  }
}
