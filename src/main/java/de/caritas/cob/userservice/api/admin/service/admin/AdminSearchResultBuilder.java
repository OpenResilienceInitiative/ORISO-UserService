package de.caritas.cob.userservice.api.admin.service.admin;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.HalLink;
import de.caritas.cob.userservice.api.adapters.web.dto.PaginationLinks;
import de.caritas.cob.userservice.api.admin.service.SearchResultBuilder;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.generated.api.adapters.web.controller.UseradminApi;
import java.util.List;
import java.util.stream.Collectors;

public class AdminSearchResultBuilder
    extends SearchResultBuilder<AdminFilter, AdminSearchResultDTO> {
  private final List<Admin> admins;
  private final long totalCount;

  private AdminSearchResultBuilder(List<Admin> admins, long totalCount) {
    super(null);
    this.admins = admins;
    this.totalCount = totalCount;
  }

  public static AdminSearchResultBuilder getInstance(List<Admin> admins, long totalCount) {
    return new AdminSearchResultBuilder(admins, totalCount);
  }

  public AdminSearchResultDTO buildSearchResult() {
    var resultList =
        admins.stream()
            .map(AdminResponseDTOBuilder::getInstance)
            .map(AdminResponseDTOBuilder::buildAgencyAdminResponseDTO)
            .collect(Collectors.toList());

    var paginationLinks =
        new PaginationLinks()
            .self(buildPageLink(page))
            .next(hasNext() ? buildPageLink(page + 1) : null)
            .previous(page > 1 ? buildPageLink(page - 1) : null);

    return new AdminSearchResultDTO()
        .embedded(resultList)
        .links(paginationLinks)
        .total((int) totalCount);
  }

  private boolean hasNext() {
    return totalCount > (long) page * perPage;
  }

  private HalLink buildPageLink(int targetPage) {
    return super.buildSelfLink(
        methodOn(UseradminApi.class).getAgencyAdmins(targetPage, perPage, filter, sort));
  }
}
