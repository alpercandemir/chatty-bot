package com.chatty.model;

import static com.chatty.store.Json.obj;
import static com.chatty.store.Json.str;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chatty.api.Problem;
import com.chatty.scenario.ScenarioPack;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class GeminiAdapterTest {

    private JsonNode diagnostics(String key, Exception cause) {
        GeminiAdapter adapter =
                new GeminiAdapter(key, "gemini-3.5-flash-lite") {
                    @Override
                    protected GenerateContentResponse generate(
                            List<Content> contents, GenerateContentConfig config, ObjectNode run)
                            throws Exception {
                        throw new ExecutionException(cause);
                    }
                };
        ScenarioPack pack = mock(ScenarioPack.class);
        when(pack.instructions()).thenReturn("Synthetic diagnostic test");
        when(pack.schemas()).thenReturn(Map.of());
        ObjectNode run = obj("id", "run-diagnostic-test");

        assertThatThrownBy(() -> adapter.next(pack, obj(), run)).isInstanceOf(Problem.class);
        assertThat(str(run, "providerCauseType")).isEqualTo(cause.getClass().getSimpleName());
        return run.path("providerError");
    }

    @Test
    void highDemandResponsePreservesTheProviderReasonAndHttpStatus() {
        String message =
                "This model is currently experiencing high demand. Please try again later.";
        ApiException error = new ApiException(503, "UNAVAILABLE", message);

        JsonNode detail = diagnostics("test-key", error);

        assertThat(detail.path("httpCode").asInt()).isEqualTo(503);
        assertThat(str(detail, "status")).isEqualTo("UNAVAILABLE");
        assertThat(str(detail, "message")).isEqualTo(message);
        assertThat(str(detail, "model")).isEqualTo("gemini-3.5-flash-lite");
        assertThat(str(detail, "exceptionType")).isEqualTo("ExecutionException");
        assertThat(detail.path("retryable").asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 503})
    void providerErrorsRetainTheirCodeAndDistinguishRetryableFailures(int code) {
        JsonNode detail =
                diagnostics("test-key", new ApiException(code, "TEST", "Provider reason"));

        assertThat(detail.path("httpCode").asInt()).isEqualTo(code);
        assertThat(str(detail, "message")).isEqualTo("Provider reason");
        assertThat(detail.path("retryable").asBoolean()).isEqualTo(code == 429 || code >= 500);
    }

    @Test
    void providerMessagesRedactKeysAndCannotCreateExtraLogLines() {
        String key = "synthetic-provider-secret";
        String otherKey = "AIza" + "a".repeat(35);
        String message =
                "Invalid API key "
                        + key
                        + "\nhttps://example.test/?key=another-secret&ok=1\t"
                        + otherKey;

        JsonNode detail = diagnostics(key, new ApiException(400, "INVALID_ARGUMENT", message));

        assertThat(detail.toString()).doesNotContain(key, otherKey, "another-secret");
        assertThat(str(detail, "message")).contains("[REDACTED]").doesNotContain("\n", "\t");
        assertThat(detail.path("retryable").asBoolean()).isFalse();
    }

    @Test
    void longProviderMessagesAreBoundedAfterRedaction() {
        JsonNode detail =
                diagnostics("test-key", new ApiException(400, "INVALID_ARGUMENT", "x".repeat(800)));

        assertThat(str(detail, "message")).hasSize(501).endsWith("…");
    }

    @Test
    void transportAndTimeoutFailuresUseSafeSummariesInsteadOfRawExceptionMessages() {
        for (Exception error :
                List.of(
                        new IOException("private transport details"),
                        new TimeoutException("private timeout details"))) {
            JsonNode detail = diagnostics("test-key", error);

            assertThat(detail.path("httpCode").asInt()).isZero();
            assertThat(str(detail, "status")).isEqualTo("TRANSPORT_OR_SDK");
            assertThat(str(detail, "message")).isEqualTo(GeminiAdapter.failure(error).getMessage());
            assertThat(detail.toString()).doesNotContain("private");
            assertThat(detail.path("retryable").asBoolean()).isTrue();
        }
    }
}
