package de.caritas.cob.userservice.api.adapters.web.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ApiModel(value = "SessionUser")
public class SessionUserDTO {

  @ApiModelProperty(example = "id", position = 0)
  private String id;

  @ApiModelProperty(example = "Username", position = 1)
  private String username;

  @ApiModelProperty(example = "Display name", position = 2)
  private String displayName;

  @ApiModelProperty(example = "isDeleted", position = 3)
  private boolean isDeleted;

  private Map<String, Object> sessionData;

  @ApiModelProperty("Stored animal id; null uses the default avatar")
  private String avatarId;

  /**
   * Set when the asker is a direct-link temporary account (ADR-029, #1476): the retention mode, the
   * guest's answer and whether the account is already deleted ("Gast (gelöscht)").
   */
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
  private TemporaryAccountDTO temporaryAccount;
}
