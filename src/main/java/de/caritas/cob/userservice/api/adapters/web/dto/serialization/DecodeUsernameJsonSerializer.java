package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class DecodeUsernameJsonSerializer extends ValueSerializer<String> {

  @Override
  public void serialize(String username, JsonGenerator jsonGenerator, SerializationContext context)
      throws JacksonException {
    jsonGenerator.writeString(new UsernameTranscoder().decodeUsername(username));
  }
}
