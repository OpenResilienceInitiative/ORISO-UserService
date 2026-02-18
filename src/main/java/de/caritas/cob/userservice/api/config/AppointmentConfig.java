package de.caritas.cob.userservice.api.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "appointments")
public class AppointmentConfig {

  @NotNull @Positive private Integer lifespanInHours;
  @NotNull private Boolean deleteJobEnabled;
  @NotBlank private String deleteJobCron;
}
