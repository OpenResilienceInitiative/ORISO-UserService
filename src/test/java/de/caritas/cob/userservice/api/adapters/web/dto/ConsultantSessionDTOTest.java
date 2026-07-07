package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultantSessionDTOTest {

  private ConsultantSessionDTO givenAFullyPopulatedDto() {
    return new ConsultantSessionDTO()
        .id(1L)
        .agencyId(2L)
        .consultingType(3)
        .status(4)
        .postcode("88045")
        .groupId("groupId")
        .consultantId("consultantId")
        .consultantRcId("consultantRcId")
        .askerId("askerId")
        .askerRcId("askerRcId")
        .askerUserName("askerUserName")
        .isTeamSession(true)
        .age(30)
        .gender("gender")
        .counsellingRelation("relation")
        .mainTopic(new SessionTopicDTO().id(5L))
        .topics(List.of(new SessionTopicDTO().id(6L)))
        .referer("referer");
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDto();

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getAgencyId()).isEqualTo(2L);
    assertThat(dto.getConsultingType()).isEqualTo(3);
    assertThat(dto.getStatus()).isEqualTo(4);
    assertThat(dto.getPostcode()).isEqualTo("88045");
    assertThat(dto.getGroupId()).isEqualTo("groupId");
    assertThat(dto.getConsultantId()).isEqualTo("consultantId");
    assertThat(dto.getConsultantRcId()).isEqualTo("consultantRcId");
    assertThat(dto.getAskerId()).isEqualTo("askerId");
    assertThat(dto.getAskerRcId()).isEqualTo("askerRcId");
    assertThat(dto.getAskerUserName()).isEqualTo("askerUserName");
    assertThat(dto.getIsTeamSession()).isTrue();
    assertThat(dto.getAge()).isEqualTo(30);
    assertThat(dto.getGender()).isEqualTo("gender");
    assertThat(dto.getCounsellingRelation()).isEqualTo("relation");
    assertThat(dto.getMainTopic().getId()).isEqualTo(5L);
    assertThat(dto.getTopics()).hasSize(1);
    assertThat(dto.getReferer()).isEqualTo("referer");
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new ConsultantSessionDTO();
    dto.setId(1L);
    dto.setAgencyId(2L);
    dto.setConsultingType(3);
    dto.setStatus(4);
    dto.setPostcode("88045");
    dto.setGroupId("groupId");
    dto.setConsultantId("consultantId");
    dto.setConsultantRcId("consultantRcId");
    dto.setAskerId("askerId");
    dto.setAskerRcId("askerRcId");
    dto.setAskerUserName("askerUserName");
    dto.setIsTeamSession(true);
    dto.setAge(30);
    dto.setGender("gender");
    dto.setCounsellingRelation("relation");
    dto.setMainTopic(new SessionTopicDTO().id(5L));
    dto.setTopics(List.of(new SessionTopicDTO().id(6L)));
    dto.setReferer("referer");

    assertThat(dto.getId()).isEqualTo(1L);
    assertThat(dto.getMainTopic().getId()).isEqualTo(5L);
    assertThat(dto.getTopics()).hasSize(1);
  }

  @Test
  void addTopicsItem_Should_InitializeList_When_TopicsIsNull() {
    var dto = new ConsultantSessionDTO();
    dto.setTopics(null);

    dto.addTopicsItem(new SessionTopicDTO().id(1L));

    assertThat(dto.getTopics()).hasSize(1);
  }

  @Test
  void addTopicsItem_Should_AppendToExistingList_When_TopicsAlreadyInitialized() {
    var dto = new ConsultantSessionDTO();

    dto.addTopicsItem(new SessionTopicDTO().id(1L));
    dto.addTopicsItem(new SessionTopicDTO().id(2L));

    assertThat(dto.getTopics()).hasSize(2);
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var dto = givenAFullyPopulatedDto();

    assertThat(dto.equals(dto)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var dto1 = givenAFullyPopulatedDto();
    var dto2 = givenAFullyPopulatedDto();

    assertThat(dto1).isEqualTo(dto2);
    assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_AFieldDiffers() {
    var dto1 = givenAFullyPopulatedDto();
    var dto2 = givenAFullyPopulatedDto().postcode("00000");

    assertThat(dto1).isNotEqualTo(dto2);
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var dto = givenAFullyPopulatedDto();

    assertThat(dto.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var dto = givenAFullyPopulatedDto();

    assertThat(dto.equals("not a ConsultantSessionDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDto();

    var result = dto.toString();

    assertThat(result).contains("askerUserName").contains("relation");
  }

  @Test
  void toString_Should_HandleNullField() {
    var dto = givenAFullyPopulatedDto().referer(null);

    var result = dto.toString();

    assertThat(result).contains("null");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDto();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().id(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().agencyId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().consultingType(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().status(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().postcode("00000"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().groupId("otherGroupId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().consultantId("otherConsultantId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().consultantRcId("otherRcId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().askerId("otherAskerId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().askerRcId("otherAskerRcId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().askerUserName("otherAskerUserName"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().isTeamSession(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().age(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().gender("otherGender"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().counsellingRelation("otherRelation"));
    assertThat(base)
        .isNotEqualTo(givenAFullyPopulatedDto().mainTopic(new SessionTopicDTO().id(99L)));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().topics(List.of()));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDto().referer("otherReferer"));
  }
}
