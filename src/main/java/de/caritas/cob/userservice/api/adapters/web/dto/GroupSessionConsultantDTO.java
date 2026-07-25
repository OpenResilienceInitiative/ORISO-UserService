package de.caritas.cob.userservice.api.adapters.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import de.caritas.cob.userservice.api.adapters.web.dto.serialization.DecodeUsernameJsonSerializer;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonSerialize;

@ApiModel(value = "GroupSessionConsultant")
@JsonInclude(Include.NON_NULL)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Getter
public class GroupSessionConsultantDTO {

  @ApiModelProperty(example = "\"Username\"")
  @JsonSerialize(using = DecodeUsernameJsonSerializer.class)
  private String username;

  @ApiModelProperty(example = "\"true\"")
  private boolean isAbsent;

  @ApiModelProperty(example = "\"Bin nicht da\"")
  private String absenceMessage;

  private String displayName;

  private String firstName;

  private String lastName;

  private String id;
}
