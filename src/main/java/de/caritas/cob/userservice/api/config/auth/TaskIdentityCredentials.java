package de.caritas.cob.userservice.api.config.auth;

import lombok.Data;
import lombok.NoArgsConstructor;

/** Distinct service-account credentials; diagnostic output never includes the secret. */
@Data
@NoArgsConstructor
public class TaskIdentityCredentials {
  @lombok.Setter(lombok.AccessLevel.NONE)
  private TaskIdentity task;

  public TaskIdentityCredentials(String clientId, String clientSecret, String serviceSubject) {
    this.clientId = clientId;
    this.clientSecret = clientSecret;
    this.serviceSubject = serviceSubject;
  }

  void bindTask(TaskIdentity task) {
    if (this.task != null && this.task != task)
      throw new IllegalStateException("Task credential binding cannot be reused");
    this.task = task;
  }

  private String clientId;
  @lombok.ToString.Exclude private String clientSecret;
  private String serviceSubject;
}
