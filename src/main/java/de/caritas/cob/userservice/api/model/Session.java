package de.caritas.cob.userservice.api.model;

import static java.util.Objects.nonNull;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.neovisionaries.i18n.LanguageCode;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.ToString.Exclude;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.ParamDef;
import org.hibernate.type.SqlTypes;
import org.springframework.lang.Nullable;

@Entity
@Builder
@Table(name = "session")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@FilterDef(
    name = "tenantFilter",
    parameters = {@ParamDef(name = "tenantId", type = Long.class)})
@Filter(
    name = "tenantFilter",
    condition = "(tenant_id = :tenantId OR (:tenantId = 1 AND tenant_id IS NULL))")
public class Session implements TenantAware {

  public enum RegistrationType {
    REGISTERED,
    ANONYMOUS
  }

  @AllArgsConstructor
  @Getter
  @JsonFormat(shape = JsonFormat.Shape.NUMBER)
  public enum SessionStatus {
    INITIAL(0),
    NEW(1),
    IN_PROGRESS(2),
    DONE(3),
    IN_ARCHIVE(4);

    private final int value;

    public static Optional<SessionStatus> valueOf(int value) {
      return Arrays.stream(SessionStatus.values())
          .filter(legNo -> legNo.value == value)
          .findFirst();
    }

    public static boolean isStatusValueInProgress(int value) {
      return value == IN_PROGRESS.getValue();
    }
  }

  /** Represents a session of a user */
  public Session(
      User user,
      int consultingTypeId,
      @NonNull String postcode,
      Long agencyId,
      @NonNull SessionStatus status,
      boolean teamSession) {
    this.user = user;
    this.consultingTypeId = consultingTypeId;
    this.postcode = postcode;
    this.agencyId = agencyId;
    this.status = status;
    this.teamSession = teamSession;
    this.registrationType = RegistrationType.REGISTERED;
  }

  @Id
  @SequenceGenerator(name = "id_seq", allocationSize = 1, sequenceName = "sequence_session")
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "id_seq")
  @Column(name = "Id", updatable = false, nullable = false)
  private Long id;

  @ManyToOne
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne
  @JoinColumn(name = "consultant_id")
  @Fetch(FetchMode.SELECT)
  private Consultant consultant;

  @Column(
      name = "consulting_type",
      updatable = false,
      nullable = false,
      columnDefinition = "tinyint")
  @JdbcTypeCode(SqlTypes.TINYINT)
  private int consultingTypeId;

  @Column(
      name = "registration_type",
      updatable = false,
      nullable = false,
      columnDefinition = "varchar(20) not null default 'REGISTERED'")
  @Enumerated(EnumType.STRING)
  @NonNull
  private RegistrationType registrationType;

  @Enumerated(EnumType.STRING)
  @Column(name = "conversation_type", length = 32)
  private ConversationType conversationType;

  @Column(name = "postcode", nullable = false)
  @Size(max = 5)
  @NonNull
  private String postcode;

  @Column(name = "agency_id")
  private Long agencyId;

  @Enumerated(EnumType.STRING)
  @Column(columnDefinition = "varchar(2) not null default 'de'", length = 2, nullable = false)
  private LanguageCode languageCode;

  @NonNull
  @Column(columnDefinition = "tinyint")
  @JdbcTypeCode(SqlTypes.TINYINT)
  private SessionStatus status;

  @Column(name = "message_date")
  @Nullable
  private LocalDateTime enquiryMessageDate;

  @Column(name = "matrix_room_id")
  private String matrixRoomId;

  @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "session")
  @Exclude
  private List<SessionData> sessionData;

  @Column(name = "is_team_session", columnDefinition = "tinyint(4) default '0'")
  @JdbcTypeCode(SqlTypes.TINYINT)
  private boolean teamSession;

  @Column(nullable = false, columnDefinition = "bit default false")
  private Boolean isConsultantDirectlySet;

  @Column(name = "create_date", columnDefinition = "datetime")
  private LocalDateTime createDate;

  @Column(name = "update_date", columnDefinition = "datetime")
  private LocalDateTime updateDate;

  @Column(name = "tenant_id")
  private Long tenantId;

  @Column(name = "main_topic_id")
  private Long mainTopicId;

  @Column(name = "user_gender")
  private String userGender;

  @Column(name = "user_age")
  private Integer userAge;

  @Column(name = "counselling_relation")
  private String counsellingRelation;

  @Column(name = "referer")
  private String referer;

  /**
   * The ratsuchende's supervision opt-out (grill 2026-07-13, supersedes the ADR-008 item-4 binary
   * per-reason consent flag). Supervision is allowed by default (false); when the client switches
   * this on, no supervisor may be attached to the case and any active supervisors are removed.
   *
   * <p>The column is NOT NULL, so a new session must never leave this null:
   * {@code @Builder.Default} (and the field initializer for the no-args constructor) keep it {@code
   * false} at creation, since Hibernate includes the column in every INSERT and the DB default only
   * applies to omitted columns.
   */
  @Builder.Default
  @Column(name = "is_supervision_opted_out", columnDefinition = "bit default false")
  private Boolean supervisionOptedOut = false;

  /**
   * ADR-022 decision 2 — the Gate 2 consent pointer: the id of the legal-text version (owned by
   * ORISO-AgencyService, ADR-021 decision 3) this room is currently cleared for. {@code null} means
   * the gate has not been passed.
   *
   * <p><b>This is a pointer, not a log. Do not turn it into one.</b> It is <i>overwritten</i> on
   * re-consent. There must be no append-only history, no timestamp/actor columns beside it and no
   * per-user consent event table anywhere. ADR-022 rejected a consent event log explicitly, because
   * it would create a behavioural record about anonymous help-seekers that does not exist today —
   * for evidentiary value the publication history in ORISO-AgencyService already provides. The
   * field exists for <b>control flow</b> only: whether the composer may open. It is never evidence.
   *
   * <p>The referenced target is a public document version, so this adds no new category of personal
   * data. Deliberately no foreign key — the version lives in another service.
   */
  @Column(name = "consented_legal_version_id")
  private Long consentedLegalVersionId;

  @OneToMany(
      targetEntity = SessionTopic.class,
      mappedBy = "session",
      fetch = FetchType.LAZY,
      cascade = CascadeType.ALL,
      orphanRemoval = true)
  private List<SessionTopic> sessionTopics;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Session)) {
      return false;
    }
    Session session = (Session) o;
    return id.equals(session.id);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  /**
   * ADR-022 scope: Gate 2 applies to rooms that have a help-seeker — 1:1 counselling, live chat and
   * self-help groups. Internal rooms (supervision per ADR-008, Team-Besprechung per ADR-016) have
   * no help-seeker and therefore no gate. A session whose modality was never recorded predates
   * ADR-006 and is treated as a help-seeker room, which is the safe direction: the gate shows.
   */
  @JsonIgnore
  public boolean isConsentGateApplicable() {
    return conversationType != ConversationType.INTERNAL_GROUP;
  }

  @JsonIgnore
  public boolean isAdvisedBy(Consultant consultant) {
    return nonNull(this.consultant) && nonNull(consultant) && this.consultant.equals(consultant);
  }

  @JsonIgnore
  public boolean isAdvisedBy(String consultantId) {
    return nonNull(consultant) && nonNull(consultantId) && consultantId.equals(consultant.getId());
  }

  @JsonIgnore
  public boolean isAdvised(String adviceSeekerId) {
    return nonNull(user) && nonNull(adviceSeekerId) && adviceSeekerId.equals(user.getUserId());
  }
}
