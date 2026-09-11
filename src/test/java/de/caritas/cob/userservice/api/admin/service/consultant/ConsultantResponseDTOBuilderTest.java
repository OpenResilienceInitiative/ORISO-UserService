package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.model.Consultant;
import org.junit.jupiter.api.Test;

class ConsultantResponseDTOBuilderTest {

  private Consultant consultantWithPersonalInfo() {
    var consultant = new Consultant();
    consultant.setId("consultant-id");
    consultant.setSalutation("counsellor_male");
    consultant.setPosition("Counsellor");
    consultant.setTitle("M.A.");
    consultant.setAdminRemarks("Tenant-admin-only note");
    return consultant;
  }

  @Test
  void buildResponseDTO_Should_mapPersonalInfoFields() {
    var dto =
        ConsultantResponseDTOBuilder.getInstance(consultantWithPersonalInfo())
            .buildResponseDTO()
            .getEmbedded();

    assertThat(dto.getSalutation()).isEqualTo("counsellor_male");
    assertThat(dto.getPosition()).isEqualTo("Counsellor");
    assertThat(dto.getTitle()).isEqualTo("M.A.");
  }

  @Test
  void buildResponseDTO_Should_omitAdminRemarks_ByDefault() {
    var dto =
        ConsultantResponseDTOBuilder.getInstance(consultantWithPersonalInfo())
            .buildResponseDTO()
            .getEmbedded();

    assertThat(dto.getAdminRemarks()).isNull();
  }

  @Test
  void buildResponseDTO_Should_mapInternalDisplayName() {
    var consultant = consultantWithPersonalInfo();
    consultant.setInternalDisplayName("Anna Beispiel (Standort Nord)");

    var dto = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO().getEmbedded();

    assertThat(dto.getInternalDisplayName()).isEqualTo("Anna Beispiel (Standort Nord)");
  }

  @Test
  void buildResponseDTO_Should_includeAdminRemarks_When_OptedIn() {
    var dto =
        ConsultantResponseDTOBuilder.getInstance(consultantWithPersonalInfo())
            .includeAdminRemarks(true)
            .buildResponseDTO()
            .getEmbedded();

    assertThat(dto.getAdminRemarks()).isEqualTo("Tenant-admin-only note");
  }
}
