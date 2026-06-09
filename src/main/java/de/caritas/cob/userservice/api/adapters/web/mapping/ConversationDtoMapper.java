package de.caritas.cob.userservice.api.adapters.web.mapping;

import de.caritas.cob.userservice.api.adapters.web.dto.AnonymousEnquiry;
import de.caritas.cob.userservice.api.adapters.web.dto.AnonymousEnquiry.StatusEnum;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConversationDtoMapper {

  public AnonymousEnquiry anonymousEnquiryOf(
      Map<String, Object> sessionMap, int numAvailableConsultants, long peopleAhead) {
    var statusString = (String) sessionMap.get("status");

    var anonymousEnquiry = new AnonymousEnquiry();
    anonymousEnquiry.setNumAvailableConsultants(numAvailableConsultants);
    anonymousEnquiry.setPeopleAhead((int) Math.min(Integer.MAX_VALUE, peopleAhead));
    anonymousEnquiry.setStatus(StatusEnum.fromValue(statusString));

    return anonymousEnquiry;
  }

  public String adviceSeekerIdOf(Map<String, Object> sessionMap) {
    return (String) sessionMap.get("adviceSeekerId");
  }

  public Integer consultingTypeIdOf(Map<String, Object> sessionMap) {
    return (Integer) sessionMap.get("consultingTypeId");
  }

  public Long agencyIdOf(Map<String, Object> sessionMap) {
    var value = sessionMap.get("agencyId");
    if (value instanceof Long) return (Long) value;
    if (value instanceof Number) return ((Number) value).longValue();
    return null;
  }

  public LocalDateTime createDateOf(Map<String, Object> sessionMap) {
    return (LocalDateTime) sessionMap.get("createDate");
  }

  public Long mainTopicIdOf(Map<String, Object> sessionMap) {
    var value = sessionMap.get("mainTopicId");
    if (value instanceof Long) return (Long) value;
    if (value instanceof Number) return ((Number) value).longValue();
    return null;
  }
}
