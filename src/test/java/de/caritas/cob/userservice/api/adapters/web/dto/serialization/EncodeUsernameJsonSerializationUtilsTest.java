package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_DECODED;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_ENCODED;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_TOO_LONG;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_TOO_SHORT;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;
import de.caritas.cob.userservice.api.helper.PlainCredentialsHolder;
import de.caritas.cob.userservice.api.helper.UserHelper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EncodeUsernameJsonSerializationUtilsTest {

  private ObjectMapper objectMapper;

  @InjectMocks private EncodeUsernameJsonDeserializer encodeUsernameJsonDeserializer;

  @Mock private UserHelper userHelper;

  @BeforeEach
  public void setup() {
    PlainCredentialsHolder.clear();
    objectMapper = new ObjectMapper();
  }

  @AfterEach
  public void tearDown() {
    PlainCredentialsHolder.clear();
  }

  @Test
  public void deserialize_Should_EncodeDecodedUsername() throws IOException {
    when(userHelper.isUsernameValid(anyString())).thenReturn(true);

    String json = "{\"username:\":\"" + USERNAME_DECODED + "\"}";
    String result = deserializeUsername(json);
    assertEquals(USERNAME_ENCODED, result);
  }

  @Test
  public void deserialize_Should_ClearPlainPasswordFromPlainCredentialsHolder() throws IOException {
    when(userHelper.isUsernameValid(anyString())).thenReturn(true);
    PlainCredentialsHolder.set(null, "platform-password");

    String json = "{\"username:\":\"" + USERNAME_DECODED + "\"}";
    deserializeUsername(json);

    assertEquals(USERNAME_DECODED, PlainCredentialsHolder.get().getUsername());
    assertNull(PlainCredentialsHolder.get().getPassword());
  }

  @Test
  public void deserialize_ShouldNot_ReencodeEncodedUsername() throws IOException {
    when(userHelper.isUsernameValid(anyString())).thenReturn(true);

    String json = "{\"username:\":\"" + USERNAME_ENCODED + "\"}";
    String result = deserializeUsername(json);
    assertEquals(USERNAME_ENCODED, result);
  }

  @Test
  public void deserialize_Should_ThrowBadRequestException_WhenUsernameIsTooShort()
      throws IOException {

    try {
      String json = "{\"username:\":\"" + USERNAME_TOO_SHORT + "\"}";
      deserializeUsername(json);

      fail("Expected exception: BadRequestException");
    } catch (BadRequestException badRequestException) {
      assertTrue(true, "Excepted BadRequestException thrown");
    }
  }

  @Test
  public void deserialize_Should_ThrowBadRequestException_WhenUsernameIsTooLong()
      throws IOException {

    try {
      String json = "{\"username:\":\"" + USERNAME_TOO_LONG + "\"}";
      deserializeUsername(json);

      fail("Expected exception: BadRequestException");
    } catch (BadRequestException badRequestException) {
      assertTrue(true, "Excepted BadRequestException thrown");
    }
  }

  private String deserializeUsername(String json) throws IOException {
    InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    JsonParser jsonParser = objectMapper.getFactory().createParser(stream);
    jsonParser.nextToken();
    jsonParser.nextToken();
    jsonParser.nextToken();
    DeserializationContext deserializationContext = objectMapper.getDeserializationContext();
    return encodeUsernameJsonDeserializer.deserialize(jsonParser, deserializationContext);
  }
}
