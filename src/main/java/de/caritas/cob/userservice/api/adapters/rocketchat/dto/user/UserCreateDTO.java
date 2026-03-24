package de.caritas.cob.userservice.api.adapters.rocketchat.dto.user;

import lombok.Data;

@Data
public class UserCreateDTO {
  private String name;
  private String email;
  private String password;
  private String username;
}
