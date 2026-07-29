package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.RootDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionAdminResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.SessionFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ViolationDTO;
import de.caritas.cob.userservice.api.admin.hallink.RootDTOBuilder;
import de.caritas.cob.userservice.api.admin.report.service.ViolationReportGenerator;
import de.caritas.cob.userservice.api.admin.service.session.SessionAdminService;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class UserAdminQueryControllerDelegate {

  private final @NonNull SessionAdminService sessionAdminService;
  private final @NonNull ViolationReportGenerator violationReportGenerator;

  ResponseEntity<RootDTO> getRoot() {
    return ResponseEntity.ok(new RootDTOBuilder().buildRootDTO());
  }

  ResponseEntity<SessionAdminResultDTO> getSessions(
      Integer page, Integer perPage, SessionFilter sessionFilter) {
    return ResponseEntity.ok(sessionAdminService.findSessions(page, perPage, sessionFilter));
  }

  ResponseEntity<List<ViolationDTO>> generateViolationReport() {
    return ResponseEntity.ok(violationReportGenerator.generateReport());
  }
}
