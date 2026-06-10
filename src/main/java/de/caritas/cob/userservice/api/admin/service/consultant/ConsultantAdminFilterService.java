package de.caritas.cob.userservice.api.admin.service.consultant;

import static java.util.Objects.nonNull;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantFilter;
import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantSearchResultDTO;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort;
import de.caritas.cob.userservice.api.adapters.web.dto.Sort.OrderEnum;
import de.caritas.cob.userservice.api.admin.service.consultant.querybuilder.ConsultantFilterSpecification;
import de.caritas.cob.userservice.api.model.Consultant;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/** Service class to provide filtered search for all consultant entities. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsultantAdminFilterService {

  private final @NonNull ConsultantRepository consultantRepository;

  /**
   * Searches for consultants by given {@link ConsultantFilter}, limits the result by perPage and
   * generates a {@link ConsultantSearchResultDTO} containing hal links.
   *
   * @param consultantFilter the filter object containing filter values
   * @param page the current requested page (1 = first page)
   * @param perPage the amount of items in one page
   * @return the result list
   */
  public ConsultantSearchResultDTO findFilteredConsultants(
      final Integer page,
      final Integer perPage,
      final ConsultantFilter consultantFilter,
      final Sort sort) {
    var pageRequest = PageRequest.of(Math.max(page - 1, 0), Math.max(perPage, 1), buildSort(sort));
    var resultPage =
        consultantRepository.findAll(buildSpecification(consultantFilter), pageRequest);

    return ConsultantSearchResultBuilder.getInstance(
            resultPage.getContent(), resultPage.getTotalElements())
        .withFilter(consultantFilter)
        .withSort(sort)
        .withPage(page)
        .withPerPage(perPage)
        .buildSearchResult();
  }

  /**
   * Builds the {@link Specification} used to filter consultants. Subclasses may override to add
   * further restrictions (e.g. tenant scoping).
   *
   * @param consultantFilter the requested filter values
   * @return the consultant {@link Specification}
   */
  protected Specification<Consultant> buildSpecification(ConsultantFilter consultantFilter) {
    return ConsultantFilterSpecification.of(consultantFilter);
  }

  private org.springframework.data.domain.Sort buildSort(Sort sort) {
    if (nonNull(sort) && nonNull(sort.getField())) {
      var direction = OrderEnum.DESC.equals(sort.getOrder()) ? Direction.DESC : Direction.ASC;
      return org.springframework.data.domain.Sort.by(direction, sort.getField().getValue());
    }
    return org.springframework.data.domain.Sort.unsorted();
  }
}
