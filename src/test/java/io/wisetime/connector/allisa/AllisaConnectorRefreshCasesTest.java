/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_BASE_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.inject.Guice;
import io.wisetime.connector.ConnectorModule;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.api_client.ApiClient;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.datastore.ConnectorStore;
import io.wisetime.generated.connect.UpsertTagRequest;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * @author pascal
 */
class AllisaConnectorRefreshCasesTest {

  private static final String BASE_URL = "https://allisa.cloud/demo/";
  private static final String ALLISA_LAST_REFRESHED_KEY = "allisa_last_refreshed_id";
  private static final String ALLISA_LAST_REFRESHED_PAGE = "allisa_last_refreshed_page";

  private static RandomDataGenerator randomDataGenerator = new RandomDataGenerator();
  private static AllisaApiService allisaApiServiceMock = mock(AllisaApiService.class);
  private static ApiClient apiClientMock = mock(ApiClient.class);
  private static ConnectorStore connectorStoreMock = mock(ConnectorStore.class);
  private static AllisaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.rebuild();
    RuntimeConfig.setProperty(ALLISA_BASE_URL, BASE_URL);

    connector = Guice.createInjector(binder ->
        binder.bind(AllisaApiService.class).toProvider(() -> allisaApiServiceMock)
    ).getInstance(AllisaConnector.class);

    // Ensure AllisaConnector#init will not fail
    doReturn(true).when(allisaApiServiceMock).canConnect();

    ConnectorModule connectorModule = new ConnectorModule(apiClientMock, connectorStoreMock, 5);
    connector.init(connectorModule);
  }

  @BeforeEach
  void setUpTest() {
    reset(allisaApiServiceMock);
    reset(apiClientMock);
    reset(connectorStoreMock);
  }

  @Test
  void refreshCases_no_cases() {
    when(allisaApiServiceMock.getNewAllisaCases(anyLong(), anyLong(), anyLong())).thenReturn(ImmutableList.of());

    connector.refreshCases();

    verifyZeroInteractions(apiClientMock);

    verify(connectorStoreMock, times(1)).putLong(ALLISA_LAST_REFRESHED_KEY, 0L);
    verify(connectorStoreMock, times(1)).putLong(ALLISA_LAST_REFRESHED_PAGE, 0L);
  }

  @Test
  void refreshCases_upsert_error() throws IOException {
    when(allisaApiServiceMock.getNewAllisaCases(anyLong(), anyLong(), anyLong()))
        .thenReturn(ImmutableList.of(randomDataGenerator.randomAllisaCase(), randomDataGenerator.randomAllisaCase()));

    IOException casedBy = new IOException("Expected exception");
    doThrow(casedBy)
        .when(apiClientMock).tagUpsertBatch(anyList());

    assertThatThrownBy(() -> connector.refreshCases())
        .isInstanceOf(RuntimeException.class)
        .hasCause(casedBy);
    verify(apiClientMock, times(1)).tagUpsertBatch(anyList());
    verify(connectorStoreMock, never()).putLong(anyString(), anyLong());
  }

  @Test
  @SuppressWarnings("unchecked")
  void refreshCases_new_cases_found() throws IOException {
    final AllisaCase case1 = randomDataGenerator.randomAllisaCase();
    final AllisaCase case2 = randomDataGenerator.randomAllisaCase();

    when(connectorStoreMock.getLong(anyString())).thenReturn(Optional.empty());

    when(allisaApiServiceMock.getNewAllisaCases(anyLong(), anyLong(), anyLong()))
        .thenReturn(ImmutableList.of(case1, case2))
        .thenReturn(ImmutableList.of());

    connector.refreshCases();

    ArgumentCaptor<List<UpsertTagRequest>> upsertRequests = ArgumentCaptor.forClass(List.class);
    verify(apiClientMock, times(1)).tagUpsertBatch(upsertRequests.capture());

    assertThat(upsertRequests.getValue())
        .as("We should create tags for both new cases found, with the configured tag upsert path")
        .containsExactlyInAnyOrder(
            case1.toUpsertTagRequest("/Allisa/", BASE_URL + "projekt/show/ID/"),
            case2.toUpsertTagRequest("/Allisa/", BASE_URL + "projekt/show/ID/"));

    verify(connectorStoreMock, times(1))
        .putLong(ALLISA_LAST_REFRESHED_KEY, case2.getCaseId());
    verify(connectorStoreMock, times(1))
        .putLong(ALLISA_LAST_REFRESHED_PAGE, 1L);
  }
}
