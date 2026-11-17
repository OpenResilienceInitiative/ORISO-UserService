package de.caritas.cob.userservice.api.adapters.web.controller;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminResponseDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.CreateAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.PatchAdminDTO;
import de.caritas.cob.userservice.api.adapters.web.mapping.AdminDtoMapper;
import de.caritas.cob.userservice.api.admin.service.admin.StatisticsReaderUserService;
import jakarta.validation.Valid;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only platform statistics accounts (ORISO-UserService#1666). Platform admins only — enforced
 * in SecurityConfig. Response shapes match the other admin endpoints so the Admin's shared table
 * and form plumbing parses them unchanged.
 */
@RestController
@RequestMapping({"/useradmin/statisticsreaders", "/service/useradmin/statisticsreaders"})
@RequiredArgsConstructor
public class StatisticsReaderController {

  private final @NonNull StatisticsReaderUserService statisticsReaderUserService;
  private final @NonNull AdminDtoMapper adminDtoMapper;

  @PostMapping
  public ResponseEntity<AdminResponseDTO> create(@Valid @RequestBody CreateAdminDTO body) {
    return new ResponseEntity<>(statisticsReaderUserService.create(body), HttpStatus.CREATED);
  }

  @GetMapping("/search")
  public ResponseEntity<AdminSearchResultDTO> search(
      @RequestParam(defaultValue = "*") String query,
      @RequestParam(defaultValue = "1") Integer page,
      @RequestParam(defaultValue = "20") Integer perPage,
      @RequestParam(defaultValue = "FIRSTNAME") String field,
      @RequestParam(defaultValue = "ASC") String order) {
    var decodedInfix = URLDecoder.decode(query, StandardCharsets.UTF_8).trim();
    var resultMap =
        statisticsReaderUserService.search(
            decodedInfix, PageRequest.of(Math.max(page, 1) - 1, Math.max(perPage, 1)));
    return ResponseEntity.ok(
        adminDtoMapper.adminSearchResultOf(resultMap, query, page, perPage, field, order));
  }

  @GetMapping("/{adminId}")
  public ResponseEntity<AdminResponseDTO> find(@PathVariable String adminId) {
    return ResponseEntity.ok(statisticsReaderUserService.find(adminId));
  }

  @PutMapping("/{adminId}")
  public ResponseEntity<AdminResponseDTO> update(
      @PathVariable String adminId, @Valid @RequestBody PatchAdminDTO body) {
    return ResponseEntity.ok(statisticsReaderUserService.update(adminId, body));
  }

  @DeleteMapping("/{adminId}")
  public ResponseEntity<Void> delete(@PathVariable String adminId) {
    statisticsReaderUserService.delete(adminId);
    return new ResponseEntity<>(HttpStatus.OK);
  }
}
