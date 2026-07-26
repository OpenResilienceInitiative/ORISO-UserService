package de.caritas.cob.userservice.api.port.out.identity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Provider-neutral identity profile used by the user-data application boundary. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IdentityUserProfile {

  private String id;
  private String username;
  private String firstName;
  private String lastName;
  private String email;
}
