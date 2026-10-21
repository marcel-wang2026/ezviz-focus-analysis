package com.focus.ezviz.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 基于 ffmpeg / ffprobe 的媒体工具封装：下载 HLS 流、探测时长、抽取音频。
 */
@Component
public class MediaTools {

    private static final Logger log = LoggerFactory.getLogger(MediaTools.class);

    public boolean isFfmpegAvailable() {
        return run(List.of("ffmpeg", "-version"), 15) != null;
    }

    /**
     * 把 HLS(m3u8) 地址拉取并封装为本地 MP4。
     *
     * @param sourceUrl      HLS 播放地址
     * @param target         目标 mp4 文件
     * @param timeoutSeconds 超时时间
     */
    public void downloadHls(String sourceUrl, Path target, long timeoutSeconds) throws IOException {
        Files.createDirectories(target.getParent());
        List<String> command = new ArrayList<>(List.of(
                "ffmpeg", "-y", "-loglevel", "error",
                "-rw_timeout", String.valueOf(timeoutSeconds * 1_000_000),
                "-user_agent", "Mozilla/5.0 (EZVIZ-Downloader)",
                "-i", sourceUrl,
                "-c", "copy",
                "-movflags", "+faststart",
                target.toAbsolutePath().toString()));
        log.info("开始下载 HLS 录像到 {}", target);
        String output = run(command, timeoutSeconds);
        if (output == null) {
            throw new IOException("ffmpeg 下载 HLS 失败或超时");
        }
        if (!Files.exists(target) || Files.size(target) == 0) {
            throw new IOException("ffmpeg 未生成有效的视频文件");
        }
    }

    /** 用 ffprobe 探测视频时长（秒），失败返回 0 */
    public double probeDurationSeconds(Path video) {
        String output = run(List.of("ffprobe", "-v", "error", "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1", video.toAbsolutePath().toString()), 60);
        if (output == null || output.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(output.trim().split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 抽取音频为 16k 单声道 wav，供 ASR 使用；返回输出文件路径，失败返回 empty */
    public Optional<Path> extractAudio(Path video, Path target) {
        try {
            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            return Optional.empty();
        }
        String output = run(List.of("ffmpeg", "-y", "-loglevel", "error",
                "-i", video.toAbsolutePath().toString(),
                "-vn", "-ac", "1", "-ar", "16000", "-f", "wav", target.toAbsolutePath().toString()), 1800);
        if (output == null || !Files.exists(target)) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private String run(List<String> command, long timeoutSeconds) {
        Path logFile = null;
        try {
            logFile = Files.createTempFile("media-tool-", ".log");
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
            boolean finished = process.waitFor(Math.max(timeoutSeconds, 5), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("命令超时: {}", command);
                return null;
            }
            if (process.exitValue() != 0) {
                String detail = Files.readString(logFile, StandardCharsets.UTF_8);
                log.warn("命令执行失败({}): {} -> {}", process.exitValue(), command, abbreviate(detail));
                return null;
            }
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("命令不可用: {} ({})", command, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) + "..." : trimmed;
    }
}
