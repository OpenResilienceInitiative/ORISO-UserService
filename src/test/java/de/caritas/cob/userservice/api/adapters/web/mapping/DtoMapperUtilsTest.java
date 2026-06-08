package de.caritas.cob.userservice.api.adapters.web.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DtoMapperUtilsTest implements DtoMapperUtils {

  @Test
  void mappedFieldOf_Should_MapUpdateDate() {
    assertThat(mappedFieldOf("UPDATE_DATE")).isEqualTo("updateDate");
  }
}
