/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import com.google.inject.Guice;
import io.wisetime.connector.ConnectorController;
import io.wisetime.connector.config.RuntimeConfig;
import io.wisetime.connector.config.RuntimeConfigKey;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Connector application entry point.
 *
 * @author pascal
 */
public class ConnectorLauncher {

  public static void main(final String... args) throws Exception {
    ConnectorController connectorController = buildConnectorController();
    connectorController.start();
  }

  public static ConnectorController buildConnectorController() {
    return ConnectorController.newBuilder()
        .withWiseTimeConnector(Guice.createInjector(binder -> {
          // Build api client here to be able to inject it into AllisaApiService for better testability
          OkHttpClient.Builder httpClient = new OkHttpClient.Builder();

          // API key interceptor
          httpClient.addInterceptor(chain -> {
            Request newRequest  = chain.request().newBuilder()
                .addHeader("Authorization", getAllisaApiKey())
                .build();
            return chain.proceed(newRequest);
          });

          Retrofit retrofit = new Retrofit.Builder()
              .client(httpClient.build())
              .baseUrl(getBaseUrl())
              .addConverterFactory(GsonConverterFactory.create())
              .build();

          binder.bind(AllisaApiService.AllisaApi.class)
              .toInstance(retrofit.create(AllisaApiService.AllisaApi.class));
        }).getInstance(AllisaConnector.class))
        .build();
  }

  /**
   * Configuration keys for the WiseTime Allisa Connector.
   *
   * @author pascal
   */
  public enum AllisaConnectorConfigKey implements RuntimeConfigKey {

    //required
    ALLISA_API_KEY("ALLISA_API_KEY"),
    ALLISA_BASE_URL("ALLISA_BASE_URL"),
    ALLISA_CASE_TYPE("ALLISA_CASE_TYPE"),
    ALLISA_POST_TYPE("ALLISA_POST_TYPE"),

    //optional
    ALLISA_POST_FIELD_MAPPING("ALLISA_POST_FIELD_MAPPING"),
    TAG_UPSERT_PATH("TAG_UPSERT_PATH"),
    TAG_UPSERT_BATCH_SIZE("TAG_UPSERT_BATCH_SIZE"),
    TIMEZONE("TIMEZONE"),
    ADD_SUMMARY_TO_NARRATIVE("ADD_SUMMARY_TO_NARRATIVE");

    private final String configKey;

    AllisaConnectorConfigKey(final String configKey) {
      this.configKey = configKey;
    }

    @Override
    public String getConfigKey() {
      return configKey;
    }
  }

  private static String getBaseUrl() {
    return RuntimeConfig
        .getString(AllisaConnectorConfigKey.ALLISA_BASE_URL)
        .orElseThrow(() -> new IllegalArgumentException("ALLISA_BASE_URL needs to be set"));
  }

  private static String getAllisaApiKey() {
    return RuntimeConfig
        .getString(AllisaConnectorConfigKey.ALLISA_API_KEY)
        .map(key -> "apikey " + key)
        .orElseThrow(() -> new IllegalArgumentException("ALLISA_API_KEY needs to be set"));
  }
}
