/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey;
import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_BASE_URL;
import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.TAG_UPSERT_BATCH_SIZE;
import static io.wisetime.connector.utils.ActivityTimeCalculator.startTime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.WiseTimeConnector;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.allisa.model.TimePostData;
import io.wisetime.connector.allisa.util.ConnectorException;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.connector.template.TemplateFormatter;
import io.wisetime.connector.template.TemplateFormatterConfig;
import io.wisetime.connector.utils.DurationCalculator;
import io.wisetime.connector.utils.DurationSource;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;

/**
 * WiseTime Connector implementation for Allisa.
 *
 * @author pascal
 */
public class AllisaConnector implements WiseTimeConnector {

  private static final Logger log = LoggerFactory.getLogger(AllisaConnector.class);
  private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String ALLISA_LAST_SYNC_KEY = "allisa_last_sync_id";
  private static final String ALLISA_LAST_REFRESHED_KEY = "allisa_last_refreshed_id";
  private static final String ALLISA_LAST_SYNC_PAGE = "allisa_last_sync_page";
  private static final String ALLISA_LAST_REFRESHED_PAGE = "allisa_last_refreshed_page";

  private ApiClient apiClient;
  private ConnectorStore connectorStore;
  private TemplateFormatter timeRegistrationTemplate;

  @Inject
  private AllisaApiService allisaApiService;

