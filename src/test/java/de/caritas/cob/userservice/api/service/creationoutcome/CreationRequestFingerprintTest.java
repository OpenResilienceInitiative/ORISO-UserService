package de.caritas.cob.userservice.api.service.creationoutcome;

import static org.assertj.core.api.Assertions.*;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CreationRequestFingerprintTest {
  private final CreationOperation.Scope scope =
      new CreationOperation.Scope(7, List.of(2L, 1L), "COUNSELLOR");

  private CreateConsultantDTO payload() {
    return new CreateConsultantDTO()
        .username("synthetic")
        .firstname("First")
        .lastname("Last")
        .email("synthetic@example.invalid")
        .agencyIds(List.of(1L, 2L))
        .topicIds(List.of(3L, 4L));
  }

  @Test
  void publicPayloadCanonicalizationExcludesSecrets() {
    var original = payload();
    var changed =
        payload()
            .password("a-different-secret")
            .email(" SYNTHETIC@EXAMPLE.INVALID ")
            .agencyIds(List.of(2L, 1L, 2L))
            .topicIds(List.of(4L, 3L, 4L));
    assertThat(CreationRequestFingerprint.of(changed, scope))
        .isEqualTo(CreationRequestFingerprint.of(original, scope));
    original.setPassword("generated-secret");
    assertThat(CreationRequestFingerprint.of(changed, scope))
        .isEqualTo(CreationRequestFingerprint.of(original, scope));
    var mutations =
        List.<Consumer<CreateConsultantDTO>>of(
            d -> d.setUsername("other"),
            d -> d.setFirstname("Other"),
            d -> d.setLastname("Other"),
            d -> d.setEmail("other@example.invalid"),
            d -> d.setFormalLanguage(true),
            d -> d.setAbsent(true),
            d -> d.setAbsenceMessage("Away"),
            d -> d.setIsGroupchatConsultant(true),
            d -> d.setTopicIds(List.of(5L)),
            d -> d.setPublicSlug("public-name"),
            d -> d.setDisplayName("Public"),
            d -> d.setInternalDisplayName("Internal"),
            d -> d.setSalutation("Mx"),
            d -> d.setPosition("Position"),
            d -> d.setTitle("Title"),
            d -> d.setAdminRemarks("Remark"),
            d -> d.setAvatarKind(CreateConsultantDTO.AvatarKindEnum.ICON));
    String baseline = CreationRequestFingerprint.of(payload(), scope);
    for (var mutation : mutations) {
      var dto = payload();
      mutation.accept(dto);
      assertThat(CreationRequestFingerprint.of(dto, scope)).isNotEqualTo(baseline);
    }
    assertThat(
            CreationRequestFingerprint.of(
                payload(), new CreationOperation.Scope(8, List.of(1L, 2L), "COUNSELLOR")))
        .isNotEqualTo(baseline);
    assertThat(
            CreationRequestFingerprint.of(
                payload().agencyIds(List.of(1L)),
                new CreationOperation.Scope(7, List.of(1L), "COUNSELLOR")))
        .isNotEqualTo(baseline);
    var icon = payload().avatarKind(CreateConsultantDTO.AvatarKindEnum.ICON).avatarId("one");
    String first = CreationRequestFingerprint.of(icon, scope);
    icon.setAvatarId("two");
    assertThat(CreationRequestFingerprint.of(icon, scope)).isNotEqualTo(first);
  }

  @Test
  void effectiveAvatarAndNullSemanticsMatchCreation() {
    var initials = payload().avatarKind(CreateConsultantDTO.AvatarKindEnum.INITIALS);
    var emptyIcon = payload().avatarKind(CreateConsultantDTO.AvatarKindEnum.ICON).avatarId(" ");
    assertThat(CreationRequestFingerprint.of(initials, scope))
        .isEqualTo(CreationRequestFingerprint.of(emptyIcon, scope));
    initials.setAvatarId("ignored");
    assertThat(CreationRequestFingerprint.of(initials, scope))
        .isEqualTo(CreationRequestFingerprint.of(emptyIcon, scope));
    assertThat(
            CreationRequestFingerprint.of(
                payload().absent(false).formalLanguage(false).isGroupchatConsultant(false), scope))
        .isEqualTo(CreationRequestFingerprint.of(payload(), scope));
    assertThat(CreationRequestFingerprint.of(payload().absenceMessage(""), scope))
        .isNotEqualTo(CreationRequestFingerprint.of(payload(), scope));
    assertThat(CreationRequestFingerprint.of(payload().publicSlug(" NAME "), scope))
        .isEqualTo(CreationRequestFingerprint.of(payload().publicSlug("name"), scope));
  }

  @Test
  void mismatchedEffectiveAgenciesFailBeforeClaim() {
    assertThatThrownBy(() -> CreationRequestFingerprint.of(payload().agencyIds(List.of(1L)), scope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Inconsistent effective agencies");
  }

  @Test
  void boundedInputsRejectOverflow() {
    assertThatThrownBy(
            () -> CreationRequestFingerprint.of(payload().publicSlug("x".repeat(256)), scope))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                CreationRequestFingerprint.of(
                    payload()
                        .topicIds(java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList()),
                    scope))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CreationOperation.Scope(7, List.of(0L), "COUNSELLOR"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
