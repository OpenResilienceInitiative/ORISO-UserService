package de.caritas.cob.userservice.api.admin.service.consultant;

import static org.assertj.core.api.Assertions.assertThat;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantDTO;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.model.ConsultantAvatarKind;
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
  void buildResponseDTO_Should_reportAMissingChatIdentity() {
    var consultant = consultantWithPersonalInfo();
    consultant.setMatrixUserId(null);

    var dto = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO().getEmbedded();

    assertThat(dto.getChatIdentityStatus()).isEqualTo(ConsultantDTO.ChatIdentityStatusEnum.MISSING);
  }

  @Test
  void buildResponseDTO_Should_reportABlankChatIdentityAsMissing() {
    var consultant = consultantWithPersonalInfo();
    consultant.setMatrixUserId("  ");

    var dto = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO().getEmbedded();

    assertThat(dto.getChatIdentityStatus()).isEqualTo(ConsultantDTO.ChatIdentityStatusEnum.MISSING);
  }

  @Test
  void buildResponseDTO_Should_reportAProvisionedChatIdentity() {
    var consultant = consultantWithPersonalInfo();
    consultant.setMatrixUserId("@anna.beispiel:matrix.local");

    var dto = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO().getEmbedded();

    assertThat(dto.getChatIdentityStatus())
        .isEqualTo(ConsultantDTO.ChatIdentityStatusEnum.PROVISIONED);
  }

  @Test
  void buildResponseDTO_Should_mapAvatarChoice() {
    var consultant = consultantWithPersonalInfo();
    consultant.setAvatarKind(ConsultantAvatarKind.ICON);
    consultant.setAvatarId("motif-24");

    var dto = ConsultantResponseDTOBuilder.getInstance(consultant).buildResponseDTO().getEmbedded();

    assertThat(dto.getAvatarKind()).isEqualTo(ConsultantDTO.AvatarKindEnum.ICON);
    assertThat(dto.getAvatarId()).isEqualTo("motif-24");
  }

  @Test
  void buildResponseDTO_Should_leaveAvatarUnset_When_noChoiceWasMade() {
    var dto =
        ConsultantResponseDTOBuilder.getInstance(consultantWithPersonalInfo())
            .buildResponseDTO()
            .getEmbedded();

    assertThat(dto.getAvatarKind()).isNull();
    assertThat(dto.getAvatarId()).isNull();
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
