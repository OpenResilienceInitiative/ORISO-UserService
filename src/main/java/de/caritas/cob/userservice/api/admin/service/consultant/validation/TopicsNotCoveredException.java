package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;

/** Requested topics are not offered by any of the selected centres. */
public class TopicsNotCoveredException extends BadRequestException {

  private static final long serialVersionUID = 1L;

  public TopicsNotCoveredException(String message) {
    super(message);
  }
}
