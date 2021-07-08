/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All rights reserved.
 */

package io.wisetime.connector.allisa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.javafaker.Faker;
import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.allisa.model.TimePostData;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.api_client.PostResult.PostResultStatus;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.TimeGroup;
import io.wisetime.generated.connect.TimeRow;
import io.wisetime.generated.connect.User;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import spark.Request;

/**
 * @author pascal
 */
class AllisaConnectorPostTimeNarrativeTest {

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
    RuntimeConfig.setProperty(ConnectorLauncher.AllisaConnectorConfigKey.TAG_UPSERT_PATH, TAG_UPSERT_PATH);
    RuntimeConfig.setProperty(ConnectorLauncher.AllisaConnectorConfigKey.TIMEZONE, "Asia/Manila");

    // create connector to test for narrative showing row duration
    connector = Guice.createInjector(
        binder -> binder.bind(AllisaApiService.class).toProvider(() -> allisaApiServiceMock)
    )
        .getInstance(AllisaConnector.class);
  }

  @BeforeEach
  void setUpTest() {
    reset(allisaApiServiceMock);
    reset(apiClientMock);
    reset(connectorStoreMock);

    // Ensure AllisaConnector#init will not fail
    doReturn(true).when(allisaApiServiceMock).canConnect();
    RuntimeConfig.setProperty(ConnectorLauncher.AllisaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE, "false");
    connector.init(new ConnectorModule(apiClientMock, connectorStoreMock, 5));
  }

  private void initConnectorWithSummaryTemplate() {
    doReturn(true).when(allisaApiServiceMock).canConnect();

    RuntimeConfig.setProperty(ConnectorLauncher.AllisaConnectorConfigKey.ADD_SUMMARY_TO_NARRATIVE, "true");
    connector.init(new ConnectorModule(apiClientMock, mock(ConnectorStore.class), 5));
  }

  @Test
  void divide_between_tags() {
    initConnectorWithSummaryTemplate();
    final TimeRow earliestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031808).firstObservedInHour(5).durationSecs(3006);
    final TimeRow latestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031810).firstObservedInHour(8).durationSecs(1000);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag("/Allisa/", "tag1"),
            FAKE_ENTITIES.randomTag("/Allisa/", "tag2")))
        .timeRows(ImmutableList.of(earliestTimeRow, latestTimeRow))
        .user(user)
        .totalDurationSecs(4006)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("should use template")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().getNarrative())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n"
                + "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription() + "\n"
                + "\r\n18:00 - 18:59\n"
                + "- 16m 40s - " + latestTimeRow.getActivity() + " - " + latestTimeRow.getDescription())
        .contains("\r\nTotal Worked Time: 1h 6m 46s\n"
            + "Total Chargeable Time: 16m 42s")
        .contains("The chargeable time has been weighed based on an experience factor of 50%.")
        .endsWith("\r\nThe above times have been split across 2 cases and are thus greater than "
            + "the chargeable time in this case");
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("narrative for all tags should be the same for time registration.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void divide_between_tags_edited() {
    initConnectorWithSummaryTemplate();
    final TimeRow earliestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031808).firstObservedInHour(5).durationSecs(3006);
    final TimeRow latestTimeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM").activityHour(2019031810).firstObservedInHour(8).durationSecs(1000);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(earliestTimeRow, latestTimeRow))
        .user(user)
        .totalDurationSecs(3600)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.DIVIDE_BETWEEN_TAGS)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("should use template")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().getNarrative())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n16:00 - 16:59\n"
                + "- 50m 6s - " + earliestTimeRow.getActivity() + " - " + earliestTimeRow.getDescription()
                + "\n" + "\r\n18:00 - 18:59\n" + "- 16m 40s - " + latestTimeRow.getActivity()
                + " - " + latestTimeRow.getDescription())
        .contains("\r\nTotal Worked Time: 1h 6m 46s\n"
            + "Total Chargeable Time: 30m")
        .doesNotContain("The chargeable time has been weighed based on an experience factor")
        .endsWith("\r\nThe above times have been split across 2 cases and are thus greater than "
            + "the chargeable time in this case");
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("narrative for all tags should be the same.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void whole_duration_for_each_tag() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .activityTypeCode("DM")
        .activityHour(2016050113)
        .firstObservedInHour(7)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(120)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("should use template")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().getNarrative())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n"
                + "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription())
        .doesNotContain("weighed based on an experience factor")
        .endsWith("\r\nTotal Worked Time: 2m\n" + "Total Chargeable Time: 2m");
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("narrative for all tags should be the same.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void whole_duration_for_each_tag_edited() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow = FAKE_ENTITIES.randomTimeRow()
        .modifier("")
        .activityHour(2016050113)
        .firstObservedInHour(7)
        .durationSecs(120)
        .description(FAKER.superhero().descriptor());
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(50);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(timeRow))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().getNarrative())
        .as("should include time row details with start time converted in configured time zone")
        .contains(
            "\r\n21:00 - 21:59\n" + "- 2m - " + timeRow.getActivity() + " - " + timeRow.getDescription())
        .doesNotContain("weighed based on an experience factor")
        .endsWith("\r\nTotal Worked Time: 2m\n" + "Total Chargeable Time: 5m");
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("narrative for all tags should be the same.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void narrative_only_no_summary() {
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    ArgumentCaptor<TimePostData> timeRegistrationCaptor =
        ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegistrationCaptor.capture());
    final String budgetLineCommentForCase1 = timeRegistrationCaptor.getAllValues().get(0).getNarrative();
    assertThat(budgetLineCommentForCase1)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        // No summary block if NARRATIVE_ONLY and summary is disabled
        .doesNotContain("Total Worked Time:")
        .doesNotContain("Total Chargeable Time: 5m");
    assertThat(budgetLineCommentForCase1)
        .as("comment for the other case should be the same")
        .isEqualTo(timeRegistrationCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void narrative_only() {
    initConnectorWithSummaryTemplate();
    final TimeRow timeRow1 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final TimeRow timeRow2 = FAKE_ENTITIES.randomTimeRow().activityTypeCode("DM").activityHour(2017050113).durationSecs(360);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(timeRow1, timeRow2))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.ONLY);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    ArgumentCaptor<TimePostData> timeRegistrationCaptor =
        ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegistrationCaptor.capture());
    final String budgetLineCommentForCase1 = timeRegistrationCaptor.getAllValues().get(0).getNarrative();
    assertThat(budgetLineCommentForCase1)
        .as("should display narrative only")
        .startsWith(timeGroup.getDescription())
        .doesNotContain(timeRow1.getActivity() + " - " + timeRow1.getDescription())
        .doesNotContain(timeRow2.getActivity() + " - " + timeRow2.getDescription())
        .endsWith("Total Worked Time: 12m\n"
            + "Total Chargeable Time: 5m");
    assertThat(budgetLineCommentForCase1)
        .as("comment for the other case should be the same")
        .isEqualTo(timeRegistrationCaptor.getAllValues().get(1).getNarrative());
  }

  @Test
  void sanitize_app_name_and_window_title() {
    initConnectorWithSummaryTemplate();
    final TimeRow nullWindowTitle = FAKE_ENTITIES.randomTimeRow()
        .activity("@_Thinking_@").description(null).activityTypeCode("DM").activityHour(2018110109).durationSecs(120);
    final TimeRow emptyWindowTitle = FAKE_ENTITIES.randomTimeRow()
        .activity("@_Videocall_@")
        .description("@_empty_@")
        .activityTypeCode("DM")
        .activityHour(2018110109)
        .durationSecs(181);
    final User user = FAKE_ENTITIES.randomUser().experienceWeightingPercent(100);

    final TimeGroup timeGroup = FAKE_ENTITIES.randomTimeGroup()
        .tags(ImmutableList.of(FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag1"),
            FAKE_ENTITIES.randomTag(TAG_UPSERT_PATH, "tag2")))
        .timeRows(ImmutableList.of(nullWindowTitle, emptyWindowTitle))
        .user(user)
        .totalDurationSecs(300)
        .durationSplitStrategy(TimeGroup.DurationSplitStrategyEnum.WHOLE_DURATION_TO_EACH_TAG)
        .narrativeType(TimeGroup.NarrativeTypeEnum.AND_TIME_ROW_ACTIVITY_DESCRIPTIONS);
    setPrerequisitesForSuccessfulPostTime(timeGroup);

    assertThat(connector.postTime(mock(Request.class), timeGroup).getStatus())
        .as("Valid time group should be posted successfully")
        .isEqualTo(PostResultStatus.SUCCESS);

    // Verify Time Registration creation
    ArgumentCaptor<TimePostData> timeRegCaptor = ArgumentCaptor.forClass(TimePostData.class);
    verify(allisaApiServiceMock, times(2)).postTime(timeRegCaptor.capture());
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("should use template if `INVOICE_COMMENT_OVERRIDE` env variable is not set")
        .startsWith(timeGroup.getDescription());
    assertThat(timeRegCaptor.getValue().getNarrative())
        .as("should sanitize manual time and blank window title")
        .contains(
            "\n\r\n17:00 - 17:59" + "\n- 2m - Thinking - No window title available"
                + "\n- 3m 1s - Videocall - No window title available")
        .endsWith("\nTotal Worked Time: 5m 1s\n" + "Total Chargeable Time: 5m");
    assertThat(timeRegCaptor.getAllValues().get(0).getNarrative())
        .as("narrative for all tags should be the same.")
        .isEqualTo(timeRegCaptor.getAllValues().get(1).getNarrative());
  }

  private void setPrerequisitesForSuccessfulPostTime(TimeGroup timeGroup) {
    timeGroup.getTags().forEach(tag -> when(allisaApiServiceMock.getAllisaCaseByTagName(tag.getName()))
        .thenReturn(Optional.of(randomDataGenerator.randomAllisaCase(tag.getName()))));
  }
}
