package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.UsernameTranscoder;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class EncodeUsernameJsonDeserializer extends ValueDeserializer<String> {

  private static final String ERROR_USERNAME_INVALID_LENGTH =
      "Please provide a username with at least 5 and at most 30 characters";

  @Override
  public String deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
      throws JacksonException {
    // MATRIX MIGRATION: Capture plain username before encoding for Matrix localpart creation.
    String plainUsername = jsonParser.getValueAsString();

    de.caritas.cob.userservice.api.helper.PlainCredentialsHolder.set(plainUsername, null);

    String username = new UsernameTranscoder().encodeUsername(plainUsername);

    // Check if username is of valid length
    var decodedUsername = new UsernameTranscoder().decodeUsername(username);
    if (decodedUsername.length() < 5 || decodedUsername.length() > 30) {
      throw new BadRequestException(ERROR_USERNAME_INVALID_LENGTH);
    }

    return username;
  }
}
