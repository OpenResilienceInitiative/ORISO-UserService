package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO.HolidayRegionEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

/** The holiday region of the working hours (addendum v1.2) through Boot's own JSON mapper. */
class CalendarHolidayRegionJacksonConfigTest {

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(CalendarHolidayRegionJacksonConfig.class);

  @Test
  void everyBundeslandCode_Should_BeRead() {
    context.run(
        ctx -> {
          var mapper = ctx.getBean(JsonMapper.class);
          for (var region : HolidayRegionEnum.values()) {
            assertThat(read(mapper, "\"" + region.getValue() + "\"").getHolidayRegion())
                .isEqualTo(region);
          }
          assertThat(HolidayRegionEnum.values()).hasSize(16);
        });
  }

  @Test
  void nullOrAnAbsentRegion_Should_MeanNoHolidaysOff() {
    context.run(
        ctx -> {
          var mapper = ctx.getBean(JsonMapper.class);
          assertThat(read(mapper, "null").getHolidayRegion()).isNull();
          assertThat(
                  mapper
                      .readValue("{\"windows\":[]}", CalendarWorkingHoursDTO.class)
                      .getHolidayRegion())
              .isNull();
        });
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"XX\"", "\"by\"", "\"BAY\"", "\"\"", "1", "true", "[\"BY\"]"})
  void anythingElse_Should_BeUnreadable(String value) {
    context.run(
        ctx -> {
          var mapper = ctx.getBean(JsonMapper.class);
          assertThatThrownBy(() -> read(mapper, value)).isInstanceOf(DatabindException.class);
        });
  }

  private static CalendarWorkingHoursDTO read(JsonMapper mapper, String region) {
    return mapper.readValue(
        "{\"windows\":[],\"holidayRegion\":" + region + "}", CalendarWorkingHoursDTO.class);
  }
}
