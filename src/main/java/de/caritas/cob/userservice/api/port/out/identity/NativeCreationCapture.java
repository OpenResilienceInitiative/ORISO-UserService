package de.caritas.cob.userservice.api.port.out.identity;

/** Explicit durable observations for one native creation attempt. */
public interface NativeCreationCapture {

  /** Records the returned identity before any later identity mutation. */
  void captured(CreatedIdentity identity);

  /** Records intent before a destructive compensation call is permitted. */
  void compensationStarting();

  /** Records only that compensation returned, never that the identity is absent. */
  void compensationReturned();
}
