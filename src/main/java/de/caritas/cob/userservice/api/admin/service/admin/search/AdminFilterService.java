package de.caritas.cob.userservice.api.admin.service.admin.search;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.AdminFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.AdminSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.FieldEnum;
import de.caritas.cob.userservice.api.admin.service.admin.AdminSearchResultBuilder;
import de.caritas.cob.userservice.api.admin.service.admin.search.querybuilder.AdminFilterSpecification;
import de.caritas.cob.userservice.api.model.Admin;
import de.caritas.cob.userservice.api.port.out.AdminRepository;
import java.util.function.Predicate;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminFilterService {

  private final @NonNull AdminRepository adminRepository;

  public AdminSearchResultDTO findFilteredAdmins(
      final Integer page, final Integer perPage, final AdminFilter adminFilter, Sort sort) {
    sort = getValidSorter(sort);
    var pageRequest = PageRequest.of(Math.max(page - 1, 0), Math.max(perPage, 1), buildSort(sort));
    var resultPage = adminRepository.findAll(buildSpecification(adminFilter), pageRequest);

    return AdminSearchResultBuilder.getInstance(
            resultPage.getContent(), resultPage.getTotalElements())
        .withFilter(adminFilter)
        .withSort(sort)
        .withPage(page)
        .withPerPage(perPage)
        .buildSearchResult();
  }

  protected Specification<Admin> buildSpecification(AdminFilter adminFilter) {
    return AdminFilterSpecification.of(adminFilter);
  }

  private org.springframework.data.domain.Sort buildSort(Sort sort) {
    if (nonNull(sort) && nonNull(sort.getField())) {
      var direction = Sort.OrderEnum.DESC.equals(sort.getOrder()) ? Direction.DESC : Direction.ASC;
      return org.springframework.data.domain.Sort.by(direction, sort.getField().getValue());
    }
    return org.springframework.data.domain.Sort.unsorted();
  }

  private Sort getValidSorter(Sort sort) {
    if (sort == null
        || Stream.of(Sort.FieldEnum.values()).noneMatch(providedSortFieldIgnoringCase(sort))) {
      sort = new Sort();
      sort.setField(Sort.FieldEnum.LAST_NAME);
      sort.setOrder(Sort.OrderEnum.ASC);
    }
    return sort;
  }

  private Predicate<FieldEnum> providedSortFieldIgnoringCase(Sort sort) {
    return field -> {
      if (nonNull(sort.getField())) {
        return field.getValue().equalsIgnoreCase(sort.getField().getValue());
      }
      return false;
    };
  }
}
