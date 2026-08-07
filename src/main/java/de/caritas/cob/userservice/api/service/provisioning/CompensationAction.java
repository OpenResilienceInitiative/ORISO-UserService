package de.caritas.cob.userservice.api.service.provisioning;

@FunctionalInterface
public interface CompensationAction {

  void compensate() throws Exception;
}
