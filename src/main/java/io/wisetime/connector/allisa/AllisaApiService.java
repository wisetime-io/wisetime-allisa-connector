/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_CASE_TYPE;
import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_POST_FIELD_MAPPING;
import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_POST_TYPE;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.allisa.model.ApiResponse;
import io.wisetime.connector.allisa.model.TimePostData;
import io.wisetime.connector.allisa.util.ConnectorException;
import io.wisetime.connector.config.RuntimeConfig;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import okhttp3.MultipartBody;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * Service class to communicate with allisa.
 *
 * @author pascal
 */
public class AllisaApiService {

  private static final Logger log = LoggerFactory.getLogger(AllisaApiService.class);
  private static final String DEFAULT_MAPPING = "pid:pid,userId:userId,narrative:narrative,startDateTime:startDateTime,"
      + "totalTimeSecs:totalTimeSecs,chargeableTimeSecs:chargeableTimeSecs,activityCode:activityCode";
  private static final Set<String> REQUIRED_MAPPINGS = ImmutableSet.of("pid", "userId", "narrative", "startDateTime",
      "totalTimeSecs", "chargeableTimeSecs", "activityCode");

  @Inject
  private AllisaApi allisaApi;

  private final Gson entityParser;

  private final Map<String, String> postTimeFieldMapping;

  public AllisaApiService() {
    entityParser = new GsonBuilder().create();
    ImmutableMap.Builder<String, String> mapBuilder = ImmutableMap.builder();
    for (String item: getAllisaPostFieldMapping().split(",")) {
      String[] parts = item.split(":");
      if (parts.length != 2) {
        throw new RuntimeException("Invalid post field mapping: " + item);
      }
      mapBuilder.put(parts[0].trim(), parts[1].trim());
    }
    postTimeFieldMapping = mapBuilder.build();
    Sets.SetView<String> missingFields = Sets.difference(REQUIRED_MAPPINGS, postTimeFieldMapping.keySet());
    if (!missingFields.isEmpty()) {
      throw new RuntimeException("Invalid post field mapping. Missing fields: " + missingFields.toString());
    }
  }

  public void postTime(TimePostData timePostData) {
    MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart(postTimeFieldMapping.get("pid"), Long.toString(timePostData.getTagId()))
        .addFormDataPart(postTimeFieldMapping.get("userId"), timePostData.getUserId())
        .addFormDataPart(postTimeFieldMapping.get("narrative"), timePostData.getNarrative())
        .addFormDataPart(postTimeFieldMapping.get("startDateTime"), timePostData.getStartDateTime())
        .addFormDataPart(postTimeFieldMapping.get("totalTimeSecs"), Long.toString(timePostData.getTotalTimeSecs()))
        .addFormDataPart(postTimeFieldMapping.get("chargeableTimeSecs"), Long.toString(timePostData.getChargeableTimeSecs()))
        .addFormDataPart(postTimeFieldMapping.get("activityCode"), timePostData.getActivityCode())
        .build();
    executeCall(allisaApi.postTime(getAllisaPostType(), body));
  }

  public Optional<AllisaCase> getAllisaCaseByTagName(String tagName) {
    return executeCall(allisaApi.getCase(getAllisaPostType(), tagName)).getResult().getData().stream()
        .filter(process -> tagName.equalsIgnoreCase(process.getCaseReference()))
        .findFirst();
  }

  public List<AllisaCase> getNewAllisaCases(long lastSyncedTag, long nextPage, long batchSize) {
    return executeCall(allisaApi.getCases(getAllisaCaseType(), nextPage, batchSize))
        .getResult().getData().stream()
        .filter(item -> item.getCaseId() > lastSyncedTag)
        .collect(Collectors.toList());
  }

  public boolean canConnect() {
    // check if we get an OK (200) response from allisa
    try {
      return executeCall(allisaApi.getCases(getAllisaCaseType(), 1, 1)).getCode() == HttpStatus.SC_OK;
    } catch (Exception e) {
      log.error("Error while trying to connect to allisa: {}", e.getMessage());
      return false;
    }
  }

  private String getAllisaCaseType() {
    return RuntimeConfig
        .getString(ALLISA_CASE_TYPE)
        .orElseThrow(() -> new IllegalArgumentException("ALLISA_CASE_TYPE needs to be set"));
  }

  private String getAllisaPostType() {
    return RuntimeConfig
        .getString(ALLISA_POST_TYPE)
        .orElseThrow(() -> new IllegalArgumentException("ALLISA_POST_TYPE needs to be set"));
  }

  private String getAllisaPostFieldMapping() {
    return RuntimeConfig
        .getString(ALLISA_POST_FIELD_MAPPING)
        .orElse(DEFAULT_MAPPING);
  }

  <T> T executeCall(Call<T> call) {
    try {
      retrofit2.Response<T> response = call.execute();
      if (response.body() != null) {
        return response.body();
      }
      if (!response.isSuccessful()) {
        // prevent potential null pointer exception
        String errorBody = response.errorBody() != null ? response.errorBody().string() : "";
        ApiResponse<Void> allisaError = entityParser.fromJson(errorBody, ApiResponse.class);
        String errorMessage = String.format("Request %s failed with code %s and message %s", call.request().toString(),
            response.code(), errorBody);
        log.error(errorMessage);
        // error response -> throw exception to prevent heartbeat
        throw new ConnectorException("Unable to connect to Allisa. Error reported by Allisa: "
            + allisaError.getMessage());
      }
      throw new ConnectorException("There was an unexpected error when trying to connect to Allisa.");
    } catch (IOException e) {
      // make sure posting fails when call to clio fails
      throw new RuntimeException(e);
    }
  }

  interface AllisaApi {

    @POST("api/{postType}")
    Call<ApiResponse<Void>> postTime(@Path("postType") String postType, @Body MultipartBody body);

    @GET("api/list/type/{caseType}/rowsPerPage/{rowsPerPage}/page/{page}/orderrow/caseId")
    Call<ApiResponse<AllisaCase>> getCases(@Path("caseType") String caseType,
                                           @Path("page") long page, @Path("rowsPerPage") long batchSize);

    @GET("api/list/type/{caseType}/search/{tagName}")
    Call<ApiResponse<AllisaCase>> getCase(@Path("caseType") String caseType, @Path("tagName") String tagName);
  }
}
