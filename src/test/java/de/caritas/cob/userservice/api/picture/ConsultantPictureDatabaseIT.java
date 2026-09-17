package de.caritas.cob.userservice.api.picture;

import static de.caritas.cob.userservice.api.config.auth.Authority.AuthorityValue.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import de.caritas.cob.userservice.api.AccountManager;
import de.caritas.cob.userservice.api.admin.facade.AdminUserFacade;
import de.caritas.cob.userservice.api.admin.service.agency.ConsultantAgencyAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.ConsultantAdminService;
import de.caritas.cob.userservice.api.admin.service.consultant.create.CreateConsultantSaga;
import de.caritas.cob.userservice.api.admin.service.consultant.delete.ConsultantPreDeletionService;
import de.caritas.cob.userservice.api.admin.service.consultant.update.ConsultantUpdateService;
import de.caritas.cob.userservice.api.exception.httpresponses.NotFoundException;
import de.caritas.cob.userservice.api.helper.AuthenticatedUser;
import de.caritas.cob.userservice.api.port.out.ConsultantRepository;
import de.caritas.cob.userservice.api.service.appointment.AppointmentService;
import de.caritas.cob.userservice.api.service.consultingtype.TopicService;
import de.caritas.cob.userservice.api.workflow.delete.action.consultant.DeleteDatabaseConsultantAction;
import de.caritas.cob.userservice.api.workflow.delete.model.ConsultantDeletionWorkflowDTO;
import de.caritas.cob.userservice.api.workflow.delete.service.DeletionLifecycleService;
import de.caritas.cob.userservice.api.workflow.delete.service.IdentityTombstoneService;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "LIQUIBASE_IT_DB_URL", matches = ".+")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
  ConsultantPictureStore.class,
  ConsultantPictureAccess.class,
  ConsultantPictureService.class,
  PictureIntake.class,
  ConsultantAdminService.class,
  DeletionLifecycleService.class,
  DeleteDatabaseConsultantAction.class
})
class ConsultantPictureDatabaseIT {
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", () -> System.getenv("LIQUIBASE_IT_DB_URL"));
    r.add("spring.datasource.username", () -> "root");
    r.add("spring.datasource.password", () -> "root");
    r.add("spring.liquibase.enabled", () -> "true");
    r.add("spring.liquibase.change-log", () -> "classpath:db/changelog/userservice-master.xml");
    r.add("spring.liquibase.contexts", () -> "dev,seed");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    r.add("multitenancy.enabled", () -> "false");
  }

  @Autowired ConsultantPictureService service;
  @Autowired ConsultantPictureStore store;
  @Autowired ConsultantAdminService deletion;
  @Autowired DeleteDatabaseConsultantAction hardDeletion;
  @MockitoBean IdentityTombstoneService tombstones;
  @Autowired ConsultantRepository consultants;
  @Autowired EntityManager em;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @MockitoBean AuthenticatedUser caller;
  @MockitoBean ClamAvPictureScanner scanner;
  @MockitoBean AdminUserFacade admins;
  @MockitoBean ConsultantAgencyAdminService agencies;
  @MockitoBean CreateConsultantSaga create;
  @MockitoBean ConsultantUpdateService update;
  @MockitoBean ConsultantPreDeletionService preDelete;
  @MockitoBean AccountManager accountManager;
  @MockitoBean AppointmentService appointments;
  @MockitoBean TopicService topics;
  String id;
  byte[] png;

  @BeforeEach
  void setup() throws Exception {
    id = UUID.randomUUID().toString();
    png = PictureIntakeTest.png(2, 2);
    jdbc.update(
        "INSERT INTO consultant (consultant_id, username, first_name, last_name, email, tenant_id) VALUES (?, ?, 'Synthetic', 'Picture', ?, 1)",
        id,
        id,
        id + "@example.invalid");
    when(caller.getTenantId()).thenReturn(1L);
    when(caller.getUserId()).thenReturn("synthetic-admin");
    when(caller.getGrantedAuthorities()).thenReturn(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM consultant WHERE consultant_id = ?", id);
  }

  int pictureCount() {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM consultant_picture WHERE consultant_id = ?", Integer.class, id);
  }

  byte[] savedBytes() {
    return jdbc.queryForObject(
        "SELECT image_bytes FROM consultant_picture WHERE consultant_id = ?", byte[].class, id);
  }

  void upload(byte[] bytes) {
    service.put(id, new ByteArrayInputStream(bytes), "image/png");
  }

  @Test
  void uploadReadReplaceAndIdempotentRemovalPersistAcrossTransactions() throws Exception {
    upload(png);
    assertThat(pictureCount()).isEqualTo(1);
    assertThat(savedBytes()).isEqualTo(png);
    assertThat(store.read(id).getBytes()).isEqualTo(png);
    byte[] replacement = PictureIntakeTest.png(3, 3);
    upload(replacement);
    assertThat(pictureCount()).isEqualTo(1);
    assertThat(savedBytes()).isEqualTo(replacement);
    store.remove(id);
    store.remove(id);
    assertThat(pictureCount()).isZero();
    assertThatThrownBy(() -> store.read(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void failedScanRetainsOldCleanBytesAndFirstFailureCreatesNothing() {
    doThrow(PictureException.unavailable()).when(scanner).scan(any());
    assertThatThrownBy(() -> upload(png)).hasMessage("PICTURE_SCAN_UNAVAILABLE");
    assertThat(pictureCount()).isZero();
    reset(scanner);
    upload(png);
    doThrow(PictureException.rejected()).when(scanner).scan(any());
    assertThatThrownBy(() -> upload(png)).hasMessage("PICTURE_REJECTED");
    assertThat(savedBytes()).isEqualTo(png);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"duplicate-header", "unknown-critical"})
  void invalidCriticalPngNeverScansOrCreatesOrReplacesStorage(String variant) throws Exception {
    byte[] invalid = PictureIntakeTest.invalidCriticalPng(variant);
    assertThatThrownBy(() -> upload(invalid)).hasMessage("PICTURE_INVALID_IMAGE");
    assertThat(pictureCount()).isZero();
    verifyNoInteractions(scanner);
    upload(png);
    clearInvocations(scanner);
    assertThatThrownBy(() -> upload(invalid)).hasMessage("PICTURE_INVALID_IMAGE");
    assertThat(savedBytes()).isEqualTo(png);
    verifyNoInteractions(scanner);
  }

  @Test
  void actualAdminSoftDeleteRemovesPictureImmediately() {
    upload(png);
    deletion.markConsultantForDeletion(id, false);
    assertThat(pictureCount()).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT delete_date IS NOT NULL FROM consultant WHERE consultant_id=?",
                Boolean.class,
                id))
        .isTrue();
    assertThatThrownBy(() -> store.read(id)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void foreignKeyCascadeAndPayloadBoundsAreEnforcedByMariaDb() {
    upload(png);
    jdbc.update("DELETE FROM consultant WHERE consultant_id=?", id);
    assertThat(pictureCount()).isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO consultant_picture (consultant_id, image_bytes, content_type, updated_at) VALUES (?, ?, 'image/png', UTC_TIMESTAMP())",
                    id,
                    png))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void finalDatabaseDeletionWorkflowLeavesNoPictureRow() {
    upload(png);
    var target =
        new ConsultantDeletionWorkflowDTO(
            consultants.findById(id).orElseThrow(), new ArrayList<>());
    new TransactionTemplate(transactions)
        .executeWithoutResult(status -> hardDeletion.execute(target));
    assertThat(target.getDeletionWorkflowErrors()).isEmpty();
    assertThat(consultants.findById(id)).isEmpty();
    assertThat(pictureCount()).isZero();
  }

  @Test
  void rolledBackSoftDeletionRestoresOwnerAndPictureTogether() {
    upload(png);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              deletion.markConsultantForDeletion(id, false);
              assertThat(pictureCount()).isZero();
              status.setRollbackOnly();
            });
    assertThat(consultants.findById(id).orElseThrow().getDeleteDate()).isNull();
    assertThat(savedBytes()).isEqualTo(png);
    assertThat(store.read(id).getBytes()).isEqualTo(png);
  }

  @Test
  void realBlobRoundTripsFiveMiBAndRejectsLargerRows() {
    byte[] boundary = new byte[PictureIntake.MAX_BYTES];
    Arrays.fill(boundary, (byte) 17);
    jdbc.update(
        "INSERT INTO consultant_picture (consultant_id, image_bytes, content_type, updated_at) VALUES (?, ?, 'image/png', UTC_TIMESTAMP())",
        id,
        boundary);
    assertThat(savedBytes()).isEqualTo(boundary);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE consultant_picture SET image_bytes=? WHERE consultant_id=?",
                    new byte[PictureIntake.MAX_BYTES + 1],
                    id))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void scanFinishingAfterDeletionCannotResurrectPicture() throws Exception {
    upload(png);
    var scanning = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              scanning.countDown();
              assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(scanner)
        .scan(any());
    try (var executor = Executors.newSingleThreadExecutor()) {
      var result = executor.submit(() -> upload(png));
      assertThat(scanning.await(5, TimeUnit.SECONDS)).isTrue();
      try {
        deletion.markConsultantForDeletion(id, false);
      } finally {
        finish.countDown();
      }
      assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(NotFoundException.class);
      assertThat(pictureCount()).isZero();
    }
  }

  @Test
  void staleManagedOwnerIsRefreshedAfterConcurrentSoftDeletion() throws Exception {
    upload(png);
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var stale = consultants.findByIdAndDeleteDateIsNull(id).orElseThrow();
              var independent = new TransactionTemplate(transactions);
              independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
              independent.executeWithoutResult(
                  inner -> deletion.markConsultantForDeletion(id, false));
              assertThat(stale.getDeleteDate()).isNull();
              assertThatThrownBy(() -> store.replace(id, png, "image/png"))
                  .isInstanceOf(NotFoundException.class);
              status.setRollbackOnly();
            });
    assertThat(pictureCount()).isZero();
  }

  @Test
  void deletionHoldingOwnerLockRefusesScannerThatFinishesDuringPreDeletion() throws Exception {
    upload(png);
    var deleting = new CountDownLatch(1);
    var allowDelete = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              deleting.countDown();
              assertThat(allowDelete.await(5, TimeUnit.SECONDS)).isTrue();
              return null;
            })
        .when(preDelete)
        .performPreDeletionSteps(any(), eq(false));
    try (var executor = Executors.newFixedThreadPool(2)) {
      var deleteResult = executor.submit(() -> deletion.markConsultantForDeletion(id, false));
      assertThat(deleting.await(5, TimeUnit.SECONDS)).isTrue();
      var uploadResult = executor.submit(() -> upload(png));
      try {
        assertThatThrownBy(() -> uploadResult.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        allowDelete.countDown();
      }
      deleteResult.get(5, TimeUnit.SECONDS);
      assertThatThrownBy(() -> uploadResult.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(NotFoundException.class);
      assertThat(pictureCount()).isZero();
    }
  }

  @Test
  void deletionWaitsForWriterAndRemovesTheCommittedReplacement() throws Exception {
    upload(png);
    var writing = new CountDownLatch(1);
    var commit = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var writer =
          executor.submit(
              () ->
                  new TransactionTemplate(transactions)
                      .executeWithoutResult(
                          status -> {
                            store.replace(id, png, "image/png");
                            writing.countDown();
                            try {
                              assertThat(commit.await(5, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException ex) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(ex);
                            }
                          }));
      assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
      var deleteResult = executor.submit(() -> deletion.markConsultantForDeletion(id, false));
      try {
        assertThatThrownBy(() -> deleteResult.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        commit.countDown();
      }
      writer.get(5, TimeUnit.SECONDS);
      deleteResult.get(5, TimeUnit.SECONDS);
      assertThat(pictureCount()).isZero();
    }
  }

  boolean internalOnlyColumn() {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT internal_only FROM consultant_picture WHERE consultant_id = ?",
            Boolean.class,
            id));
  }

  @Test
  void issue1049AnUploadedPictureIsInternalOnlyUntilItIsPublished() {
    upload(png);
    assertThat(internalOnlyColumn()).isTrue();
    assertThat(store.readInternalOnly(id)).isTrue();
    assertThatThrownBy(() -> asAdviceSeeker(() -> store.readPublished(id)))
        .isInstanceOf(NotFoundException.class);
    store.writeInternalOnly(id, false);
    assertThat(internalOnlyColumn()).isFalse();
    assertThat(asAdviceSeeker(() -> store.readPublished(id)).getBytes()).isEqualTo(png);
  }

  @Test
  void issue1049WithdrawingAndReplacingBothHideThePictureAgainImmediately() throws Exception {
    upload(png);
    store.writeInternalOnly(id, false);
    store.writeInternalOnly(id, true);
    assertThat(internalOnlyColumn()).isTrue();
    assertThatThrownBy(() -> asAdviceSeeker(() -> store.readPublished(id)))
        .isInstanceOf(NotFoundException.class);

    store.writeInternalOnly(id, false);
    byte[] replacement = PictureIntakeTest.png(3, 3);
    upload(replacement);
    assertThat(internalOnlyColumn()).isTrue();
    assertThat(savedBytes()).isEqualTo(replacement);
    assertThatThrownBy(() -> asAdviceSeeker(() -> store.readPublished(id)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void issue1049DeletingTheConsultantAlsoEndsPublicDelivery() {
    upload(png);
    store.writeInternalOnly(id, false);
    deletion.markConsultantForDeletion(id, false);
    assertThat(pictureCount()).isZero();
    assertThatThrownBy(() -> asAdviceSeeker(() -> store.readPublished(id)))
        .isInstanceOf(NotFoundException.class);
  }

  /** Swap the mocked caller to an advice seeker for one read, then restore the administrator. */
  <T> T asAdviceSeeker(java.util.function.Supplier<T> read) {
    when(caller.getGrantedAuthorities()).thenReturn(Set.of(USER_DEFAULT));
    try {
      return read.get();
    } finally {
      when(caller.getGrantedAuthorities()).thenReturn(Set.of(USER_ADMIN, CONSULTANT_UPDATE));
    }
  }
}
