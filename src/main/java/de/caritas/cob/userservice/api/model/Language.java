package de.caritas.cob.userservice.api.model;

import com.neovisionaries.i18n.LanguageCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Table
@Getter
@Setter
@ToString
@Entity
@IdClass(LanguageId.class)
@NoArgsConstructor
@AllArgsConstructor
public class Language {

  @Id
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(
      columnDefinition = "varchar(36)",
      name = "consultant_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "language_code_consultant_constraint"))
  @ToString.Exclude
  private Consultant consultant;

  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 2)
  private LanguageCode languageCode;
}
