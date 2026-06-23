package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.PASSWORD;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.PASSWORD_URL_ENCODED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class UrlDecodePasswordJsonSerializationUtilsTest {

  private ObjectMapper objectMapper;
  private UrlDecodePasswordJsonDeserializer urlDecodePasswordJsonDeserializer;

  @BeforeEach
  public void setup() {
    PlainCredentialsHolder.clear();
    objectMapper = new ObjectMapper();
    urlDecodePasswordJsonDeserializer = new UrlDecodePasswordJsonDeserializer();
  }

  @AfterEach
  public void tearDown() {
    PlainCredentialsHolder.clear();
  }

  @Test
  public void deserialize_Schould_EncodePassword() throws JsonParseException, IOException {
    String json = "{\"password:\":\"" + PASSWORD_URL_ENCODED + "\"}";
    String result = deserializePassword(json);
    assertEquals(PASSWORD, result);
  }

  @Test
  public void deserialize_ShouldNot_StorePasswordInPlainCredentialsHolder()
      throws JsonParseException, IOException {
    PlainCredentialsHolder.set("plain-user", null);

    String json = "{\"password:\":\"" + PASSWORD_URL_ENCODED + "\"}";
    String result = deserializePassword(json);

    assertEquals(PASSWORD, result);
    assertEquals("plain-user", PlainCredentialsHolder.get().getUsername());
    assertNull(PlainCredentialsHolder.get().getPassword());
  }

  private String deserializePassword(String json) throws JsonParseException, IOException {
    InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    JsonParser jsonParser = objectMapper.getFactory().createParser(stream);
    jsonParser.nextToken();
    jsonParser.nextToken();
    jsonParser.nextToken();
    DeserializationContext deserializationContext = objectMapper.getDeserializationContext();
    return urlDecodePasswordJsonDeserializer.deserialize(jsonParser, deserializationContext);
  }
}
