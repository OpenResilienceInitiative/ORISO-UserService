package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultantDTOTest {

  private ConsultantDTO givenAFullyPopulatedConsultantDTO() {
    return new ConsultantDTO()
        .id("id")
        .username("username")
        .firstname("firstname")
        .lastname("lastname")
        .email("mail@example.com")
        .formalLanguage(true)
        .teamConsultant(true)
        .absent(false)
        .absenceMessage("absent message")
        .createDate("2026-01-01")
        .updateDate("2026-01-02")
        .deleteDate(null)
        .status("CREATED")
        .agencies(List.of(new AgencyAdminResponseDTO().id(1L)))
        .isGroupchatConsultant(true)
        .isSupervisor(false)
        .tenantId(1)
        .tenantName("tenant")
        .displayName("Display Name")
        .publicName("Public Name")
        .roleInOrg("role")
        .vacated(false)
        .adminRights(true)
        .topics(List.of(new ConsultantTopicDTO().id(2L)));
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedConsultantDTO();

    assertThat(dto.getId()).isEqualTo("id");
    assertThat(dto.getUsername()).isEqualTo("username");
    assertThat(dto.getFirstname()).isEqualTo("firstname");
    assertThat(dto.getLastname()).isEqualTo("lastname");
    assertThat(dto.getEmail()).isEqualTo("mail@example.com");
    assertThat(dto.getFormalLanguage()).isTrue();
    assertThat(dto.getTeamConsultant()).isTrue();
    assertThat(dto.getAbsent()).isFalse();
    assertThat(dto.getAbsenceMessage()).isEqualTo("absent message");
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-01");
    assertThat(dto.getUpdateDate()).isEqualTo("2026-01-02");
    assertThat(dto.getDeleteDate()).isNull();
    assertThat(dto.getStatus()).isEqualTo("CREATED");
    assertThat(dto.getAgencies()).hasSize(1);
    assertThat(dto.getIsGroupchatConsultant()).isTrue();
    assertThat(dto.getIsSupervisor()).isFalse();
    assertThat(dto.getTenantId()).isEqualTo(1);
    assertThat(dto.getTenantName()).isEqualTo("tenant");
    assertThat(dto.getDisplayName()).isEqualTo("Display Name");
    assertThat(dto.getPublicName()).isEqualTo("Public Name");
    assertThat(dto.getRoleInOrg()).isEqualTo("role");
    assertThat(dto.getVacated()).isFalse();
    assertThat(dto.getAdminRights()).isTrue();
    assertThat(dto.getTopics()).hasSize(1);
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new ConsultantDTO();
    dto.setId("id");
    dto.setUsername("username");
    dto.setFirstname("firstname");
    dto.setLastname("lastname");
    dto.setEmail("mail@example.com");
    dto.setFormalLanguage(true);
    dto.setTeamConsultant(true);
    dto.setAbsent(false);
    dto.setAbsenceMessage("absent message");
    dto.setCreateDate("2026-01-01");
    dto.setUpdateDate("2026-01-02");
    dto.setDeleteDate("2026-01-03");
    dto.setStatus("CREATED");
    dto.setAgencies(List.of(new AgencyAdminResponseDTO().id(1L)));
    dto.setIsGroupchatConsultant(true);
    dto.setIsSupervisor(false);
    dto.setTenantId(1);
    dto.setTenantName("tenant");
    dto.setDisplayName("Display Name");
    dto.setPublicName("Public Name");
    dto.setRoleInOrg("role");
    dto.setVacated(false);
    dto.setAdminRights(true);
    dto.setTopics(List.of(new ConsultantTopicDTO().id(2L)));

    assertThat(dto.getId()).isEqualTo("id");
    assertThat(dto.getDeleteDate()).isEqualTo("2026-01-03");
    assertThat(dto.getAgencies()).hasSize(1);
    assertThat(dto.getTopics()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_InitializeList_When_AgenciesIsNull() {
    var dto = new ConsultantDTO();
    dto.setAgencies(null);

    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(1L));

    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_AppendToExistingList_When_AgenciesAlreadyInitialized() {
    var dto = new ConsultantDTO();

    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(1L));
    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(2L));

    assertThat(dto.getAgencies()).hasSize(2);
  }

  @Test
  void addTopicsItem_Should_InitializeList_When_TopicsIsNull() {
    var dto = new ConsultantDTO();
    dto.setTopics(null);

    dto.addTopicsItem(new ConsultantTopicDTO().id(1L));

    assertThat(dto.getTopics()).hasSize(1);
  }

  @Test
  void addTopicsItem_Should_AppendToExistingList_When_TopicsAlreadyInitialized() {
    var dto = new ConsultantDTO();

    dto.addTopicsItem(new ConsultantTopicDTO().id(1L));
    dto.addTopicsItem(new ConsultantTopicDTO().id(2L));

    assertThat(dto.getTopics()).hasSize(2);
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var dto = givenAFullyPopulatedConsultantDTO();

    assertThat(dto.equals(dto)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var dto1 = givenAFullyPopulatedConsultantDTO();
    var dto2 = givenAFullyPopulatedConsultantDTO();

    assertThat(dto1).isEqualTo(dto2);
    assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_AFieldDiffers() {
    var dto1 = givenAFullyPopulatedConsultantDTO();
    var dto2 = givenAFullyPopulatedConsultantDTO().username("differentUsername");

    assertThat(dto1).isNotEqualTo(dto2);
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var dto = givenAFullyPopulatedConsultantDTO();

    assertThat(dto.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var dto = givenAFullyPopulatedConsultantDTO();

    assertThat(dto.equals("not a ConsultantDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedConsultantDTO();

    var result = dto.toString();

    assertThat(result).contains("username").contains("Display Name");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedConsultantDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().id("otherId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().firstname("otherFirstname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().lastname("otherLastname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().email("other@example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().formalLanguage(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().teamConsultant(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().absent(true));
    assertThat(base)
        .isNotEqualTo(givenAFullyPopulatedConsultantDTO().absenceMessage("other message"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().createDate("2027-01-01"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().updateDate("2027-01-02"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().deleteDate("2027-01-03"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().status("DELETED"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().agencies(List.of()));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().isGroupchatConsultant(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().isSupervisor(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().tenantId(2));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().tenantName("otherTenant"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().displayName("Other Display"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().publicName("Other Public"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().roleInOrg("other role"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().vacated(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().adminRights(false));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedConsultantDTO().topics(List.of()));
  }
}
