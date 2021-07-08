/*
 * Copyright (c) 2020 Practice Insight Pty Ltd. All Rights Reserved.
 */

package io.wisetime.connector.allisa;

import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_CASE_TYPE;
import static io.wisetime.connector.allisa.ConnectorLauncher.AllisaConnectorConfigKey.ALLISA_POST_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.javafaker.Faker;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.inject.Guice;
import io.wisetime.connector.allisa.model.AllisaCase;
import io.wisetime.connector.allisa.model.ApiResponse;
import io.wisetime.connector.allisa.model.Result;
import io.wisetime.connector.allisa.model.TimePostData;
import io.wisetime.connector.allisa.util.ConnectorException;
import io.wisetime.connector.config.RuntimeConfig;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

/**
 * @author pascal
 */
public class AllisaApiServiceTest {

  public static final String WISETIME_CASES = "wisetime_cases";
  public static final String WISETIME = "wisetime";
  private static Faker faker = new Faker();
  private static RandomDataGenerator dataGenerator = new RandomDataGenerator();

  private static AllisaApiService allisaApiService;
  private static AllisaApiService.AllisaApi allisaApiMock;


  @BeforeAll
  static void beforeAll() {
    RuntimeConfig
        .setProperty(ALLISA_CASE_TYPE, WISETIME_CASES);
    RuntimeConfig
        .setProperty(ALLISA_POST_TYPE, WISETIME);
    allisaApiMock = mock(AllisaApiService.AllisaApi.class);
    allisaApiService = Guice.createInjector(binder ->
        binder.bind(AllisaApiService.AllisaApi.class).toInstance(allisaApiMock))
        .getInstance(AllisaApiService.class);
  }

  @BeforeEach
  void setup() {
    reset(allisaApiMock);
  }

  @Test
  void executeCall() throws IOException {
    Call<String> mockCall = mock(Call.class);
    String fakeResponse = faker.gameOfThrones().character();

    when(mockCall.execute()).thenReturn(Response.success(fakeResponse));

    assertThat(allisaApiService.executeCall(mockCall)).contains(fakeResponse);
  }

  @Test
  void executeCall_null_body() throws IOException {
    Call<String> mockCall = mock(Call.class);

    when(mockCall.execute()).thenReturn(Response.success(null));

    assertThatThrownBy(() -> allisaApiService.executeCall(mockCall))
        .isInstanceOf(ConnectorException.class)
        .isEqualToComparingFieldByField(new ConnectorException("There was an unexpected error "
            + "when trying to connect to Allisa."));
  }

  @Test
  void executeCall_error() throws IOException {
    Call<String> mockCall = mock(Call.class);
    String errorMessage = faker.gameOfThrones().quote();
    int errorCode = faker.number().numberBetween(400, 500);

    // prevent null pointer exception while logging, no relevant for assertions
    when(mockCall.request()).thenReturn(new Request.Builder().url("http://fake.url").build());
    when(mockCall.execute())
        .thenReturn(Response.error(errorCode, ResponseBody.create(
            MediaType.get("application/json"),
            new Gson().toJson(new ApiResponse<>().setCode(errorCode).setMessage(errorMessage)))));

    assertThatThrownBy(() -> allisaApiService.executeCall(mockCall))
        .isInstanceOf(ConnectorException.class)
        .isEqualToComparingFieldByField(new ConnectorException("Unable to post time to Allisa: " + errorMessage));
  }

