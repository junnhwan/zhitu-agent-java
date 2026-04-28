package com.zhituagent.memory;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FactExtractorTest {

    @Test
    void shouldExtractStableFactsFromUserStatementsOnly() {
        FactExtractor extractor = new FactExtractor();

        List<String> facts = extractor.extract(List.of(
                new ChatMessageRecord("user", "我叫小智", OffsetDateTime.now()),
                new ChatMessageRecord("assistant", "你好，小智", OffsetDateTime.now()),
                new ChatMessageRecord("user", "我在杭州做 Java Agent 后端开发", OffsetDateTime.now()),
                new ChatMessageRecord("user", "现在几点了？", OffsetDateTime.now())
        ));

        assertThat(facts).containsExactly(
                "我叫小智",
                "我在杭州做 Java Agent 后端开发"
        );
    }

    @Test
    void shouldIgnoreQuestionLikeIntentStatementsEvenIfTheyStartWithWoShi() {
        FactExtractor extractor = new FactExtractor();

        List<String> facts = extractor.extract(List.of(
                new ChatMessageRecord("user", "我是想问第一阶段先做什么", OffsetDateTime.now()),
                new ChatMessageRecord("user", "我叫小智", OffsetDateTime.now())
        ));

        assertThat(facts).containsExactly("我叫小智");
    }
}
