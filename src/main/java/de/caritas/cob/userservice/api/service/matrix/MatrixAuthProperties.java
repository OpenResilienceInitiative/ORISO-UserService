package de.caritas.cob.userservice.api.service.matrix;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "matrix.auth")
public class MatrixAuthProperties {

  private boolean consultantTokenEnabled = true;
  private boolean userTokenEnabled = true;
  private long browserTokenTtlMs = 55 * 60 * 1000L;
}