  @Override
  public void init(final ConnectorModule connectorModule) {
    Preconditions.checkState(allisaApiService.canConnect(),
        "Connector couldn't connect to Allisa instance");

    // default to no summary
    if (RuntimeConfig.getBoolean(AllisaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE).orElse(false)) {
      timeRegistrationTemplate = createTemplateFormatter(
          "classpath:narrative-template/allisa-template_include-summary.ftl");
    } else {
      // in case of no summary, just use the charge template, as it is the same as time registration without summary
      timeRegistrationTemplate = createTemplateFormatter(
          "classpath:narrative-template/allisa-template.ftl");
    }

    apiClient = connectorModule.getApiClient();
    connectorStore = connectorModule.getConnectorStore();
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule to check if Connector is healthy.
   */
  @Override
  public boolean isConnectorHealthy() {
    return allisaApiService.canConnect();
  }

  /**
   * Called by the WiseTime Connector library on a regular schedule.
   *
   * Finds all Allisa cases that haven't been synced and creates matching tags for them in WiseTime.
   * Blocks until all cases have been synced.
   */
  @Override
  public void performTagUpdate() {
    syncNewCases();
  }

  /**
   * Sends a batch of already synced cases to WiseTime to maintain freshness of existing tags.
   * Mitigates effect of renamed or missed tags.
   */
  @Override
  public void performTagUpdateSlowLoop() {
    refreshCases();
  }

  @Override
  public void performActivityTypeUpdate() {
    // Activity type update is not performed in this connector
  }

  /**
   * Called by the WiseTime Connector library whenever a user posts time to the team.
   * Registers worked time and updates budget if needed.
   */
  @Override
  public PostResult postTime(final Request request, final TimeGroup userPostedTime) {
    log.info("Posted time received: {}", userPostedTime.getGroupId());

    List<Tag> relevantTags = userPostedTime.getTags().stream()
        .filter(tag -> {
          if (!createdByConnector(tag)) {
            log.warn("The Allisa connector is not configured to handle this tag: {}. No time will be posted for this tag.",
                tag.getName());
            return false;
          }
          return true;
        })
        .collect(Collectors.toList());
    userPostedTime.setTags(relevantTags);

    if (userPostedTime.getTags().isEmpty()) {
      return PostResult.SUCCESS().withMessage("Time group has no tags. There is nothing to post to Allisa.");
    }

    if (userPostedTime.getTimeRows().isEmpty()) {
      return PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows");
    }

    final String userIdAllisa = userPostedTime.getUser().getExternalId();
    if (StringUtils.isEmpty(userIdAllisa)) {
      return PostResult.PERMANENT_FAILURE().withMessage("External User Id is required in order to post to Allisa");
    }

    if (!StringUtils.isNumeric(userIdAllisa)) {
      return PostResult.PERMANENT_FAILURE().withMessage("External User Id must be numeric: " + userIdAllisa);
    }

    final long chargeableSecsPerCase;
    if (wasTotalDurationEdited(userPostedTime)) {
      // time was edited -> use the edited time as is (no exp rating)
      chargeableSecsPerCase = DurationCalculator
          .of(userPostedTime)
          .useDurationFrom(DurationSource.TIME_GROUP)
          .roundToNearestSeconds(1) // do not round
          .disregardExperienceWeighting()
          .calculate();
    } else {
      // time was not edited -> use the experience rating
      chargeableSecsPerCase = DurationCalculator
          .of(userPostedTime)
          .useDurationFrom(DurationSource.TIME_GROUP)
          .roundToNearestSeconds(1) // do not round
          .calculate();
    }

    final long actualSecsPerCase = DurationCalculator
        .of(userPostedTime)
        .useDurationFrom(DurationSource.SUM_TIME_ROWS)
        .roundToNearestSeconds(1) // do not round
        .disregardExperienceWeighting()
        .calculate();


    final TimeGroup timeGroupToFormat = convertToZone(userPostedTime, getTimeZoneId());
    final String timeRegComment =  timeRegistrationTemplate.format(timeGroupToFormat);

    final Optional<LocalDateTime> activityStartTime = startTime(timeGroupToFormat);
    if (!activityStartTime.isPresent()) {
      return PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows");
    }

    final Consumer<AllisaCase> createTimeAndChargeRecord = allisaCase ->
        executeCreateTimeAndChargeRecord(new TimePostData()
            .setTagId(allisaCase.getCaseId())
            .setNarrative(timeRegComment)
            .setUserId(userIdAllisa)
            .setStartDateTime(activityStartTime.get().format(DATE_TIME_FORMATTER))
            .setChargeableTimeSecs(chargeableSecsPerCase)
            .setTotalTimeSecs(actualSecsPerCase)
            .setActivityCode(getTimeGroupActivityCode(userPostedTime))
        );

    final Function<Tag, AllisaCase> findProcess = tag ->
        allisaApiService.getAllisaCaseByTagName(tag.getName())
            .orElseThrow(() -> new ConnectorException("Can't find Allisa case for tag " + tag.getName()));

    try {
      userPostedTime
          .getTags()
          .stream()

          .map(findProcess)

          .forEach(createTimeAndChargeRecord);
    } catch (ConnectorException e) {
      log.warn("Can't post time to Allisa: " + e.getMessage());
      return PostResult.PERMANENT_FAILURE()
          .withError(e)
          .withMessage(e.getMessage());
    } catch (RuntimeException e) {
      log.warn("Failed to save posted time in Allisa", e);
      return PostResult.TRANSIENT_FAILURE()
          .withError(e)
          .withMessage("There was an error posting time to Allisa");
    }
    return PostResult.SUCCESS();
  }

  @VisibleForTesting
  void syncNewCases() {
    // When we start a new tag sync it is possible that the current page returns an empty result
    // because we already synced all of the cases of this page and we want to check the next page if there are new
    // cases. We can't check the next page directly because there might be new values on the current page
    boolean shouldCheckNextPage = true;
    while (true) {
      final Optional<Long> storedLastSyncedCaseId = connectorStore.getLong(ALLISA_LAST_SYNC_KEY);
      final Optional<Long> storedLastCompletelySyncPage = connectorStore.getLong(ALLISA_LAST_SYNC_PAGE);
      final long currentPage = storedLastCompletelySyncPage.orElse(1L);

      final List<AllisaCase> newAllisaCases = allisaApiService.getNewAllisaCases(
          storedLastSyncedCaseId.orElse(0L),
          currentPage,
          tagUpsertBatchSize()
      );

      if (newAllisaCases.isEmpty()) {
        if (shouldCheckNextPage) {
          shouldCheckNextPage = false;
          log.info("Encountered empty tag list for the first time, checking next page.");
          // first page was empty, checking next. Only doing this for the first page we check
          connectorStore.putLong(ALLISA_LAST_SYNC_PAGE, currentPage + 1);
          continue;
        }
        log.info("No new processes found. Last case ID synced: {}",
            storedLastSyncedCaseId.map(String::valueOf).orElse("None"));
        // if we got and empty page (this also includes the second empty page, if we had no new cases)
        // revert to the last page we got results on, because it could contain more cases in the future
        connectorStore.putLong(ALLISA_LAST_SYNC_PAGE, currentPage - 1);
        return;
      }

      log.info("Detected {} new {}: {}",
          newAllisaCases.size(),
          newAllisaCases.size() > 1 ? "tags" : "tag",
          newAllisaCases.stream().map(AllisaCase::getCaseId).map(Object::toString).collect(Collectors.joining(", ")));

      upsertWiseTimeTags(newAllisaCases);

      final long lastSyncedCaseId = newAllisaCases.get(newAllisaCases.size() - 1).getCaseId();
      connectorStore.putLong(ALLISA_LAST_SYNC_KEY, lastSyncedCaseId);
      connectorStore.putLong(ALLISA_LAST_SYNC_PAGE, currentPage + 1);
      // if the first page returned a result, no need to continue checking once we got an empty page
      shouldCheckNextPage = false;
      log.info("Last synced case ID: {} on page {}", lastSyncedCaseId, currentPage);
    }
  }

  @VisibleForTesting
  void refreshCases() {
    final Optional<Long> storedLastRefreshedCaseId = connectorStore.getLong(ALLISA_LAST_REFRESHED_KEY);
    final Optional<Long> storedLastRefreshedPage = connectorStore.getLong(ALLISA_LAST_REFRESHED_PAGE);

    final List<AllisaCase> newAllisaCases = allisaApiService.getNewAllisaCases(
        storedLastRefreshedCaseId.orElse(0L),
        storedLastRefreshedPage.orElse(0L) + 1,
        tagUpsertBatchSize()
    );

    if (newAllisaCases.isEmpty()) {
      // start over the next time
      connectorStore.putLong(ALLISA_LAST_REFRESHED_KEY, 0);
      connectorStore.putLong(ALLISA_LAST_REFRESHED_PAGE, 0);
      return;
    }

    log.info("Refreshing {} {}: {}",
        newAllisaCases.size(),
        newAllisaCases.size() > 1 ? "tags" : "tag",
        newAllisaCases.stream().map(AllisaCase::getCaseId).map(Object::toString).collect(Collectors.joining(", ")));

    upsertWiseTimeTags(newAllisaCases);

    final long lastSyncedCaseId = newAllisaCases.get(newAllisaCases.size() - 1).getCaseId();
    connectorStore.putLong(ALLISA_LAST_REFRESHED_KEY, lastSyncedCaseId);
    connectorStore.putLong(ALLISA_LAST_REFRESHED_PAGE, storedLastRefreshedPage.orElse(0L) + 1);
    log.info("Last refreshed case ID: {} on page {}", lastSyncedCaseId, storedLastRefreshedPage.orElse(0L) + 1);
  }

  private void upsertWiseTimeTags(final List<AllisaCase> cases) {
    try {
      final List<UpsertTagRequest> upsertRequests = cases
          .stream()
          .map(item -> item.toUpsertTagRequest(tagUpsertPath(), getBaseUrl() + "projekt/show/ID/"))
          .collect(Collectors.toList());

      apiClient.tagUpsertBatch(upsertRequests);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getConnectorType() {
    return "wisetime-allisa-connector";
  }

  private String tagUpsertPath() {
    return RuntimeConfig
        .getString(AllisaConnectorConfigKey.TAG_UPSERT_PATH)
        .orElse("/Allisa/");
  }

  private void executeCreateTimeAndChargeRecord(TimePostData params) {
    allisaApiService.postTime(params);

    log.info("Posted time to Allisa case {} on behalf of {}", params.getTagId(), params.getUserId());
  }

  private TemplateFormatter createTemplateFormatter(String getTemplatePath) {
    return new TemplateFormatter(TemplateFormatterConfig.builder()
        .withTemplatePath(getTemplatePath)
        .build());
  }

  private ZoneId getTimeZoneId() {
    return ZoneId.of(RuntimeConfig.getString(AllisaConnectorConfigKey.TIMEZONE).orElse("UTC"));
  }

  private String getTimeGroupActivityCode(TimeGroup timeGroup) {
    final List<String> activityCodes = timeGroup.getTimeRows().stream()
        .map(TimeRow::getActivityTypeCode)
        .distinct()
        .collect(Collectors.toList());
    if (activityCodes.isEmpty()) {
      return "";
    }
    if (activityCodes.size() > 1) {
      log.error("All time logs within time group {} should have same activity type code, but got: {}",
          timeGroup.getGroupId(), activityCodes);
      throw new ConnectorException("Expected only one activity type, but got " + activityCodes.size());
    }
    return activityCodes.get(0);
  }

  @VisibleForTesting
  TimeGroup convertToZone(TimeGroup timeGroupUtc, ZoneId zoneId) {
    try {
      final String timeGroupUtcJson = OBJECT_MAPPER.writeValueAsString(timeGroupUtc);
      final TimeGroup timeGroupCopy = OBJECT_MAPPER.readValue(timeGroupUtcJson, TimeGroup.class);
      timeGroupCopy.getTimeRows()
          .forEach(tr -> convertToZone(tr, zoneId));
      return timeGroupCopy;
    } catch (IOException ex) {
      throw new RuntimeException("Failed to convert TimeGroup to zone " + zoneId, ex);
    }
  }

  private void convertToZone(TimeRow timeRowUtc, ZoneId zoneId) {
    final Pair<Integer, Integer> activityTimePair = convertToZone(
        timeRowUtc.getActivityHour(), timeRowUtc.getFirstObservedInHour(), zoneId
    );

    timeRowUtc
        .activityHour(activityTimePair.getLeft())
        .firstObservedInHour(activityTimePair.getRight())
        .setSubmittedDate(convertToZone(timeRowUtc.getSubmittedDate(), zoneId));
  }

  /**
   * Returns a Pair of "activity hour" (left value) and "first observed in hour" (right value) converted
   * in the specified zone ID.
   */
  private Pair<Integer, Integer> convertToZone(int activityHourUtc, int firstObservedInHourUtc, ZoneId toZoneId) {
    final DateTimeFormatter activityTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    final String activityTimeUTC = activityHourUtc + StringUtils.leftPad(String.valueOf(firstObservedInHourUtc), 2, "0");
    final String activityTimeConverted = ZonedDateTime
        .of(LocalDateTime.parse(activityTimeUTC, activityTimeFormatter), ZoneOffset.UTC)
        .withZoneSameInstant(toZoneId)
        .format(activityTimeFormatter);

    return Pair.of(
        Integer.parseInt(activityTimeConverted.substring(0, 10)), // activityHour in 'yyyyMMddHH' format
        Integer.parseInt(activityTimeConverted.substring(10))     // firstObservedInHour in 'mm' format
    );
  }

  private Long convertToZone(long submittedDateUtc, ZoneId toZoneId) {
    DateTimeFormatter submittedDateFormatter = new DateTimeFormatterBuilder()
        .appendPattern("yyyyMMddHHmmss")
        .appendValue(ChronoField.MILLI_OF_SECOND, 3)
        .toFormatter();
    final String submittedDateConverted = ZonedDateTime
        .of(LocalDateTime.parse(String.valueOf(submittedDateUtc), submittedDateFormatter), ZoneOffset.UTC)
        .withZoneSameInstant(toZoneId)
        .format(submittedDateFormatter);

    return Long.parseLong(submittedDateConverted);
  }

  private boolean createdByConnector(Tag tag) {
    return tag.getPath().equals(tagUpsertPath())
        || tag.getPath().equals(StringUtils.strip(tagUpsertPath(), "/"));
  }

  private boolean wasTotalDurationEdited(TimeGroup userPostedTimeGroup) {
    // check if user edited the total time by comparing the total time to the sum of the time on each row
    return !userPostedTimeGroup.getTotalDurationSecs()
        .equals(userPostedTimeGroup.getTimeRows().stream().mapToInt(TimeRow::getDurationSecs).sum());
  }

  private String getBaseUrl() {
    return RuntimeConfig
        .getString(ALLISA_BASE_URL)
        .orElseThrow(() -> new IllegalArgumentException("ALLISA_BASE_URL needs to be set"));
  }

  private int tagUpsertBatchSize() {
    return RuntimeConfig
        .getInt(TAG_UPSERT_BATCH_SIZE)
        // A large batch mitigates query round trip latency
        .orElse(500);
  }
}
