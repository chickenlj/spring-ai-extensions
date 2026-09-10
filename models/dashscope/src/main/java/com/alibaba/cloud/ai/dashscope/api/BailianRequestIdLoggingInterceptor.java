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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

/**
 * Logs Bailian knowledge base request IDs before response conversion or error handling.
 */
final class BailianRequestIdLoggingInterceptor implements ClientHttpRequestInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(BailianRequestIdLoggingInterceptor.class);

	private static final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		ClientHttpResponse response = execution.execute(request, body);
		String path = request.getURI().getPath();
		if (!path.startsWith("/api/v1/indices/") && !path.startsWith("/api/v1/datacenter/")) {
			return response;
		}
		try {
			byte[] responseBody = response.getBody().readAllBytes();
			logger.info("Bailian response: method={}, path={}, status={}, requestId={}", request.getMethod(), path,
					response.getStatusCode().value(), extractRequestId(responseBody));
			// Both message converters and error handlers still need the original body.
			return new ClientHttpResponse() {
				@Override
				public HttpStatusCode getStatusCode() throws IOException {
					return response.getStatusCode();
				}

				@Override
				public String getStatusText() throws IOException {
					return response.getStatusText();
				}

				@Override
				public HttpHeaders getHeaders() {
					return response.getHeaders();
				}

				@Override
				public InputStream getBody() {
					return new ByteArrayInputStream(responseBody);
				}

				@Override
				public void close() {
					response.close();
				}
			};
		}
		catch (IOException | RuntimeException ex) {
			response.close();
			throw ex;
		}
	}

	private static String extractRequestId(byte[] body) {
		try {
			JsonNode root = objectMapper.readTree(body);
			if (root != null && root.isObject()) {
				for (String field : new String[] { "request_id", "requestId", "RequestId" }) {
					JsonNode value = root.get(field);
					if (value != null && value.isTextual() && StringUtils.hasText(value.textValue())) {
						return value.textValue().replace('\r', '_').replace('\n', '_');
					}
				}
			}
			return "<missing>";
		}
		catch (IOException ex) {
			// Logging must not change how malformed or non-JSON responses are handled.
			return "<unparseable>";
		}
	}

}
