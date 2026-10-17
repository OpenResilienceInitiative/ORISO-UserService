package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;

/** A selected centre does not exist. */
public class UnknownAgencyException extends BadRequestException {

  private static final long serialVersionUID = 1L;

  public UnknownAgencyException(String message) {
    super(message);
  }
}
