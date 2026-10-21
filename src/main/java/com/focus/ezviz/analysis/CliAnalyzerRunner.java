package com.focus.ezviz.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.focus.ezviz.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过本机 Python 子进程调用算法包：
 * <pre>
 * python3 analyzer/analyze.py --video &lt;file&gt; --output &lt;json&gt; --tasks focus,distraction,hands,phone,audio
 * </pre>
 * 约定：脚本 stdout 只输出结果 JSON 的路径，stderr 输出日志。
 */
@Component
@ConditionalOnProperty(name = "app.analyzer.mode", havingValue = "cli", matchIfMissing = true)
public class CliAnalyzerRunner implements AnalyzerRunner {

    private static final Logger log = LoggerFactory.getLogger(CliAnalyzerRunner.class);

    private final AppProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CliAnalyzerRunner(AppProperties props) {
        this.props = props;
    }

    @Override
    public JsonNode analyze(String videoFile, String roi, String tasks) {
        AppProperties.Analyzer cfg = props.getAnalyzer();
        Path video = Paths.get(videoFile).toAbsolutePath();
        if (!Files.exists(video)) {
            throw new AnalyzerException("视频文件不存在: " + video);
        }
        Path script = Paths.get(cfg.getScript()).toAbsolutePath();
        if (!Files.exists(script)) {
            throw new AnalyzerException("算法脚本不存在: " + script + "，请检查 app.analyzer.script 配置");
        }

        String outName = video.getFileName().toString().replaceAll("\\.[^.]+$", "") + ".analysis.json";
        Path output = video.resolveSibling(outName);

        List<String> command = new ArrayList<>(List.of(
                cfg.getPython(), script.toString(),
                "--video", video.toString(),
                "--output", output.toString(),
                "--tasks", tasks == null || tasks.isBlank() ? cfg.getTasks() : tasks,
                "--sample-fps", String.valueOf(cfg.getSampleFps()),
                "--lang", cfg.getLang()));
        if (roi != null && !roi.isBlank()) {
            command.add("--roi");
            command.add(roi);
        }

        Path logFile;
        try {
            logFile = Files.createTempFile("analyzer-", ".log");
        } catch (IOException e) {
            throw new AnalyzerException("无法创建日志临时文件", e);
        }

        try {
            log.info("执行算法: {}", String.join(" ", command));
            Process process = new ProcessBuilder(command)
                    .directory(script.getParent().getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean finished = process.waitFor(cfg.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new AnalyzerException("算法执行超时（>" + cfg.getTimeoutSeconds() + " 秒）");
            }
            String processLog = Files.readString(logFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new AnalyzerException("算法执行失败，退出码 " + process.exitValue() + "\n" + tail(processLog));
            }
            if (!Files.exists(output)) {
                throw new AnalyzerException("算法未产出结果文件: " + output + "\n" + tail(processLog));
            }
            return objectMapper.readTree(Files.readString(output, StandardCharsets.UTF_8));
        } catch (AnalyzerException e) {
            throw e;
        } catch (Exception e) {
            throw new AnalyzerException("调用算法失败: " + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static String tail(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 2000 ? trimmed.substring(trimmed.length() - 2000) : trimmed;
    }
}
