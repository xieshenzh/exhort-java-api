/*
 * Copyright © 2023 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.exhort.impl;

import static org.assertj.core.api.BDDAssertions.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junitpioneer.jupiter.ClearEnvironmentVariable;
import org.junitpioneer.jupiter.SetEnvironmentVariable;
import org.mockito.ArgumentMatcher;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.exhort.Provider;
import com.redhat.exhort.api.v3.AnalysisReport;
import com.redhat.exhort.tools.Ecosystem;

@ExtendWith(MockitoExtension.class)
@ClearEnvironmentVariable(key="EXHORT_SNYK_TOKEN")
@ClearEnvironmentVariable(key="EXHORT_DEV_MODE")
@ClearEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL")
@ClearEnvironmentVariable(key="RHDA_TOKEN")
@ClearEnvironmentVariable(key="RHDA_SOURCE")
@SuppressWarnings("unchecked")
class Exhort_Api_Test {
  @Mock
  Provider mockProvider;
  @Mock
  HttpClient mockHttpClient;
  @InjectMocks
  ExhortApi exhortApiSut;

  @AfterEach
  void cleanup() {
    System.clearProperty("EXHORT_SNYK_TOKEN");
  }

  @Test
  @SetEnvironmentVariable(key="EXHORT_SNYK_TOKEN", value="snyk-token-from-env-var")
  @SetEnvironmentVariable(key="RHDA_TOKEN", value="rhda-token-from-env-var")
  @SetEnvironmentVariable(key="RHDA_SOURCE", value="rhda-source-from-env-var")
  void stackAnalysisHtml_with_pom_xml_should_return_html_report_from_the_backend()
      throws IOException, ExecutionException, InterruptedException {
    // create a temporary pom.xml file
    var tmpFile = Files.createTempFile("exhort_test_pom_", ".xml");
    try (var is = getClass().getModule().getResourceAsStream("tst_manifests/maven/empty/pom.xml")) {
      Files.write(tmpFile, is.readAllBytes());
    }

    // stub the mocked provider with a fake content object
    given(mockProvider.provideStack(tmpFile))
      .willReturn(new Provider.Content("fake-body-content".getBytes(), "fake-content-type"));

    // create an argument matcher to make sure we mock the response to for right request
    ArgumentMatcher<HttpRequest> matchesRequest = r ->
      r.headers().firstValue("Content-Type").get().equals("fake-content-type") &&
      r.headers().firstValue("Accept").get().equals("text/html") &&
      // snyk token is set using the environment variable (annotation)
      r.headers().firstValue("ex-snyk-token").get().equals("snyk-token-from-env-var") &&
      r.headers().firstValue("rhda-token").get().equals("rhda-token-from-env-var") &&
        r.headers().firstValue("rhda-source").get().equals("rhda-source-from-env-var") &&
        r.headers().firstValue("rhda-operation-type").get().equals("Stack Analysis") &&

      r.method().equals("POST");

    // load dummy html and set as the expected analysis
    byte[] expectedHtml;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.html")) {
      expectedHtml = is.readAllBytes();
    }

    // mock and http response object and stub it to return a fake body
    var mockHttpResponse = mock(HttpResponse.class);
    given(mockHttpResponse.body()).willReturn(expectedHtml);
    given(mockHttpResponse.statusCode()).willReturn(200);

    // mock static getProvider utility function
    try(var ecosystemTool = mockStatic(Ecosystem.class)) {
      // stub static getProvider utility function to return our mock provider
      ecosystemTool.when(() -> Ecosystem.getProvider(tmpFile)).thenReturn(mockProvider);

      // stub the http client to return our mocked response when request matches our arg matcher
      given(mockHttpClient.sendAsync(argThat(matchesRequest), any()))
        .willReturn(CompletableFuture.completedFuture(mockHttpResponse));

      // when invoking the api for a html stack analysis report
      var htmlTxt = exhortApiSut.stackAnalysisHtml(tmpFile.toString());
      // verify we got the correct html response
      then(htmlTxt.get()).isEqualTo(expectedHtml);
    }
    // cleanup
    Files.deleteIfExists(tmpFile);
  }

  @Test
//    System.setProperty("RHDA_TOKEN", "rhda-token-from-property");
//    System.setProperty("RHDA_SOURCE", "rhda-source-from-property");
  @SetEnvironmentVariable(key="EXHORT_SNYK_TOKEN", value="snyk-token-from-env-var")
  @SetEnvironmentVariable(key="RHDA_TOKEN", value="rhda-token-from-env-var")
  @SetEnvironmentVariable(key="RHDA_SOURCE", value="rhda-source-from-env-var")
  void stackAnalysis_with_pom_xml_should_return_json_object_from_the_backend()
    throws IOException, ExecutionException, InterruptedException {
    // create a temporary pom.xml file
    var tmpFile = Files.createTempFile("exhort_test_pom_", ".xml");
    try (var is = getClass().getModule().getResourceAsStream("tst_manifests/maven/empty/pom.xml")) {
      Files.write(tmpFile, is.readAllBytes());
    }

    // stub the mocked provider with a fake content object
    given(mockProvider.provideStack(tmpFile))
      .willReturn(new Provider.Content("fake-body-content".getBytes(), "fake-content-type"));

    // we expect this to be ignored because tokens from env vars takes precedence
    System.setProperty("EXHORT_SNYK_TOKEN", "snyk-token-from-property");

    // create an argument matcher to make sure we mock the response for the right request
    ArgumentMatcher<HttpRequest> matchesRequest = r ->
      r.headers().firstValue("Content-Type").get().equals("fake-content-type") &&
        r.headers().firstValue("Accept").get().equals("application/json") &&
        // snyk token is set using the environment variable (annotation) - ignored the one set in properties
        r.headers().firstValue("ex-snyk-token").get().equals("snyk-token-from-env-var") &&
        r.headers().firstValue("rhda-token").get().equals("rhda-token-from-env-var") &&
        r.headers().firstValue("rhda-source").get().equals("rhda-source-from-env-var") &&
        r.headers().firstValue("rhda-operation-type").get().equals("Stack Analysis") &&
        r.method().equals("POST");

    // load dummy json and set as the expected analysis
    var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    AnalysisReport expectedAnalysis;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.json")) {
      expectedAnalysis = mapper.readValue(is, AnalysisReport.class);
    }

    // mock and http response object and stub it to return the expected analysis
    var mockHttpResponse = mock(HttpResponse.class);
    given(mockHttpResponse.body()).willReturn(mapper.writeValueAsString(expectedAnalysis));
    given(mockHttpResponse.statusCode()).willReturn(200);

    // mock static getProvider utility function
    try(var ecosystemTool = mockStatic(Ecosystem.class)) {
      // stub static getProvider utility function to return our mock provider
      ecosystemTool.when(() -> Ecosystem.getProvider(tmpFile)).thenReturn(mockProvider);

      // stub the http client to return our mocked response when request matches our arg matcher
      given(mockHttpClient.sendAsync(argThat(matchesRequest), any()))
        .willReturn(CompletableFuture.completedFuture(mockHttpResponse));

      // when invoking the api for a json stack analysis report
      var responseAnalysis = exhortApiSut.stackAnalysis(tmpFile.toString());
      // verify we got the correct analysis report
      then(responseAnalysis.get()).isEqualTo(expectedAnalysis);
    }
    // cleanup
    Files.deleteIfExists(tmpFile);
  }

  @Test
  void componentAnalysis_with_pom_xml_should_return_json_object_from_the_backend()
    throws IOException, ExecutionException, InterruptedException {
    // load pom.xml
    byte[] targetPom;
    try (var is = getClass().getModule().getResourceAsStream("tst_manifests/maven/empty/pom.xml")) {
      targetPom = is.readAllBytes();
    }

    // stub the mocked provider with a fake content object
    given(mockProvider.provideComponent(targetPom))
      .willReturn(new Provider.Content("fake-body-content".getBytes(), "fake-content-type"));

    // we expect this to picked up because no env var to take precedence
    System.setProperty("EXHORT_SNYK_TOKEN", "snyk-token-from-property");
    System.setProperty("RHDA_TOKEN", "rhda-token-from-property");
    System.setProperty("RHDA_SOURCE", "rhda-source-from-property");

    // create an argument matcher to make sure we mock the response for the right request
    ArgumentMatcher<HttpRequest> matchesRequest = r ->
      r.headers().firstValue("Content-Type").get().equals("fake-content-type") &&
        r.headers().firstValue("Accept").get().equals("application/json") &&
        // snyk token is set using properties which is picked up because no env var specified
        r.headers().firstValue("ex-snyk-token").get().equals("snyk-token-from-property") &&
        r.headers().firstValue("rhda-token").get().equals("rhda-token-from-property") &&
        r.headers().firstValue("rhda-source").get().equals("rhda-source-from-property") &&
        r.headers().firstValue("rhda-operation-type").get().equals("Component Analysis") &&
        r.method().equals("POST");

    // load dummy json and set as the expected analysis
    var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    AnalysisReport expectedReport;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.json")) {
      expectedReport = mapper.readValue(is, AnalysisReport.class);
    }

    // mock and http response object and stub it to return the expected analysis
    var mockHttpResponse = mock(HttpResponse.class);
    given(mockHttpResponse.body()).willReturn(mapper.writeValueAsString(expectedReport));
    given(mockHttpResponse.statusCode()).willReturn(200);

    // mock static getProvider utility function
    try (var ecosystemTool = mockStatic(Ecosystem.class)) {
      // stub static getProvider utility function to return our mock provider
      ecosystemTool.when(() -> Ecosystem.getProvider("pom.xml")).thenReturn(mockProvider);

      // stub the http client to return our mocked response when request matches our arg matcher
      given(mockHttpClient.sendAsync(argThat(matchesRequest), any()))
        .willReturn(CompletableFuture.completedFuture(mockHttpResponse));

      // when invoking the api for a json stack analysis report
      var responseAnalysis = exhortApiSut.componentAnalysis("pom.xml", targetPom);
      // verify we got the correct analysis report
      then(responseAnalysis.get()).isEqualTo(expectedReport);
    }
  }

  @Test
  void stackAnalysisMixed_with_pom_xml_should_return_both_html_text_and_json_object_from_the_backend()
    throws IOException, ExecutionException, InterruptedException {
    // load dummy json and set as the expected analysis
    var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    AnalysisReport expectedJson;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.json")) {
      expectedJson = mapper.readValue(is, AnalysisReport.class);
    }

    // load dummy html and set as the expected analysis
    byte[] expectedHtml;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.html")) {
      expectedHtml = is.readAllBytes();
    }

    // create a temporary pom.xml file
    var tmpFile = Files.createTempFile("exhort_test_pom_", ".xml");
    try (var is = getClass().getModule().getResourceAsStream("tst_manifests/maven/empty/pom.xml")) {
      Files.write(tmpFile, is.readAllBytes());
    }

    // stub the mocked provider with a fake content object
    given(mockProvider.provideStack(tmpFile))
      .willReturn(new Provider.Content("fake-body-content".getBytes(), "fake-content-type"));

    // create an argument matcher to make sure we mock the response for the right request
    ArgumentMatcher<HttpRequest> matchesRequest = r ->
      r.headers().firstValue("Content-Type").get().equals("fake-content-type") &&
        r.headers().firstValue("Accept").get().equals("multipart/mixed") &&
        r.method().equals("POST");

    // load dummy mixed and set as the expected analysis
    byte[] mixedResponse;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.mixed")) {
      mixedResponse = is.readAllBytes();
    }

    // mock and http response object and stub it to return the expected analysis
    var mockHttpResponse = mock(HttpResponse.class);
    given(mockHttpResponse.body()).willReturn(mixedResponse);
    given(mockHttpResponse.statusCode()).willReturn(200);

    // mock static getProvider utility function
    try(var ecosystemTool = mockStatic(Ecosystem.class)) {
      // stub static getProvider utility function to return our mock provider
      ecosystemTool.when(() -> Ecosystem.getProvider(tmpFile)).thenReturn(mockProvider);

      // stub the http client to return our mocked response when request matches our arg matcher
      given(mockHttpClient.sendAsync(argThat(matchesRequest), any()))
        .willReturn(CompletableFuture.completedFuture(mockHttpResponse));

      // when invoking the api for a json stack analysis mixed report
      var responseAnalysis = exhortApiSut.stackAnalysisMixed(tmpFile.toString()).get();
      // verify we got the correct mixed report
      then(new String(responseAnalysis.html).trim()).isEqualTo(new String(expectedHtml).trim());
      then(responseAnalysis.json).isEqualTo(expectedJson);
    }
    // cleanup
    Files.deleteIfExists(tmpFile);
  }

  @Test
  void componentAnalysis_with_pom_xml_as_path_should_return_json_object_from_the_backend()
    throws IOException, ExecutionException, InterruptedException {
    // load pom.xml
    var tmpFile = Files.createTempFile("exhort_test_pom_", ".xml");
    try (var is = getClass().getModule().getResourceAsStream("tst_manifests/maven/empty/pom.xml")) {
      Files.write(tmpFile, is.readAllBytes());
    }

    // stub the mocked provider with a fake content object
    given(mockProvider.provideComponent(tmpFile))
      .willReturn(new Provider.Content("fake-body-content".getBytes(), "fake-content-type"));

    // we expect this to picked up because no env var to take precedence
    System.setProperty("EXHORT_SNYK_TOKEN", "snyk-token-from-property");

    // create an argument matcher to make sure we mock the response for the right request
    ArgumentMatcher<HttpRequest> matchesRequest = r ->
      r.headers().firstValue("Content-Type").get().equals("fake-content-type") &&
        r.headers().firstValue("Accept").get().equals("application/json") &&
        // snyk token is set using properties which is picked up because no env var specified
        r.headers().firstValue("ex-snyk-token").get().equals("snyk-token-from-property") &&
        r.method().equals("POST");

    // load dummy json and set as the expected analysis
    var mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    AnalysisReport expectedReport;
    try (var is = getClass().getModule().getResourceAsStream("dummy_responses/maven/analysis-report.json")) {
      expectedReport = mapper.readValue(is, AnalysisReport.class);
    }

    // mock and http response object and stub it to return the expected analysis
    var mockHttpResponse = mock(HttpResponse.class);
    given(mockHttpResponse.body()).willReturn(mapper.writeValueAsString(expectedReport));
    given(mockHttpResponse.statusCode()).willReturn(200);

    // mock static getProvider utility function
    try (var ecosystemTool = mockStatic(Ecosystem.class)) {
      // stub static getProvider utility function to return our mock provider
      ecosystemTool.when(() -> Ecosystem.getProvider(tmpFile)).thenReturn(mockProvider);

      // stub the http client to return our mocked response when request matches our arg matcher
      given(mockHttpClient.sendAsync(argThat(matchesRequest), any()))
        .willReturn(CompletableFuture.completedFuture(mockHttpResponse));

      // when invoking the api for a json stack analysis report
      var responseAnalysis = exhortApiSut.componentAnalysis(tmpFile.toString());
      // verify we got the correct analysis report
      then(responseAnalysis.get()).isEqualTo(expectedReport);
      //cleanup
      Files.deleteIfExists(tmpFile);
    }
  }


  @AfterEach
  void afterEach() {
    System.clearProperty("EXHORT_DEV_MODE");
    System.clearProperty("DEV_EXHORT_BACKEND_URL");
    System.clearProperty("RHDA_TOKEN");
    System.clearProperty("RHDA_SOURCE");

  }

  @Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value="true")
  @ClearEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL")
  void check_Exhort_Url_When_DEV_Mode_true_Both() {
      System.setProperty("EXHORT_DEV_MODE","true");
      ExhortApi exhortApi = new ExhortApi();
      then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
      then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT);
  }
@Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value="true")
  @ClearEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL")
  void check_Exhort_Url_When_env_DEV_Mode_true_property_DEV_Mode_false() {
      System.setProperty("EXHORT_DEV_MODE","false");
      ExhortApi exhortApi = new ExhortApi();
      then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
      then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT);
  }

@Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value="true")
  @ClearEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL")
  void check_Exhort_Url_When_env_DEV_Mode_true_And_DEV_Exhort_Url_Set_Then_Default_DEV_Exhort_URL_Not_Selected() {
      String dummyUrl = "http://dummy-url";
      System.setProperty("DEV_EXHORT_BACKEND_URL", dummyUrl);
      ExhortApi exhortApi = new ExhortApi();
      then(exhortApi.getEndpoint()).isEqualTo(dummyUrl);
      then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
  }

@Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value="false")
  @ClearEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL")
void check_Exhort_Url_When_env_DEV_Mode_false_And_DEV_Exhort_Url_Set_Then_Default_DEV_Exhort_URL_Not_Selected() {
    System.setProperty("EXHORT_DEV_MODE", "false");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
  }


  @Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value= "false")
  void check_Exhort_Url_When_env_DEV_Mode_false_And_Property_Dev_Mode_true_Default_Exhort_URL_Selected() {
    System.setProperty("EXHORT_DEV_MODE", "true");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
  }

  @Test
  @SetEnvironmentVariable(key="EXHORT_DEV_MODE", value="false")
  @SetEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL", value="http://dummy-route")
  void check_Exhort_Url_When_env_DEV_Mode_false_And_DEV_Exhort_Url_Set_Then_Default_Exhort_URL_Selected_Anyway() {
    System.setProperty("EXHORT_DEV_MODE", "true");
    System.setProperty("DEV_EXHORT_BACKEND_URL","http://dummy-route2");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isNotEqualTo(System.getenv("DEV_EXHORT_BACKEND_URL"));
    then(exhortApi.getEndpoint()).isNotEqualTo(System.getProperty("DEV_EXHORT_BACKEND_URL"));

  }
  @Test
  void check_Exhort_Url_When_env_DEV_Mode_not_set_And_Property_Exhort_Dev_Mode_false_Then_Default_Exhort_URL_Selected() {
    System.setProperty("EXHORT_DEV_MODE", "false");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
  }
  @Test
  void check_Exhort_Url_When_env_DEV_Mode_not_set_And_Property_Exhort_Dev_Mode_true_Then_Default_DEV_Exhort_URL_Selected() {
    System.setProperty("EXHORT_DEV_MODE", "true");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
  }
  @Test
  @SetEnvironmentVariable(key="DEV_EXHORT_BACKEND_URL", value="http://dummy-route")
  void check_Exhort_Url_When_env_DEV_Mode_not_set_And_Property_Exhort_Dev_Mode_true_and_Env_DEV_Exhort_Backend_Url_Set_Then_DEV_ENV_Exhort_URL_Selected() {
    System.setProperty("EXHORT_DEV_MODE", "true");
    System.setProperty("DEV_EXHORT_BACKEND_URL", "http://dummy-route2");
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT);
    then(exhortApi.getEndpoint()).isNotEqualTo(ExhortApi.DEFAULT_ENDPOINT_DEV);
    then(exhortApi.getEndpoint()).isNotEqualTo("http://dummy-route2");
    then(exhortApi.getEndpoint()).isEqualTo("http://dummy-route");
  }

  @Test
  void check_Exhort_Url_When_Nothing_Set_Then_Default_Exhort_URL_Selected() {
    ExhortApi exhortApi = new ExhortApi();
    then(exhortApi.getEndpoint()).isEqualTo(ExhortApi.DEFAULT_ENDPOINT);

  }

}
