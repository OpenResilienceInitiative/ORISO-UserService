package de.caritas.cob.userservice.api.exception;

/** Transport-neutral failure raised by an outbound messaging-account operation. */
public class MessageClientException extends Exception {

  private static final long serialVersionUID = 1L;

  public MessageClientException(Exception cause) {
    super(cause);
  }

  public MessageClientException(String message) {
    super(message);
  }
}
