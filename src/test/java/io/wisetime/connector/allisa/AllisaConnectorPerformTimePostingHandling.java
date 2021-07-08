/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.github.javafaker.Faker;
import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.allisa.model.TimePostData;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult;
import io.wisetime.connector.api_client.PostResult.PostResultStatus;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.Tag;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import spark.Request;

/**
 * @author pascal
 */
class AllisaConnectorPerformTimePostingHandling {

  private static final Faker FAKER = new Faker();
  private static final FakeEntities FAKE_ENTITIES = new FakeEntities();
  private static final String TAG_UPSERT_PATH = "/Allisa/";

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static AllisaApiService allisaApiServiceMock = mock(AllisaApiService.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static AllisaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(AllisaConnectorConfigKey.TAG_UPSERT_PATH, TAG_UPSERT_PATH);
    RuntimeConfig.setProperty(AllisaConnectorConfigKey.TIMEZONE, "Asia/Manila");

    connector = Guice.createInjector(
        binder -> binder.bind(AllisaApiService.class).toProvider(() -> allisaApiServiceMock)
    )
        .getInstance(AllisaConnector.class);

    // Ensure AllisaConnector#init will not fail
    doReturn(true).when(allisaApiServiceMock).canConnect();

    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock, 5));
  }

  @BeforeEach
  void setUpTest() {
    reset(allisaApiServiceMock);
    reset(apiClientMock);
    reset(connectorStoreMock);
  }

  @Test
  void postTime_noTags() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTags(Collections.emptyList());

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .usingRecursiveComparison()
        .as("no tags in time group")
        .isEqualTo(PostResult.SUCCESS().withMessage("Time group has no tags. There is nothing to post to Allisa."));

    verifyZeroInteractions(allisaApiServiceMock);
  }

  @Test
  void postTime_tag_not_exists_in_allisa() {
    Tag tag = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag_not_exists");
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag));
    when(allisaApiServiceMock.getAllisaCaseByTagName(tag.getName()))
        .thenReturn(Optional.empty());

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("tag not found in db")
        .isEqualTo(PostResult.PERMANENT_FAILURE().getStatus());
  }

  @Test
  void postTime_tag_non_allisa_tag() {
    Tag tag = FAKE_ENTITIES.randomTag("/NonAllisa/", "tag");
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("tag is not Allisa tag")
        .isEqualTo(PostResult.SUCCESS().getStatus());
  }

  @Test
  void postTime_noTimeRows() {
    TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup();
    timeGroup.setTimeRows(Collections.emptyList());
    timeGroup.getTags()
        .forEach(tag -> tag.setPath(RuntimeConfig.getString(AllisaConnectorConfigKey.TAG_UPSERT_PATH).get()));

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .usingRecursiveComparison()
        .as("no time rows in time group")
        .isEqualTo(PostResult.PERMANENT_FAILURE().withMessage("Cannot post time group with no time rows"));

    verifyZeroInteractions(allisaApiServiceMock);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime() {
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final String activityType = "activity1";
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110121)
        .durationSecs(600)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110122)
        .durationSecs(300)
        .firstObservedInHour(0);
    timeRow2.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    // time groups will now only have one tag
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(900);

    final AllisaCase allisaCase1 = randomDataGenerator.randomAllisaCase(tag1.getName());

    when(allisaApiServiceMock.getAllisaCaseByTagName(anyString()))
        .thenReturn(Optional.of(allisaCase1));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify time post creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(1)).postTime(timeRegCaptor.capture());
    List<TimePostData> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).getTagId())
        .as("time registration should have correct case id")
        .isEqualTo(allisaCase1.getCaseId());
    assertThat(timeRegistrations.get(0).getStartDateTime())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02 05:00:00");
    assertThat(timeRegistrations.get(0).getTotalTimeSecs())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(900);
    assertThat(timeRegistrations.get(0).getChargeableTimeSecs())
        .as("chargeable hours should corresponds to the group duration, using user experience")
        .isEqualTo(450);
    assertThat(timeRegistrations.get(0).getActivityCode())
        .isEqualTo(activityType);
  }

  @Test
  @SuppressWarnings({"MethodLength", "ExecutableStatementCount"})
  void postTime_noSplittingForIrrelevantTags() {
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");
    final Tag tag2 = FAKE_ENTITIES.randomTag("/NotAllisa/", "tag2");

    final String activityType = "activity1";
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110121)
        .durationSecs(600)
        .firstObservedInHour(0);
    timeRow1.setDescription(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110122)
        .durationSecs(300)
        .firstObservedInHour(0);
    timeRow2.setDescription(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1, tag2))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(900);

    final AllisaCase allisaCase1 = randomDataGenerator.randomAllisaCase(tag1.getName());
    final AllisaCase allisaCase2 = randomDataGenerator.randomAllisaCase(tag2.getName());

    when(allisaApiServiceMock.getAllisaCaseByTagName(anyString()))
        .thenReturn(Optional.of(allisaCase1))
        .thenReturn(Optional.of(allisaCase2));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify time post creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(1)).postTime(timeRegCaptor.capture());
    List<TimePostData> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).getTagId())
        .as("time registration should have correct case id")
        .isEqualTo(allisaCase1.getCaseId());
    assertThat(timeRegistrations.get(0).getStartDateTime())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02 05:00:00");
    assertThat(timeRegistrations.get(0).getTotalTimeSecs())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience and "
            + "split equally between all tags ")
        .isEqualTo(900);
    assertThat(timeRegistrations.get(0).getChargeableTimeSecs())
        .as("chargeable hours should corresponds to the group duration, using user experience and "
            + "split equally between all tags ")
        .isEqualTo(450);
  }

  @Test
  void postTime_editedTotalDuration() {
    final Tag tag1 = FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1");

    final String activityType = "activity1";
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110121)
        .durationSecs(600)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow(activityType)
        .activityHour(2018110122)
        .durationSecs(300)
        .firstObservedInHour(0)
        .description(FAKER.lorem().characters());

    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    // time groups will now only have one tag
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(tag1))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .totalDurationSecs(1500);

    final AllisaCase allisaCase1 = randomDataGenerator.randomAllisaCase(tag1.getName());

    when(allisaApiServiceMock.getAllisaCaseByTagName(anyString()))
        .thenReturn(Optional.of(allisaCase1));

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify time post creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(1)).postTime(timeRegCaptor.capture());
    List<TimePostData> timeRegistrations = timeRegCaptor.getAllValues();

    assertThat(timeRegistrations.get(0).getTagId())
        .as("time registration should have correct case id")
        .isEqualTo(allisaCase1.getCaseId());
    assertThat(timeRegistrations.get(0).getStartDateTime())
        .as("activity date should equal to the activity date of the row in user time zone")
        .isEqualTo("2018-11-02 05:00:00");
    assertThat(timeRegistrations.get(0).getTotalTimeSecs())
        .as("actual hours should corresponds to the total rows duration, disregarding user experience")
        .isEqualTo(900);
    assertThat(timeRegistrations.get(0).getChargeableTimeSecs())
        .as("chargeable hours should corresponds to the group duration, disregarding user experience")
        .isEqualTo(1500);
  }

  @Test
  void convertToZone() {
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityHour(2018123123)
        .firstObservedInHour(12)
        .submittedDate(20190110082359997L);
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .timeRows(ImmutableList.of(timeRow));

    final TimeGroup convertedTimeGroup = connector.convertToZone(timeGroup, ZoneId.of("Asia/Kolkata")); // offset is +5.5

    // check TimeGroup
    assertThat(convertedTimeGroup)
        .as("original time group should not be mutated")
        .isNotSameAs(timeGroup);
    assertThat(convertedTimeGroup.getCallerKey()).isEqualTo(timeGroup.getCallerKey());
    assertThat(convertedTimeGroup.getGroupId()).isEqualTo(timeGroup.getGroupId());
    assertThat(convertedTimeGroup.getGroupName()).isEqualTo(timeGroup.getGroupName());
    assertThat(convertedTimeGroup.getDescription()).isEqualTo(timeGroup.getDescription());
    assertThat(convertedTimeGroup.getTotalDurationSecs()).isEqualTo(timeGroup.getTotalDurationSecs());
    assertThat(convertedTimeGroup.getNarrativeType()).isEqualTo(timeGroup.getNarrativeType());
    assertThat(convertedTimeGroup.getTags()).isEqualTo(timeGroup.getTags());
    assertThat(convertedTimeGroup.getUser()).isEqualTo(timeGroup.getUser());
    assertThat(convertedTimeGroup.getDurationSplitStrategy()).isEqualTo(timeGroup.getDurationSplitStrategy());
    assertThat(convertedTimeGroup.getTags()).isEqualTo(timeGroup.getTags());

    // check TimeRow
    assertThat(convertedTimeGroup.getTimeRows().get(0))
        .as("original time group should not be mutated")
        .isNotSameAs(timeRow);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivityHour())
        .as("should be converted to the specified timezone")
        .isEqualTo(2019010104);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getFirstObservedInHour())
        .as("should be converted to the specified timezone")
        .isEqualTo(42);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getSubmittedDate())
        .as("should be converted to the specified timezone")
        .isEqualTo(20190110135359997L);
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivity()).isEqualTo(timeRow.getActivity());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getDescription()).isEqualTo(timeRow.getDescription());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getDurationSecs()).isEqualTo(timeRow.getDurationSecs());
    assertThat(convertedTimeGroup.getTimeRows().get(0).getActivityTypeCode()).isEqualTo(timeRow.getActivityTypeCode());
  }

  @Test
  void postTime_should_use_external_id_as_username() {
    final String externalId = "42";
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag")))
        .user(FAKE_ENTITIES.randomUser().externalId(externalId));
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(fakeRequest(), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    final ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(timeGroup.getTags().size())).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getValue().getUserId())
        .as("should use the external id as login id")
        .isEqualTo(externalId);
  }

  @Test
  void postTime_noExternalId() {
    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag")))
        .user(FAKE_ENTITIES.randomUser().externalId(""));
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(fakeRequest(), timeGroup))
        .usingRecursiveComparison()
        .as("should fail when no external id")
        .isEqualTo(PostResult.PERMANENT_FAILURE().withMessage(
            "External User Id is required in order to post to Allisa"));
  }

  private void setPrerequisitesForSuccessfulPostTime(TimeGroup timeGroup) {
    timeGroup.getTags().forEach(tag -> when(allisaApiServiceMock.getAllisaCaseByTagName(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomAllisaCase(tag.getName()))));
  }

  private Request fakeRequest() {
    return mock(Request.class);
  }

}
