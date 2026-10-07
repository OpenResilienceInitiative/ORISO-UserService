package de.caritas.cob.userservice.api.config.auth;

import jakarta.annotation.PostConstruct;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Fail-closed explicit responsibility-to-credential mapping. No legacy credential fallback. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "identity")
public class TaskIdentityConfiguration {
  private Map<String, TaskIdentityCredentials> tasks = new LinkedHashMap<>();

  public TaskIdentityCredentials require(TaskIdentity task) {
    var configured = tasks.get(task.key());
    if (configured == null
        || blank(configured.getClientId())
        || blank(configured.getClientSecret())
        || blank(configured.getServiceSubject())) {
      throw new IllegalStateException("Missing identity configuration for " + task.key());
    }
    configured.bindTask(task);
    return configured;
  }

  @PostConstruct
  public void validate() {
    var clients = new HashSet<String>();
    var secrets = new HashSet<String>();
    var subjects = new HashSet<String>();
    for (var task : TaskIdentity.values()) {
      var credential = require(task);
      if (!clients.add(credential.getClientId())
          || !secrets.add(credential.getClientSecret())
          || !subjects.add(credential.getServiceSubject())) {
        throw new IllegalStateException(
            "Task identity credentials must be distinct: " + task.key());
      }
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