  @Test
  void postTimeTest() {
    AllisaApiService serviceSpy = spy(allisaApiService);
    doReturn(new ApiResponse<>()).when(serviceSpy).executeCall(any());

    TimePostData data = dataGenerator.randomTimePostData();

    serviceSpy.postTime(data);

    ArgumentCaptor<MultipartBody> bodyCaptor = ArgumentCaptor.forClass(MultipartBody.class);
    verify(allisaApiMock, times(1)).postTime(eq(WISETIME), bodyCaptor.capture());

    assertThat(bodyCaptor.getValue().parts().stream().map(part -> {
      Buffer buffer = new Buffer();
      try {
        part.body().writeTo(buffer);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return buffer.readUtf8();
    }).collect(Collectors.toList())).containsExactlyInAnyOrder(
        Long.toString(data.getTagId()), data.getUserId(), data.getNarrative(), data.getStartDateTime(),
        Long.toString(data.getChargeableTimeSecs()), Long.toString(data.getTotalTimeSecs()),
        data.getActivityCode()
    );
  }

  @Test
  void getAllisaCaseByTagNameTest() {
    AllisaApiService serviceSpy = spy(allisaApiService);

    String tagName = faker.lorem().word();
    AllisaCase expectedCase = new AllisaCase().setCaseReference(tagName);

    doReturn(new ApiResponse<AllisaCase>()
        .setCode(200)
        .setResult(new Result<AllisaCase>()
            .setData(ImmutableList.of(
                // add fake case to make sure that the filter works
                new AllisaCase().setCaseReference(faker.lorem().word()),
                expectedCase
            ))))
        .when(serviceSpy).executeCall(any());

    Optional<AllisaCase> result = serviceSpy.getAllisaCaseByTagName(tagName);

    assertThat(result).contains(expectedCase);

    verify(allisaApiMock, times(1)).getCase(WISETIME, tagName);
  }

  @Test
  void getNewAllisaCasesTest() {
    AllisaApiService serviceSpy = spy(allisaApiService);

    String tagName = faker.lorem().word();
    AllisaCase expectedCase1 = new AllisaCase().setCaseReference(tagName).setCaseId(6);
    AllisaCase expectedCase2 = new AllisaCase().setCaseReference(tagName).setCaseId(7);
    AllisaCase expectedCase3 = new AllisaCase().setCaseReference(tagName).setCaseId(8);

    AllisaCase filteredCase1 = new AllisaCase().setCaseReference(tagName).setCaseId(1);
    AllisaCase filteredCase2 = new AllisaCase().setCaseReference(tagName).setCaseId(5);

    doReturn(new ApiResponse<AllisaCase>()
        .setCode(200)
        .setResult(new Result<AllisaCase>()
            .setData(ImmutableList.of(
                expectedCase1, expectedCase2, expectedCase3, filteredCase1, filteredCase2
            ))))
        .when(serviceSpy).executeCall(any());

    List<AllisaCase> result = serviceSpy.getNewAllisaCases(5, 10, 10);

    assertThat(result).containsExactlyInAnyOrder(expectedCase1, expectedCase2, expectedCase3);

    verify(allisaApiMock, times(1)).getCases(WISETIME_CASES, 10, 10);
  }

  @Test
  void canConnectTest() {
    AllisaApiService serviceSpy = spy(allisaApiService);

    doReturn(new ApiResponse<AllisaCase>()
        .setCode(200)
        .setResult(new Result<AllisaCase>()
            .setData(ImmutableList.of())))
        .when(serviceSpy).executeCall(any());

    boolean result = serviceSpy.canConnect();

    assertThat(result).isTrue();

    verify(allisaApiMock, times(1)).getCases(WISETIME_CASES, 1, 1);
  }

  @Test
  void canConnectTestFailed() {
    AllisaApiService serviceSpy = spy(allisaApiService);

    doReturn(new ApiResponse<AllisaCase>()
        .setCode(400)
        .setResult(new Result<AllisaCase>()
            .setData(ImmutableList.of())))
        .when(serviceSpy).executeCall(any());

    boolean result = serviceSpy.canConnect();

    assertThat(result).isFalse();

    verify(allisaApiMock, times(1)).getCases(WISETIME_CASES, 1, 1);
  }

  @Test
  void canConnectTestFailedDueToException() {
    AllisaApiService serviceSpy = spy(allisaApiService);

    doThrow(new ConnectorException("Error"))
        .when(serviceSpy).executeCall(any());

    boolean result = serviceSpy.canConnect();

    assertThat(result).isFalse();

    verify(allisaApiMock, times(1)).getCases(WISETIME_CASES, 1, 1);
  }
}
