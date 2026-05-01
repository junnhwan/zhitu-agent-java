package com.zhituagent.eval;

import com.zhituagent.config.EvalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@ConditionalOnProperty(prefix = "zhitu.eval", name = "enabled", havingValue = "true")
public class EvalApplicationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalApplicationRunner.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BaselineEvalRunner baselineEvalRunner;
    private final BaselineComparisonReporter comparisonReporter;
    private final EvalProperties evalProperties;
    private final ConfigurableApplicationContext applicationContext;

    public EvalApplicationRunner(BaselineEvalRunner baselineEvalRunner,
                                 BaselineComparisonReporter comparisonReporter,
                                 EvalProperties evalProperties,
                                 ConfigurableApplicationContext applicationContext) {
        this.baselineEvalRunner = baselineEvalRunner;
        this.comparisonReporter = comparisonReporter;
        this.evalProperties = evalProperties;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            if (!evalProperties.getCompareLabels().isEmpty()) {
                runComparison();
            } else {
                runFixture();
            }
        } finally {
            if (evalProperties.isExitAfterRun()) {
                SpringApplication.exit(applicationContext, () -> 0);
            }
        }
    }

    private void runFixture() throws Exception {
        String label = evalProperties.getLabel();
        String labelSegment = label.isBlank() ? "comparison" : label;
        Path reportPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-" + labelSegment + "-" + FILE_TS.format(LocalDateTime.now()) + ".json"
        );
        BaselineEvalComparisonReport report = baselineEvalRunner.runModeComparisonFixture(evalProperties.getModes(), reportPath);
        log.info(
                "评估报告已生成 eval.completed label={} totalModes={} reportPath={} requestedModes={}",
                labelSegment,
                report.totalModes(),
                reportPath.toAbsolutePath(),
                String.join(",", report.requestedModes())
        );
    }

    private void runComparison() throws Exception {
        String tsSuffix = FILE_TS.format(LocalDateTime.now());
        String joinedLabels = String.join("-vs-", evalProperties.getCompareLabels());
        Path jsonPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-compare-" + joinedLabels + "-" + tsSuffix + ".json"
        );
        Path markdownPath = Path.of(
                evalProperties.getReportDir(),
                "baseline-compare-" + joinedLabels + "-" + tsSuffix + ".md"
        );
        comparisonReporter.compareLatest(
                Path.of(evalProperties.getReportDir()),
                evalProperties.getCompareLabels(),
                jsonPath,
                markdownPath
        );
        log.info(
                "对比报告已生成 eval.compare.completed labels={} jsonPath={} markdownPath={}",
                joinedLabels,
                jsonPath.toAbsolutePath(),
                markdownPath.toAbsolutePath()
        );
    }
}
