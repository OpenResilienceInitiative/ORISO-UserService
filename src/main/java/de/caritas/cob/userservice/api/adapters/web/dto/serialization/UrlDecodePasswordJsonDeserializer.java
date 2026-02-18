package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import de.caritas.cob.userservice.api.helper.Helper;
import java.io.IOException;

public class UrlDecodePasswordJsonDeserializer extends JsonDeserializer<String> {

  private Helper helper = new Helper();

  @Override
  public String deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws IOException, JsonProcessingException {
    String password = jsonParser.getValueAsString();
    String decodedPassword = helper.urlDecodeString(password);

    // MATRIX MIGRATION: Store plain password in ThreadLocal for Matrix user creation
    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.PlainCredentials current =
        de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.get();
    if (current != null) {
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.set(
          current.getUsername(), decodedPassword);
    } else {
      de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.set(null, decodedPassword);
    }

    return decodedPassword;
  }
}
