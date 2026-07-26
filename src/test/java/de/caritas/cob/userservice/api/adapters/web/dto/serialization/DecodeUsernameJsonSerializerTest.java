package de.caritas.cob.userservice.api.adapters.web.dto.serialization;

import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_DECODED;
import static de.caritas.cob.userservice.api.testHelper.TestConstants.USERNAME_ENCODED;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import de.caritas.cob.userservice.api.helper.UserHelper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;

@ExtendWith(MockitoExtension.class)
public class DecodeUsernameJsonSerializerTest {

  @InjectMocks private DecodeUsernameJsonSerializer serializer;
  @Mock private JsonGenerator jsonGenerator;
  @Mock private UserHelper userHelper;

  @Test
  public void serialize_Schould_DecodeEncodedUsername() throws JacksonException {
    serializer.serialize(USERNAME_ENCODED, jsonGenerator, null);

    verify(jsonGenerator, times(1)).writeString(USERNAME_DECODED);
  }

  @Test
  public void serialize_SchouldNot_DecodeDecodedUsername() throws JacksonException {
    serializer.serialize(USERNAME_DECODED, jsonGenerator, null);

    verify(jsonGenerator, times(1)).writeString(USERNAME_DECODED);
  }
}
