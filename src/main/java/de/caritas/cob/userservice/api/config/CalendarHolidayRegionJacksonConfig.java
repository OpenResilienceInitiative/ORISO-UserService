package de.caritas.cob.userservice.api.config;

import de.caritas.cob.userservice.api.adapters.web.dto.CalendarWorkingHoursDTO.HolidayRegionEnum;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Reads the holiday region of the working hours (ADR-032, contract addendum v1.2) strictly. The
 * spec declares it nullable, so the generated {@link HolidayRegionEnum#fromValue} answers null for
 * an unknown code, and a typo such as "BAY" would silently switch holidays off with a 200. Here an
 * unknown code is unreadable input, which the API answers with 400; JSON null and an absent field
 * still mean "no holidays off".
 */
@Configuration
public class CalendarHolidayRegionJacksonConfig {

  @Bean
  public JacksonModule calendarHolidayRegionModule() {
    return new SimpleModule("CalendarHolidayRegion")
        .addDeserializer(HolidayRegionEnum.class, new StrictHolidayRegionDeserializer());
  }

  static class StrictHolidayRegionDeserializer extends ValueDeserializer<HolidayRegionEnum> {

    @Override
    public HolidayRegionEnum deserialize(JsonParser parser, DeserializationContext context) {
      if (parser.currentToken() != JsonToken.VALUE_STRING) {
        return (HolidayRegionEnum) context.handleUnexpectedToken(HolidayRegionEnum.class, parser);
      }
      var code = parser.getString();
      for (var region : HolidayRegionEnum.values()) {
        if (region.getValue().equals(code)) {
          return region;
        }
      }
      return context.reportInputMismatch(
          HolidayRegionEnum.class, "holidayRegion is not a Bundesland code.");
    }
  }
}
