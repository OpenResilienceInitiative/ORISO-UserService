package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionDTOTest {

  private SessionDTO givenAFullyPopulatedDTO() {
    return new SessionDTO()
        .id(153918L)
        .agencyId(100L)
        .consultingType(1)
        .status(0)
        .postcode("79098")
        .language(LanguageCode.DE)
        .groupId("xGklslk2JJKK")
        .matrixRoomId("!aBcDeF123:91.99.219.182")
        .askerRcId("8ertjlasdKJA")
        .e2eLastMessage(new LastMessageDTO().msg("hello").t("text"))
        .lastMessage("lastMessage")
        .lastMessageType(MessageType.VIDEOCALL)
        .messageDate(1539184948L)
        .messagesRead(false)
        .isTeamSession(false)
        .registrationType("ANONYMOUS")
        .createDate("2026-01-01")
        .attachment(new SessionAttachmentDTO().fileType("image/png").fileReceived(true))
        .videoCallMessageDTO(
            new VideoCallMessageDTO(
                VideoCallMessageDTO.EventTypeEnum.IGNORED_CALL, "initiator", "rcId"))
        .topic(new SessionTopicDTO().id(1L).name("topicName"));
  }

  @Test
  void requiredArgsConstructor_Should_SetRequiredFields() {
    var dto = new SessionDTO(153918L, 100L, 1, 0, "ANONYMOUS");

    assertThat(dto.getId()).isEqualTo(153918L);
    assertThat(dto.getAgencyId()).isEqualTo(100L);
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getStatus()).isEqualTo(0);
    assertThat(dto.getRegistrationType()).isEqualTo("ANONYMOUS");
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedDTO();

    assertThat(dto.getId()).isEqualTo(153918L);
    assertThat(dto.getAgencyId()).isEqualTo(100L);
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getStatus()).isEqualTo(0);
    assertThat(dto.getPostcode()).isEqualTo("79098");
    assertThat(dto.getLanguage()).isEqualTo(LanguageCode.DE);
    assertThat(dto.getGroupId()).isEqualTo("xGklslk2JJKK");
    assertThat(dto.getMatrixRoomId()).isEqualTo("!aBcDeF123:91.99.219.182");
    assertThat(dto.getAskerRcId()).isEqualTo("8ertjlasdKJA");
    assertThat(dto.getE2eLastMessage().getMsg()).isEqualTo("hello");
    assertThat(dto.getLastMessage()).isEqualTo("lastMessage");
    assertThat(dto.getLastMessageType()).isEqualTo(MessageType.VIDEOCALL);
    assertThat(dto.getMessageDate()).isEqualTo(1539184948L);
    assertThat(dto.getMessagesRead()).isFalse();
    assertThat(dto.getIsTeamSession()).isFalse();
    assertThat(dto.getRegistrationType()).isEqualTo("ANONYMOUS");
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-01");
    assertThat(dto.getAttachment().getFileType()).isEqualTo("image/png");
    assertThat(dto.getVideoCallMessageDTO().getInitiatorUserName()).isEqualTo("initiator");
    assertThat(dto.getTopic().getName()).isEqualTo("topicName");
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new SessionDTO();
    dto.setId(153918L);
    dto.setAgencyId(100L);
    dto.setConsultingType(1);
    dto.setStatus(0);
    dto.setPostcode("79098");
    dto.setLanguage(LanguageCode.DE);
    dto.setGroupId("xGklslk2JJKK");
    dto.setMatrixRoomId("!aBcDeF123:91.99.219.182");
    dto.setAskerRcId("8ertjlasdKJA");
    dto.setE2eLastMessage(new LastMessageDTO().msg("hello"));
    dto.setLastMessage("lastMessage");
    dto.setLastMessageType(MessageType.VIDEOCALL);
    dto.setMessageDate(1539184948L);
    dto.setMessagesRead(false);
    dto.setIsTeamSession(false);
    dto.setRegistrationType("ANONYMOUS");
    dto.setCreateDate("2026-01-01");
    dto.setAttachment(new SessionAttachmentDTO().fileType("image/png"));
    dto.setVideoCallMessageDTO(
        new VideoCallMessageDTO(
            VideoCallMessageDTO.EventTypeEnum.IGNORED_CALL, "initiator", "rcId"));
    dto.setTopic(new SessionTopicDTO().id(1L));

    assertThat(dto.getId()).isEqualTo(153918L);
    assertThat(dto.getMatrixRoomId()).isEqualTo("!aBcDeF123:91.99.219.182");
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

    assertThat(dto.equals("not a SessionDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedDTO();

    var result = dto.toString();

    assertThat(result).contains("matrixRoomId").contains("ANONYMOUS");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().id(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().agencyId(99L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().consultingType(99));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().status(3));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().postcode("00000"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().language(LanguageCode.EN));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().groupId("otherGroupId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().matrixRoomId("!otherRoomId:host"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().askerRcId("otherAskerRcId"));
    assertThat(base)
        .isNotEqualTo(givenAFullyPopulatedDTO().e2eLastMessage(new LastMessageDTO().msg("other")));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().lastMessage("otherLastMessage"));
    assertThat(base)
        .isNotEqualTo(givenAFullyPopulatedDTO().lastMessageType(MessageType.FINISHED_CONVERSATION));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().messageDate(1L));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().messagesRead(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().isTeamSession(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().registrationType("REGISTERED"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedDTO().createDate("2027-01-01"));
    assertThat(base)
        .isNotEqualTo(
            givenAFullyPopulatedDTO().attachment(new SessionAttachmentDTO().fileType("other")));
    assertThat(base)
        .isNotEqualTo(
            givenAFullyPopulatedDTO()
                .videoCallMessageDTO(
                    new VideoCallMessageDTO(
                        VideoCallMessageDTO.EventTypeEnum.IGNORED_CALL, "other", "rcId")));
    assertThat(base)
        .isNotEqualTo(givenAFullyPopulatedDTO().topic(new SessionTopicDTO().id(1L).name("other")));
  }
}
