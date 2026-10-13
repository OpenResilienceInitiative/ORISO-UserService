package de.caritas.cob.userservice.api.adapters.web.mapping;

import de.caritas.cob.userservice.api.adapters.web.dto.ConsultantAgencyTopicsDTO;
import de.caritas.cob.userservice.api.port.out.ConsultantTopicRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Groups stored consultant topic rows into the per-centre admin view (#1264). */
public final class ConsultantTopicsByAgencyMapper {

  private ConsultantTopicsByAgencyMapper() {}

  /**
   * @return consultantId -> entries sorted by agencyId (legacy entry without agencyId first)
   */
  public static Map<String, List<ConsultantAgencyTopicsDTO>> topicsByAgencyOf(
      ConsultantTopicRepository repository, Collection<String> consultantIds) {
    if (consultantIds.isEmpty()) {
      return Map.of();
    }
    Map<String, Map<Long, TreeSet<Long>>> grouped = new HashMap<>();
    for (Object[] row : repository.findAgencyTopicRowsByConsultantIdIn(consultantIds)) {
      grouped
          .computeIfAbsent((String) row[0], id -> new HashMap<>())
          .computeIfAbsent((Long) row[1], id -> new TreeSet<>())
          .add((Long) row[2]);
    }
    Map<String, List<ConsultantAgencyTopicsDTO>> result = new HashMap<>();
    grouped.forEach(
        (consultantId, byAgency) -> {
          var entries = new ArrayList<ConsultantAgencyTopicsDTO>();
          byAgency.forEach(
              (agencyId, topicIds) ->
                  entries.add(
                      new ConsultantAgencyTopicsDTO()
                          .agencyId(agencyId)
                          .topicIds(new ArrayList<>(topicIds))));
          entries.sort(
              Comparator.comparing(
                  ConsultantAgencyTopicsDTO::getAgencyId,
                  Comparator.nullsFirst(Comparator.naturalOrder())));
          result.put(consultantId, entries);
        });
    return result;
  }
}
