package de.caritas.cob.userservice.api.picture;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "consultant.picture.scanner")
public class PictureScannerProperties {
  private boolean enabled = false;
  private int port = 3310;
  private int timeoutMillis = 5000;
}
