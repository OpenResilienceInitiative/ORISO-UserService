package de.caritas.cob.userservice.api.workflow.accountinactivity;

/** Structured safe diagnostics; never persist external response bodies or credentials. */
public class AccountInactivityEffectException extends RuntimeException {
  public enum Target {
    KEYCLOAK,
    MATRIX,
    MEDIA,
    DATABASE,
    APPOINTMENT_SERVICE,
    ANONYMOUS_REGISTRY_IDS,
    USER_CONTENT,
    OTHER
  }

  public enum Code {
    FAILED,
    UNCONFIRMED,
    ROLE_CHANGED
  }

  private final Target target;
  private final Code code;

  public AccountInactivityEffectException(Target target, Code code) {
    super(target.name() + ":" + code.name());
    this.target = target;
    this.code = code;
  }

  public Target target() {
    return target;
  }

  public Code code() {
    return code;
  }
}
