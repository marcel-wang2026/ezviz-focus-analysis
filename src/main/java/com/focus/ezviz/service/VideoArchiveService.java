package com.focus.ezviz.service;

import com.focus.ezviz.config.AppProperties;
import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.VideoRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

/**
 * 视频归档：按 摄像头 / 年 / 月 / 日 / 时间段 分层保存。
 * <pre>
 * data/videos/
 *   书房摄像头/
 *     2026/10/20/20261020_090000-20261020_093000.mp4
 * </pre>
 */
@Service
public class VideoArchiveService {

    private static final Logger log = LoggerFactory.getLogger(VideoArchiveService.class);
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final AppProperties props;

    public VideoArchiveService(AppProperties props) {
        this.props = props;
    }

    public Path root() {
        return Paths.get(props.getStorage().getRoot()).toAbsolutePath().normalize();
    }

    public void ensureRoot() {
        try {
            Files.createDirectories(root());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建视频归档目录: " + root(), e);
        }
    }

    /** 生成归档目标路径 */
    public Path targetPath(Camera camera, LocalDateTime begin, LocalDateTime end) {
        String cameraDir = sanitize(camera.getName().isBlank() ? camera.getDeviceSerial() : camera.getName())
                + "-" + camera.getId();
        Path dir = root()
                .resolve(cameraDir)
                .resolve(DATE_DIR.format(begin));
        String fileName = FILE_TIME.format(begin) + "-" + FILE_TIME.format(end) + ".mp4";
        return dir.resolve(fileName).normalize();
    }

    /** 生成分析 JSON 的落盘路径，与视频同目录 */
    public Path reportJsonPath(VideoRecord video) {
        Path videoPath = Paths.get(video.getFilePath());
        String name = videoPath.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return videoPath.resolveSibling(base + ".analysis.json");
    }

    public String relativePath(Path file) {
        return root().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Path resolveRelative(String relPath) {
        Path resolved = root().resolve(relPath).normalize();
        if (!resolved.startsWith(root())) {
            throw new IllegalArgumentException("非法路径: " + relPath);
        }
        return resolved;
    }

    public long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    public void deleteVideo(VideoRecord video) {
        if (video.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(video.getFilePath()));
            } catch (IOException e) {
                log.warn("删除视频文件失败: {}", video.getFilePath(), e);
            }
        }
        try {
            Files.deleteIfExists(reportJsonPath(video));
        } catch (IOException ignored) {
            // ignore
        }
    }

    /** 清理空目录（最多向上 4 层） */
    public void cleanupEmptyDirectories(Path start) {
        Path current = start;
        for (int i = 0; i < 4 && current != null && current.startsWith(root()) && !current.equals(root()); i++) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) {
                    return;
                }
            } catch (IOException e) {
                return;
            }
            try {
                Files.deleteIfExists(current);
            } catch (IOException e) {
                return;
            }
            current = current.getParent();
        }
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
