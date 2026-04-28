package com.zhituagent.api;

import com.zhituagent.ZhituAgentApplication;
import com.zhituagent.llm.LlmRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = {ZhituAgentApplication.class, ChatControllerTest.StubConfig.class})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class StubConfig {

        @Bean
        @Primary
        LlmRuntime testLlmRuntime() {
            return new LlmRuntime() {
                @Override
                public String generate(String systemPrompt, List<String> messages, Map<String, Object> metadata) {
                    return "Mock answer";
                }

                @Override
                public void stream(String systemPrompt,
                                   List<String> messages,
                                   Map<String, Object> metadata,
                                   Consumer<String> onToken,
                                   Runnable onComplete) {
                    onToken.accept("第一版");
                    onToken.accept("建议先把主链路跑通");
                    onComplete.run();
                }
            };
        }
    }

    @Test
    void shouldReturnMockAnswerForChatEndpoint() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "介绍一下第一版目标"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("sess_10001"))
                .andExpect(jsonPath("$.answer").value("Mock answer"))
                .andExpect(jsonPath("$.trace.path").value("direct-answer"))
                .andExpect(jsonPath("$.trace.retrievalHit").value(false))
                .andExpect(jsonPath("$.trace.toolUsed").value(false))
                .andExpect(jsonPath("$.trace.retrievalMode").value("none"))
                .andExpect(jsonPath("$.trace.contextStrategy").value("recent-summary"))
                .andExpect(jsonPath("$.trace.requestId").isNotEmpty())
                .andExpect(jsonPath("$.trace.latencyMs").isNumber())
                .andExpect(jsonPath("$.trace.snippetCount").value(0))
                .andExpect(jsonPath("$.trace.topSource").value(""))
                .andExpect(jsonPath("$.trace.topScore").value(0.0))
                .andExpect(jsonPath("$.trace.retrievalCandidateCount").value(0))
                .andExpect(jsonPath("$.trace.rerankModel").value(""))
                .andExpect(jsonPath("$.trace.rerankTopScore").value(0.0))
                .andExpect(jsonPath("$.trace.inputTokenEstimate").isNumber())
                .andExpect(jsonPath("$.trace.outputTokenEstimate").isNumber());
    }

    @Test
    void shouldEmitStartTokenAndCompleteEventsForStreamEndpoint() throws Exception {
        MvcResult mvcResult = mockMvc.perform(post("/api/streamChat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "流式介绍一下当前方案"
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        Thread.sleep(250);

        assertThat(mvcResult.getResponse().getStatus(), org.hamcrest.Matchers.is(200));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:start"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:token"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("event:complete"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"retrievalMode\":\"none\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"contextStrategy\":\"recent-summary\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"retrievalCandidateCount\":0"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"rerankModel\":\"\""));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"rerankTopScore\":0.0"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"inputTokenEstimate\":"));
        assertThat(mvcResult.getResponse().getContentAsString(), containsString("\"outputTokenEstimate\":"));
    }

    @Test
    void shouldLogChatRouteDecision(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId": "sess_10001",
                                  "userId": "user_20001",
                                  "message": "介绍一下第一版目标"
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(output).contains("chat.completed sessionId=sess_10001 path=direct-answer retrievalHit=false toolUsed=false");
    }
}
