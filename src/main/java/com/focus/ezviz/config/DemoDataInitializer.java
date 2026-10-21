package com.focus.ezviz.config;

import com.focus.ezviz.domain.Camera;
import com.focus.ezviz.domain.DownloadRule;
import com.focus.ezviz.domain.EzvizAccount;
import com.focus.ezviz.domain.User;
import com.focus.ezviz.domain.VideoRecord;
import com.focus.ezviz.repository.CameraRepository;
import com.focus.ezviz.repository.DownloadRuleRepository;
import com.focus.ezviz.repository.EzvizAccountRepository;
import com.focus.ezviz.repository.UserRepository;
import com.focus.ezviz.repository.VideoRecordRepository;
import com.focus.ezviz.service.MediaTools;
import com.focus.ezviz.service.VideoArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 初始化默认管理员，并在开启 demo 时写入一套演示数据（含一段本地生成的示例视频），
 * 便于没有萤石账号时直接体验下载归档 / 分析 / 报告页面。
 */
@Component
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final UserRepository userRepository;
    private final EzvizAccountRepository accountRepository;
    private final CameraRepository cameraRepository;
    private final VideoRecordRepository videoRepository;
    private final DownloadRuleRepository ruleRepository;
    private final PasswordEncoder passwordEncoder;
    private final VideoArchiveService archiveService;
    private final AppProperties props;
    private final MediaTools mediaTools;

    public DemoDataInitializer(UserRepository userRepository,
                               EzvizAccountRepository accountRepository,
                               CameraRepository cameraRepository,
                               VideoRecordRepository videoRepository,
                               DownloadRuleRepository ruleRepository,
                               PasswordEncoder passwordEncoder,
                               VideoArchiveService archiveService,
                               AppProperties props,
                               MediaTools mediaTools) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.cameraRepository = cameraRepository;
        this.videoRepository = videoRepository;
        this.ruleRepository = ruleRepository;
        this.passwordEncoder = passwordEncoder;
        this.archiveService = archiveService;
        this.props = props;
        this.mediaTools = mediaTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        archiveService.ensureRoot();
        createAdmin();
        if (!props.getDemo().isEnabled() || cameraRepository.count() > 0) {
            return;
        }
        try {
            createDemoData();
        } catch (Exception e) {
            log.warn("初始化演示数据失败: {}", e.getMessage());
        }
    }

    private void createAdmin() {
        if (userRepository.count() > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setDisplayName("管理员");
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        userRepository.save(admin);
        log.info("已创建默认管理员 admin / admin123，请登录后及时修改密码");
    }

    private void createDemoData() throws Exception {
        EzvizAccount account = new EzvizAccount();
        account.setName("演示萤石账号");
        account.setAppKey("demo-app-key");
        account.setAppSecret("demo-app-secret");
        account.setEnabled(false);
        account.setLastCheckMessage("演示数据：请替换为真实 appKey/appSecret");
        account = accountRepository.save(account);

        Camera camera = new Camera();
        camera.setAccountId(account.getId());
        camera.setDeviceSerial("BG9468329");
        camera.setChannelNo(1);
        camera.setName("书房摄像头（演示样本）");
        camera.setRoi("0.15,0.15,0.7,0.8");
        camera.setRemark("真实设备录像因视频加密无法取流，此处使用等长演示样本");
        camera = cameraRepository.save(camera);

        DownloadRule rule = new DownloadRule();
        rule.setName("演示规则（默认停用）");
        rule.setCameraId(camera.getId());
        rule.setEnabled(false);
        rule.setCron("0 0/30 * * * *");
        rule.setWindowMinutes(30);
        rule.setSegmentMinutes(30);
        rule.setAutoAnalyze(false);
        ruleRepository.save(rule);

        Path demoDir = archiveService.root().resolve("demo");
        Files.createDirectories(demoDir);
        Path demoVideo = demoDir.resolve("demo-lesson.mp4");
        String sampleVideo = props.getDemo().getSampleVideo();
        if (sampleVideo != null && !sampleVideo.isBlank() && Files.exists(Path.of(sampleVideo))) {
            Files.copy(Path.of(sampleVideo), demoVideo, StandardCopyOption.REPLACE_EXISTING);
            log.info("已导入示例视频: {}", sampleVideo);
        } else if (!Files.exists(demoVideo)) {
            generateDemoVideo(demoVideo);
        }
        if (Files.exists(demoVideo)) {
            double duration = mediaTools.probeDurationSeconds(demoVideo);
            if (duration <= 0) {
                duration = 90;
            }
            LocalDateTime start = parseSampleStart();
            LocalDateTime end = start != null ? start.plusSeconds((long) duration) : LocalDateTime.now().withNano(0);
            LocalDateTime begin = start != null ? start : end.minusSeconds((long) duration);
            VideoRecord video = new VideoRecord();
            video.setCameraId(camera.getId());
            video.setCameraName(camera.getName());
            video.setDeviceSerial(camera.getDeviceSerial());
            video.setChannelNo(1);
            video.setBeginTime(begin);
            video.setEndTime(end);
            video.setDurationSec(duration);
            video.setFilePath(demoVideo.toAbsolutePath().toString());
            video.setRelPath(archiveService.relativePath(demoVideo));
            video.setFileSize(Files.size(demoVideo));
            video.setStatus("READY");
            video.setSource("IMPORT");
            video.setMessage("演示样本：设备 BG9468329 指定时段录像因加密无法取流，使用等长替代样本");
            videoRepository.save(video);
            log.info("已生成演示视频: {}", demoVideo);
        }
    }

    /** 解析配置的示例视频开始时间，格式 yyyy-MM-ddTHH:mm:ss，留空或格式错误则返回 null */
    private LocalDateTime parseSampleStart() {
        String value = props.getDemo().getSampleStart();
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception e) {
            log.warn("示例视频开始时间格式不正确: {}", value);
            return null;
        }
    }

    private void generateDemoVideo(Path target) {
        List<String> command = List.of("ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=25:duration=90",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=90",
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", target.toAbsolutePath().toString());
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                log.warn("ffmpeg 生成演示视频失败，跳过演示视频");
                return;
            }
            log.info("演示视频生成完成: {}", target);
        } catch (Exception e) {
            log.warn("ffmpeg 不可用，跳过演示视频: {}", e.getMessage());
        }
    }
}
