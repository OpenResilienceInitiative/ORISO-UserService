package de.caritas.cob.userservice.api.admin.service.consultant.create;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreateConsultantDTOCreationInputAdapterTest {

  private static final String USERNAME = "consultantUser";
  private static final String PASSWORD = "SecurePass1!";

  @Test
  void getters_Should_returnExpectedValues_When_dtoFieldsAreSet() {
    CreateConsultantDTO dto = new CreateConsultantDTO();
    dto.setUsername(USERNAME);
    dto.setFirstname("First");
    dto.setLastname("Last");
    dto.setEmail("consultant@example.com");
    dto.setPassword(PASSWORD);
    dto.setAbsent(true);
    dto.setAbsenceMessage("Away");
    dto.setFormalLanguage(true);
    dto.setTenantId(3L);
    dto.setTopicIds(List.of(10L, 20L));
    dto.setAgencyIds(List.of(30L, 40L));

    ConsultantCreationInput input = new CreateConsultantDTOCreationInputAdapter(dto);

    assertThat(input.getIdOld(), nullValue());
    assertThat(input.getUserName(), is(USERNAME));
    assertThat(input.getEncodedUsername(), is(new UsernameTranscoder().encodeUsername(USERNAME)));
    assertThat(input.getFirstName(), is("First"));
    assertThat(input.getLastName(), is("Last"));
    assertThat(input.getEmail(), is("consultant@example.com"));
    assertThat(input.getPassword(), is(PASSWORD));
    assertThat(input.shouldGeneratePassword(), is(false));
    assertThat(input.isAbsent(), is(true));
    assertThat(input.getAbsenceMessage(), is("Away"));
    assertThat(input.isTeamConsultant(), is(false));
    assertThat(input.isLanguageFormal(), is(true));
    assertThat(input.getTenantId(), is(3L));
    assertThat(input.getTopicIds(), is(List.of(10L, 20L)));
    assertThat(input.getAgencyIds(), is(List.of(30L, 40L)));
    assertThat(input.getCreateDate(), notNullValue());
    assertThat(input.getUpdateDate(), notNullValue());
  }

  @Test
  void booleanFlags_Should_defaultToFalse_When_dtoFlagsAreNull() {
    CreateConsultantDTO dto = new CreateConsultantDTO();
    dto.setUsername(USERNAME);
    dto.setAbsent(null);
    dto.setFormalLanguage(null);

    ConsultantCreationInput input = new CreateConsultantDTOCreationInputAdapter(dto);

    assertThat(input.isAbsent(), is(false));
    assertThat(input.isLanguageFormal(), is(false));
  }
}
