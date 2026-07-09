package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import de.caritas.cob.userservice.api.helper.Helper;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UrlDecodePasswordJsonDeserializer extends JsonDeserializer<String> {

  private final Helper helper;

  @Override
  public String deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws IOException {
    String password = jsonParser.getValueAsString();
    return helper.urlDecodeString(password);
  }
}
