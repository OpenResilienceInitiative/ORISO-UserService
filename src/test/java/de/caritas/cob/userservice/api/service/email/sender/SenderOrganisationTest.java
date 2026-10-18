package de.caritas.cob.userservice.api.service.email.sender;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SenderOrganisationTest {

  @Test
  void blankValuesAreNoValues() {
    SenderOrganisation organisation = new SenderOrganisation("  ", "", " Weg 1 ");

    assertThat(organisation.name()).isNull();
    assertThat(organisation.address()).isNull();
    assertThat(organisation.contactLine()).isEqualTo("Weg 1");
  }

  @Test
  void overriddenBy_replacesOnlyTheFieldsTheOverrideHas() {
    SenderOrganisation base =
        new SenderOrganisation("Betreiber", "Betreiberweg 1", "info@b.example");

    SenderOrganisation merged = base.overriddenBy(new SenderOrganisation("Träger", null, null));

    assertThat(merged)
        .isEqualTo(new SenderOrganisation("Träger", "Betreiberweg 1", "info@b.example"));
  }

  @org.junit.jupiter.api.Test
  void contactLine_joinsWhatWasEntered_andIsNullWhenNothingWas() {
    org.assertj.core.api.Assertions.assertThat(
            SenderOrganisation.contactLine(" a@b.example ", "+49 1"))
        .isEqualTo("a@b.example · +49 1");
    org.assertj.core.api.Assertions.assertThat(SenderOrganisation.contactLine("", "+49 1"))
        .isEqualTo("+49 1");
    org.assertj.core.api.Assertions.assertThat(SenderOrganisation.contactLine(null, " ")).isNull();
  }
}
