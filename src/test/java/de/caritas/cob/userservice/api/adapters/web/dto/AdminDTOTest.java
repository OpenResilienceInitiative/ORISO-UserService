package de.caritas.cob.userservice.api.adapters.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AdminDTOTest {

  private AdminDTO givenAFullyPopulatedAdminDTO() {
    return new AdminDTO()
        .id("id")
        .username("username")
        .firstname("firstname")
        .lastname("lastname")
        .email("mail@example.com")
        .createDate("2026-01-01")
        .updateDate("2026-01-02")
        .deleteDate(null)
        .tenantId("1")
        .tenantName("tenant")
        .tenantSubdomain("subdomain")
        .agencies(List.of(new AgencyAdminResponseDTO().id(1L)))
        .publicName("Public Name")
        .roleInOrg("role")
        .vacated(false)
        .adminRights(true);
  }

  @Test
  void builderChain_Should_RoundTripAllFields() {
    var dto = givenAFullyPopulatedAdminDTO();

    assertThat(dto.getId()).isEqualTo("id");
    assertThat(dto.getUsername()).isEqualTo("username");
    assertThat(dto.getFirstname()).isEqualTo("firstname");
    assertThat(dto.getLastname()).isEqualTo("lastname");
    assertThat(dto.getEmail()).isEqualTo("mail@example.com");
    assertThat(dto.getCreateDate()).isEqualTo("2026-01-01");
    assertThat(dto.getUpdateDate()).isEqualTo("2026-01-02");
    assertThat(dto.getDeleteDate()).isNull();
    assertThat(dto.getTenantId()).isEqualTo("1");
    assertThat(dto.getTenantName()).isEqualTo("tenant");
    assertThat(dto.getTenantSubdomain()).isEqualTo("subdomain");
    assertThat(dto.getAgencies()).hasSize(1);
    assertThat(dto.getPublicName()).isEqualTo("Public Name");
    assertThat(dto.getRoleInOrg()).isEqualTo("role");
    assertThat(dto.getVacated()).isFalse();
    assertThat(dto.getAdminRights()).isTrue();
  }

  @Test
  void setters_Should_RoundTripAllFields() {
    var dto = new AdminDTO();
    dto.setId("id");
    dto.setUsername("username");
    dto.setFirstname("firstname");
    dto.setLastname("lastname");
    dto.setEmail("mail@example.com");
    dto.setCreateDate("2026-01-01");
    dto.setUpdateDate("2026-01-02");
    dto.setDeleteDate("2026-01-03");
    dto.setTenantId("1");
    dto.setTenantName("tenant");
    dto.setTenantSubdomain("subdomain");
    dto.setAgencies(List.of(new AgencyAdminResponseDTO().id(1L)));
    dto.setPublicName("Public Name");
    dto.setRoleInOrg("role");
    dto.setVacated(false);
    dto.setAdminRights(true);

    assertThat(dto.getId()).isEqualTo("id");
    assertThat(dto.getDeleteDate()).isEqualTo("2026-01-03");
    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_InitializeList_When_AgenciesIsNull() {
    var dto = new AdminDTO();
    dto.setAgencies(null);

    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(1L));

    assertThat(dto.getAgencies()).hasSize(1);
  }

  @Test
  void addAgenciesItem_Should_AppendToExistingList_When_AgenciesAlreadyInitialized() {
    var dto = new AdminDTO();

    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(1L));
    dto.addAgenciesItem(new AgencyAdminResponseDTO().id(2L));

    assertThat(dto.getAgencies()).hasSize(2);
  }

  @Test
  void equals_Should_ReturnTrue_When_SameInstance() {
    var dto = givenAFullyPopulatedAdminDTO();

    assertThat(dto.equals(dto)).isTrue();
  }

  @Test
  void equals_Should_ReturnTrue_When_AllFieldsMatch() {
    var dto1 = givenAFullyPopulatedAdminDTO();
    var dto2 = givenAFullyPopulatedAdminDTO();

    assertThat(dto1).isEqualTo(dto2);
    assertThat(dto1.hashCode()).isEqualTo(dto2.hashCode());
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToNull() {
    var dto = givenAFullyPopulatedAdminDTO();

    assertThat(dto.equals(null)).isFalse();
  }

  @Test
  void equals_Should_ReturnFalse_When_ComparedToDifferentClass() {
    var dto = givenAFullyPopulatedAdminDTO();

    assertThat(dto.equals("not an AdminDTO")).isFalse();
  }

  @Test
  void toString_Should_ContainFieldValues() {
    var dto = givenAFullyPopulatedAdminDTO();

    var result = dto.toString();

    assertThat(result).contains("username").contains("Public Name");
  }

  @Test
  void equals_Should_ReturnFalse_When_AnyIndividualFieldDiffers() {
    var base = givenAFullyPopulatedAdminDTO();

    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().id("otherId"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().username("otherUsername"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().firstname("otherFirstname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().lastname("otherLastname"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().email("other@example.com"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().createDate("2027-01-01"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().updateDate("2027-01-02"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().deleteDate("2027-01-03"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().tenantId("2"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().tenantName("otherTenant"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().tenantSubdomain("otherSubdomain"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().agencies(List.of()));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().publicName("Other Public"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().roleInOrg("other role"));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().vacated(true));
    assertThat(base).isNotEqualTo(givenAFullyPopulatedAdminDTO().adminRights(false));
  }
}
