package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import de.caritas.cob.userservice.api.service.ConsultantImportService.ImportRecord;
import org.junit.jupiter.api.Test;

class ImportRecordCreationInputAdapterTest {

  @Test
  void getters_Should_returnExpectedValues_When_importRecordFieldsAreSet() {
    ImportRecord importRecord = new ImportRecord();
    importRecord.setIdOld(42L);
    importRecord.setUsername("plainUsername");
    importRecord.setUsernameEncoded("encodedUsername");
    importRecord.setFirstName("Import");
    importRecord.setLastName("Record");
    importRecord.setEmail("import@example.com");
    importRecord.setAbsent(true);
    importRecord.setAbsenceMessage("On leave");
    importRecord.setTeamConsultant(true);
    importRecord.setFormalLanguage(true);
    importRecord.setTenantId(7L);

    ConsultantCreationInput input = new ImportRecordCreationInputAdapter(importRecord);

    assertThat(input.getIdOld(), is(42L));
    assertThat(input.getUserName(), is("plainUsername"));
    assertThat(input.getEncodedUsername(), is("encodedUsername"));
    assertThat(input.getFirstName(), is("Import"));
    assertThat(input.getLastName(), is("Record"));
    assertThat(input.getEmail(), is("import@example.com"));
    assertThat(input.getPassword(), nullValue());
    assertThat(input.isAbsent(), is(true));
    assertThat(input.getAbsenceMessage(), is("On leave"));
    assertThat(input.isTeamConsultant(), is(true));
    assertThat(input.isLanguageFormal(), is(true));
    assertThat(input.getTenantId(), is(7L));
    assertThat(input.getTopicIds(), nullValue());
    assertThat(input.getCreateDate(), notNullValue());
    assertThat(input.getUpdateDate(), notNullValue());
  }
}
