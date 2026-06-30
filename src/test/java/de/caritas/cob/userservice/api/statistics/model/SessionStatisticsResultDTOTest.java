package de.caritas.cob.userservice.api.statistics.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SessionStatisticsResultDTOTest {

  // ─── Fluent setters ───────────────────────────────────────────────────────

  @Test
  void fluentSetters_Should_ReturnSameInstance() {
    SessionStatisticsResultDTO dto = new SessionStatisticsResultDTO();
    assertThat(dto.id(1L)).isSameAs(dto);
    assertThat(dto.rcGroupId("rc1")).isSameAs(dto);
    assertThat(dto.consultingType(2)).isSameAs(dto);
    assertThat(dto.agencyId(3L)).isSameAs(dto);
    assertThat(dto.isTeamSession(true)).isSameAs(dto);
    assertThat(dto.postcode("88999")).isSameAs(dto);
    assertThat(dto.messageDate("2026-01-01")).isSameAs(dto);
    assertThat(dto.createDate("2026-01-02")).isSameAs(dto);
  }

  @Test
  void getters_Should_ReturnSetValues() {
    SessionStatisticsResultDTO dto =
        new SessionStatisticsResultDTO()
            .id(94L)
            .rcGroupId("y77uzd")
            .consultingType(1)
            .agencyId(5L)
            .isTeamSession(false)
            .postcode("88999")
            .messageDate("2026-06-01")
            .createDate("2026-05-01");

    assertThat(dto.getId()).isEqualTo(94L);
    assertThat(dto.getRcGroupId()).isEqualTo("y77uzd");
    assertThat(dto.getConsultingType()).isEqualTo(1);
    assertThat(dto.getAgencyId()).isEqualTo(5L);
    assertThat(dto.getIsTeamSession()).isFalse();
    assertThat(dto.getPostcode()).isEqualTo("88999");
    assertThat(dto.getMessageDate()).isEqualTo("2026-06-01");
    assertThat(dto.getCreateDate()).isEqualTo("2026-05-01");
  }

  @Test
  void setters_Should_StoreValues() {
    SessionStatisticsResultDTO dto = new SessionStatisticsResultDTO();
    dto.setId(10L);
    dto.setRcGroupId("rc");
    dto.setConsultingType(3);
    dto.setAgencyId(7L);
    dto.setIsTeamSession(true);
    dto.setPostcode("12345");
    dto.setMessageDate("2026-01-01");
    dto.setCreateDate("2026-01-02");

    assertThat(dto.getId()).isEqualTo(10L);
    assertThat(dto.getRcGroupId()).isEqualTo("rc");
    assertThat(dto.getConsultingType()).isEqualTo(3);
    assertThat(dto.getAgencyId()).isEqualTo(7L);
    assertThat(dto.getIsTeamSession()).isTrue();
    assertThat(dto.getPostcode()).isEqualTo("12345");
    assertThat(dto.getMessageDate()).isEqualTo("2026-01-01");
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-02");
  }

  // ─── equals ───────────────────────────────────────────────────────────────

  @Test
  void equals_Should_BeReflexive() {
    SessionStatisticsResultDTO dto = buildFull();
    assertThat(dto).isEqualTo(dto);
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    assertThat(buildFull()).isNotEqualTo(null);
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentType() {
    assertThat(buildFull()).isNotEqualTo("string");
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsEqual() {
    assertThat(buildFull()).isEqualTo(buildFull());
  }

  @Test
  void equals_Should_ReturnFalse_When_IdDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setId(999L);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_RcGroupIdDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setRcGroupId("other");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_ConsultingTypeDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setConsultingType(99);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_AgencyIdDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setAgencyId(999L);
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_IsTeamSessionDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setIsTeamSession(!a.getIsTeamSession());
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_PostcodeDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setPostcode("00000");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_MessageDateDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setMessageDate("1999-01-01");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnFalse_When_CreateDateDiffers() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setCreateDate("1999-01-01");
    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsNull() {
    assertThat(new SessionStatisticsResultDTO()).isEqualTo(new SessionStatisticsResultDTO());
  }

  // ─── hashCode ─────────────────────────────────────────────────────────────

  @Test
  void hashCode_Should_BeConsistent() {
    SessionStatisticsResultDTO dto = buildFull();
    assertThat(dto.hashCode()).isEqualTo(dto.hashCode());
  }

  @Test
  void hashCode_Should_BeEqualForEqualObjects() {
    assertThat(buildFull().hashCode()).isEqualTo(buildFull().hashCode());
  }

  @Test
  void hashCode_Should_DifferForDifferentObjects() {
    SessionStatisticsResultDTO a = buildFull();
    SessionStatisticsResultDTO b = buildFull();
    b.setId(999L);
    assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
  }

  // ─── toString ─────────────────────────────────────────────────────────────

  @Test
  void toString_Should_ContainAllFieldValues() {
    SessionStatisticsResultDTO dto = buildFull();
    String result = dto.toString();

    assertThat(result).contains("94");
    assertThat(result).contains("y77uzd");
    assertThat(result).contains("1");
    assertThat(result).contains("88999");
    assertThat(result).contains("2026-06-01");
    assertThat(result).contains("2026-05-01");
  }

  @Test
  void toString_Should_HandleNullFields() {
    String result = new SessionStatisticsResultDTO().toString();
    assertThat(result).contains("null");
  }

  // ─── helpers ──────────────────────────────────────────────────────────────

  private SessionStatisticsResultDTO buildFull() {
    return new SessionStatisticsResultDTO()
        .id(94L)
        .rcGroupId("y77uzd")
        .consultingType(1)
        .agencyId(5L)
        .isTeamSession(true)
        .postcode("88999")
        .messageDate("2026-06-01")
        .createDate("2026-05-01");
  }
}
