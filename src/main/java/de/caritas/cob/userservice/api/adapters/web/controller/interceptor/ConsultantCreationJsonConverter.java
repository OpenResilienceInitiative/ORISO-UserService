package de.caritas.cob.userservice.api.adapters.web.controller.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.caritas.cob.userservice.api.adapters.web.controller.dto.SafeCreateConsultantDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateConsultantDTO;
import de.caritas.cob.userservice.api.service.creationoutcome.CreationOperationAccess;
import java.io.*;
import org.springframework.http.*;
import org.springframework.http.converter.*;

/** Sanitizes decoding failures before request advice, validation or MVC logging can render them. */
public final class ConsultantCreationJsonConverter
    extends AbstractHttpMessageConverter<CreateConsultantDTO> {
  private final ObjectMapper mapper;

  public ConsultantCreationJsonConverter(ObjectMapper mapper) {
    super(MediaType.APPLICATION_JSON, new MediaType("application", "*+json"));
    this.mapper = mapper;
  }

  @Override
  protected boolean supports(Class<?> clazz) {
    return CreateConsultantDTO.class.isAssignableFrom(clazz);
  }

  @Override
  public boolean canWrite(Class<?> clazz, MediaType type) {
    return false;
  }

  @Override
  protected CreateConsultantDTO readInternal(
      Class<? extends CreateConsultantDTO> clazz, HttpInputMessage input) {
    try {
      var keys = input.getHeaders().get(CreationOperationAccess.KEY_HEADER);
      boolean tracked = keys != null;
      if (tracked) {
        if (keys.size() != 1) throw CreationOperationAccess.invalid();
        CreationOperationAccess.parseKey(keys.getFirst());
      }
      InputStream stream = input.getBody();
      if (tracked) {
        // Read at most one byte beyond the limit, regardless of Content-Length or chunking.
        byte[] bytes = stream.readNBytes(65537);
        if (bytes.length > 65536) throw CreationOperationAccess.invalid();
        stream = new ByteArrayInputStream(bytes);
      }
      var dto = mapper.readValue(stream, SafeCreateConsultantDTO.class);
      if (tracked && dto != null) CreationOperationAccess.validateBounds(dto);
      return dto;
    } catch (Exception failure) {
      throw new HttpMessageNotReadableException("INVALID_CREATION_REQUEST", input);
    }
  }

  @Override
  protected void writeInternal(CreateConsultantDTO dto, HttpOutputMessage output) {
    throw new UnsupportedOperationException();
  }
}
