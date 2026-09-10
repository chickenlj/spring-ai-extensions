/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dashscope.api;

import com.alibaba.cloud.ai.dashscope.rag.DashScopeDocumentRetrieverOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.ai.rag.Query;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class BailianRequestIdLoggingInterceptorTests {

	@ParameterizedTest
	@ValueSource(strings = {
			"/api/v1/datacenter/category/category/file/file/query",
			"/api/v1/datacenter/category/category/file/file/download_lease",
			"/api/v1/datacenter/category/category/upload_lease",
			"/api/v1/datacenter/category/category/add_file",
			"/api/v1/indices/component/configed_transformations/spliter",
			"/api/v1/indices/pipeline_simple",
			"/api/v1/indices/pipeline",
			"/api/v1/indices/pipeline/pipeline/managed_ingest",
			"/api/v1/indices/pipeline/pipeline/delete",
			"/api/v1/indices/pipeline/pipeline/retrieve" })
	void logsAllKnowledgeBaseEndpointsAndPreservesBody(String path, CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().requestInterceptor(new BailianRequestIdLoggingInterceptor());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		String body = "{\"request_id\":\"trace-123\",\"data\":\"private-document-content\"}";
		server.expect(requestTo(path)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(builder.build().post().uri(path).retrieve().body(String.class)).isEqualTo(body);
		assertThat(output.getOut()).contains("method=POST", "path=" + path, "status=200", "requestId=trace-123")
			.doesNotContain("private-document-content");
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "request_id", "requestId", "RequestId" })
	void logsRequestIdBeforeHttpErrorHandlerAndPreservesErrorBody(String field, CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().requestInterceptor(new BailianRequestIdLoggingInterceptor());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		String body = "{\"" + field + "\":\"error-123\",\"message\":\"upstream failure\"}";
		String path = "/api/v1/indices/pipeline/pipeline/retrieve";
		server.expect(requestTo(path))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(body));

		assertThatThrownBy(() -> builder.build().post().uri(path).retrieve().body(String.class))
			.isInstanceOfSatisfying(HttpServerErrorException.class,
					ex -> assertThat(ex.getResponseBodyAsString()).isEqualTo(body));
		assertThat(output.getOut()).contains("status=500", "requestId=error-123");
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"request_id\":null}", "{\"request_id\":{}}", "<html>Bad Gateway</html>", "" })
	void toleratesMissingRequestIdAndNonJsonResponses(String body, CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().requestInterceptor(new BailianRequestIdLoggingInterceptor());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		String path = "/api/v1/indices/pipeline";
		server.expect(requestTo(path)).andRespond(withSuccess(body, MediaType.TEXT_PLAIN));

		assertThat(builder.build().get().uri(path).retrieve().body(String.class))
			.isEqualTo(body.isEmpty() ? null : body);
		assertThat(output.getOut()).contains("requestId=<");
		server.verify();
	}

	@Test
	void retrieverAndDeprecatedRetrieverLogOnceAfterMutate(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		DashScopeApi api = DashScopeApi.builder().apiKey("test-key").baseUrl("https://example.test")
			.restClientBuilder(builder).build().mutate().build();
		String url = "https://example.test/api/v1/indices/pipeline/pipeline/retrieve";
		server.expect(requestTo(url)).andRespond(withSuccess(
				"{\"request_id\":\"query-trace\",\"code\":\"SUCCESS\",\"nodes\":[]}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(url)).andRespond(withSuccess(
				"{\"request_id\":\"legacy-trace\",\"code\":\"SUCCESS\",\"nodes\":[]}", MediaType.APPLICATION_JSON));

		assertThat(api.retriever("pipeline", new Query("question"), new DashScopeDocumentRetrieverOptions())).isEmpty();
		assertThat(api.retriever("pipeline", "question", new DashScopeDocumentRetrieverOptions())).isEmpty();
		assertThat(output.getOut()).containsOnlyOnce("requestId=query-trace").containsOnlyOnce("requestId=legacy-trace");
		server.verify();
	}

	@Test
	void leavesModelResponsesOutsideKnowledgeBaseLogging(CapturedOutput output) {
		RestClient.Builder builder = RestClient.builder().requestInterceptor(new BailianRequestIdLoggingInterceptor());
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		String path = "/api/v1/services/aigc/text-generation/generation";
		String body = "{\"request_id\":\"model-trace\"}";
		server.expect(requestTo(path)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		assertThat(builder.build().post().uri(path).retrieve().body(String.class)).isEqualTo(body);
		assertThat(output.getOut()).doesNotContain("Bailian response:");
		server.verify();
	}

}
