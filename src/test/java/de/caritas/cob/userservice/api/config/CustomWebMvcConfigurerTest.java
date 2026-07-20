package de.caritas.cob.userservice.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.caritas.cob.userservice.api.adapters.web.dto.ConversationType;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionDTO;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;

class CustomWebMvcConfigurerTest {

  @Test
  void extendMessageConvertersShouldSerializeJsonNullableAsApiValueWithJackson3() throws Exception {
    var converters = new ArrayList<HttpMessageConverter<?>>();
    converters.add(new JacksonJsonHttpMessageConverter());
    var configurer = new CustomWebMvcConfigurer(mock(ObjectProvider.class));

    configurer.extendMessageConverters(converters);

    var converter = (JacksonJsonHttpMessageConverter) converters.getFirst();
    var json =
        converter
            .getMapper()
            .writeValueAsString(new SessionDTO().conversationType(ConversationType.LIVE_CHAT));
    assertThat(json)
        .contains("\"conversationType\":\"LIVE_CHAT\"")
        .doesNotContain("\"conversationType\":{\"present\":true}");
  }
}
