package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.service.admin.SupportAdminUserService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global-Support-Admin listing (ADR-018). Response shape matches the other admin searches so the
 * Admin panel's shared table plumbing parses it unchanged.
 */
@RestController
@RequestMapping("/useradmin/supportadmins")
@RequiredArgsConstructor
public class SupportAdminController {

  private final @NonNull SupportAdminUserService supportAdminUserService;
  private final @NonNull AdminDtoMapper adminDtoMapper;

  @GetMapping("/search")
  public ResponseEntity<AdminSearchResultDTO> searchSupportAdmins(
      @RequestParam(defaultValue = "*") String query,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "20") Integer perPage,
      @RequestParam(defaultValue = "FIRSTNAME") String field,
      @RequestParam(defaultValue = "ASC") String order) {
    var decodedInfix = URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
    var resultMap =
        supportAdminUserService.findSupportAdminsByInfix(
            decodedInfix, PageRequest.of(page - 1, perPage));
    return ResponseEntity.ok(
        adminDtoMapper.adminSearchResultOf(resultMap, query, page, perPage, field, order));
  }
}
