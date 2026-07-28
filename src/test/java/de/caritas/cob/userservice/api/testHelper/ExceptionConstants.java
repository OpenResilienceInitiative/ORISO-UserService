package de.caritas.cob.userservice.api.testHelper;

import de.caritas.cob.userservice.api.exception.httpresponses.InternalServerErrorException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpStatusCodeException;

public class ExceptionConstants {

  /** Common exceptions */
  public static final Exception EXCEPTION = new Exception();

  /** HttpStatusCode exception */
  @SuppressWarnings("serial")
  public static final HttpStatusCodeException HTTP_STATUS_CODE_INTERNAL_SERVER_ERROR_EXCEPTION =
      new HttpStatusCodeException(HttpStatus.INTERNAL_SERVER_ERROR) {};

  @SuppressWarnings("serial")
  public static final HttpStatusCodeException HTTP_STATUS_CODE_UNAUTHORIZED_EXCEPTION =
      new HttpStatusCodeException(HttpStatus.UNAUTHORIZED) {};

  /** InternalServerErrorException */
  public static final InternalServerErrorException INTERNAL_SERVER_ERROR_EXCEPTION =
      new InternalServerErrorException(EXCEPTION.getMessage());
}
