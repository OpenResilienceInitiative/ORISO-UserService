package de.caritas.cob.userservice.api.admin.service.consultant.validation;

import de.caritas.cob.userservice.api.exception.httpresponses.BadRequestException;

/** A selected centre belongs to a Träger other than the one it is assigned in. */
public class AgencyOutsideTenantException extends BadRequestException {

  private static final long serialVersionUID = 1L;

  public AgencyOutsideTenantException(String message) {
    super(message);
  }
}
