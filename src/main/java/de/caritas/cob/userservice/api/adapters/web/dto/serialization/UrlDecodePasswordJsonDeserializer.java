package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import de.caritas.cob.userservice.api.helper.Helper;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class UrlDecodePasswordJsonDeserializer extends ValueDeserializer<String> {

  @Override
  public String deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws JacksonException {
    String password = jsonParser.getValueAsString();
    return new Helper().urlDecodeString(password);
  }
}
