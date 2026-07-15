package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CreateConsultantDTOTest {

  private CreateConsultantDTO givenAFullyPopulatedDTO() {
    return new CreateConsultantDTO()
        .username("max.mustermann")
        .password("SecurePass123!")
        .firstname("Max")
        .lastname("Mustermann")
        .email("max@mustermann.de")
        .formalLanguage(true)
        .absent(false)
        .absenceMessage("I am absent until...")
        .tenantId(1L)
        .isGroupchatConsultant(true)
        .topicIds(List.of(3L, 7L, 12L));
  }

  @Test
  void requiredArgsConstructor_Should_SetRequiredFields() {
    var dto =
        new CreateConsultantDTO(
            "max.mustermann",
            "SecurePass123!",
            "Max",
            "Mustermann",
            "max@mustermann.de",
            true,
            false);

    assertThat(dto.getUsername()).isEqualTo("max.mustermann");
    assertThat(dto.getPassword()).isEqualTo("SecurePass123!");
    assertThat(dto.getFirstname()).isEqualTo("Max");
    assertThat(dto.getLastname()).isEqualTo("Mustermann");
    assertThat(dto.getEmail()).isEqualTo("max@mustermann.de");
    assertThat(dto.getFormalLanguage()).isTrue();
    assertThat(dto.getAbsent()).isFalse();
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getUsername()).isEqualTo("max.mustermann");
    assertThat(dto.getPassword()).isEqualTo("SecurePass123!");
    assertThat(dto.getFirstname()).isEqualTo("Max");
    assertThat(dto.getLastname()).isEqualTo("Mustermann");
    assertThat(dto.getEmail()).isEqualTo("max@mustermann.de");
    assertThat(dto.getFormalLanguage()).isTrue();
    assertThat(dto.getAbsent()).isFalse();
    assertThat(dto.getAbsenceMessage()).isEqualTo("I am absent until...");
    assertThat(dto.getTenantId()).isEqualTo(1L);
    assertThat(dto.getIsGroupchatConsultant()).isTrue();
    assertThat(dto.getTopicIds()).containsExactly(3L, 7L, 12L);
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new CreateConsultantDTO();
    dto.setUsername("username");
    dto.setPassword("password12");
    dto.setFirstname("First");
    dto.setLastname("Last");
    dto.setEmail("mail@example.com");
    dto.setFormalLanguage(false);
    dto.setAbsent(true);
    dto.setAbsenceMessage("msg");
    dto.setTenantId(2L);
    dto.setIsGroupchatConsultant(false);
    dto.setTopicIds(List.of(1L));

    assertThat(dto.getUsername()).isEqualTo("username");
    assertThat(dto.getTopicIds()).containsExactly(1L);
  }

  @Test
  void addTopicIdsItem_Should_InitializeList_When_TopicIdsIsNull() {
    var dto = new CreateConsultantDTO();
    dto.setTopicIds(null);

    dto.addTopicIdsItem(3L);

    assertThat(dto.getTopicIds()).containsExactly(3L);
  }

  @Test
  void addTopicIdsItem_Should_AppendToExistingList_When_TopicIdsAlreadyInitialized() {
    var dto = new CreateConsultantDTO();

    dto.addTopicIdsItem(3L);
    dto.addTopicIdsItem(7L);

    assertThat(dto.getTopicIds()).containsExactly(3L, 7L);
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals(dto)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var dto1 = givenAFullyPopulatedDTO();
    var dto2 = givenAFullyPopulatedDTO();

    assertThat(dto1).isEqualTo(dto2);
    assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.equals("not a CreateConsultantDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("max.mustermann").contains("Mustermann");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().username("otherUsername"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().password("otherPassword1"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().firstname("otherFirstname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().lastname("otherLastname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().email("other@example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().formalLanguage(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().absent(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().absenceMessage("other message"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().tenantId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isGroupchatConsultant(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().topicIds(List.of(99L)));
  }
}
