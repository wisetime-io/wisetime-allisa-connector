/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_BASE_URL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.google.inject.Guice;
import io.wisetime.connector.config.RuntimeConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AllisaConnector}.
 *
 * @author pascal
 */
class AllisaConnectorInitTest {

  private static final String BASE_URL = "https://allisa.cloud/demo/";

  private static AllisaConnector connector;

  @BeforeAll
  static void setUp() {
    RuntimeConfig.setProperty(ALLISA_BASE_URL, BASE_URL);
    connector = Guice.createInjector(binder ->
        binder.bind(AllisaApiService.AllisaApi.class).toInstance(mock(AllisaApiService.AllisaApi.class)))
        .getInstance(AllisaConnector.class);
  }

  @Test
  void getConnectorType_should_not_be_changed() {
    assertThat(connector.getConnectorType())
        .as("Connector returns the expected connector type")
        .isEqualTo("wisetime-allisa-connector");
  }
}
