# 萤石录像归档与学习专注度分析平台

从萤石（EZVIZ）开放平台定时/手动下载指定摄像头的录像，按时间归档为本地 MP4，
并调用 Python 算法包对视频做专注度、走神、玩手、玩手机与语音转写分析，
最终在 Web 页面中查看每个学习时间段的专注度报告。

## 1. 功能一览

| 模块 | 说明 |
| --- | --- |
| 萤石账号 | 页面配置 appKey / appSecret，保存后自动换取 accessToken 并校验 |
| 摄像头管理 | 从萤石账号同步设备列表，选择需要下载的通道，可配置 ROI 区域 |
| 下载规则 | 指定摄像头 + cron 周期 + 回溯时间窗 + 分段时长，可开关自动分析 |
| 视频归档 | 按 `摄像头/年/月/日/HHmm-HHmm.mp4` 落盘，HLS 拉流转封装为 MP4 |
| 手动下载 | 指定摄像头与任意时间段立即下载并归档 |
| 分析中心 | 手动或定时触发 focus / distraction / hands / phone / audio 分析 |
| 分析任务 | 任务状态、进度、失败重试；页面 3 秒自动刷新 |
| 分析报告 | 视频时长、学习时长、每段学习间隔、每段专注度总结、异常事件、语音文本 |
| 开放接口 | `/api/v1/**` 供多个 Python 算法/外部系统读取视频、提交任务、获取报告 |
| 登录鉴权 | Spring Security 表单登录，所有页面与接口均需登录 |

## 2. 技术栈

- Java 21、Spring Boot 3.4（Web / Data JPA / Security / Thymeleaf）
- H2 文件数据库（默认），可切换 MySQL
- ffmpeg / ffprobe：HLS 拉流、转封装、时长探测
- Python 算法包：OpenCV（必需），MediaPipe / Ultralytics / Faster-Whisper 可选

## 3. 快速开始

### 3.1 环境依赖

```bash
java -version        # 21+
mvn -version         # 3.9+
ffmpeg -version      # 必须，用于拉流与转封装
python3 --version    # 3.10+
python3 -m pip install opencv-python-headless numpy   # 算法包最小依赖
```

### 3.2 启动

```bash
cd ezviz-focus-analysis
mvn spring-boot:run
```

打开 http://localhost:8080 ，默认管理员：

```
用户名：admin
密码：admin123
```

首次启动会自动创建 `data/` 目录、数据库，并在演示模式下生成一个演示摄像头、
一条停用的下载规则和一段 90 秒示例视频，可直接在「分析中心」体验完整分析流程。
生产环境请设置 `DEMO_ENABLED=false` 并修改默认密码。

### 3.3 打包运行

```bash
mvn clean package -DskipTests
java -jar target/ezviz-focus-analysis-1.0.0.jar
```

## 4. 使用流程

1. **配置萤石账号**：进入「萤石账号」，填写开放平台应用的 appKey / appSecret。
   账号需在萤石开放平台开通「设备列表」「录像查询/回放」「HLS 取流」相关权限。
2. **同步摄像头**：「摄像头」页面点击「同步设备」，勾选需要下载的通道并保存；
   可根据画面调整 ROI（归一化的 `x,y,w,h`，用于算法聚焦书桌区域）。
3. **配置下载规则**：「下载规则」中选择摄像头，设置 cron（Spring 6 位表达式，
   如 `0 0/30 * * * *` 表示每 30 分钟）、回溯窗口与分段时长。
4. **触发下载**：规则到点自动执行；也可在摄像头详情或「分析中心」按时间段手动触发。
5. **触发分析**：在「分析中心」选择视频与算法任务，或开启规则的「自动分析」。
6. **查看报告**：「分析任务」查看进度，「分析报告」查看汇总与逐段明细。

归档目录示例：

```
data/videos/
  演示-书房摄像头/
    2026/
      10/
        20/
          2122-2124.mp4
          2122-2124.analysis.json
```

## 5. 配置项

全部配置位于 `src/main/resources/application.yml`，均可用环境变量覆盖。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 服务端口 |
| `DB_URL` | `jdbc:h2:file:./data/focusdb;...` | 数据库连接 |
| `DB_USERNAME` / `DB_PASSWORD` | `sa` / 空 | 数据库账号 |
| `DB_DRIVER` | `org.h2.Driver` | 切换 MySQL 时改为 `com.mysql.cj.jdbc.Driver` |
| `VIDEO_ROOT` | `./data/videos` | 视频归档根目录 |
| `RETENTION_DAYS` | `30` | 归档保留天数（0 表示不清理） |
| `EZVIZ_BASE_URL` | `https://open.ys7.com` | 萤石开放平台地址 |
| `ANALYZER_MODE` | `cli` | `cli` 本机子进程 / `http` 独立算法服务 |
| `ANALYZER_PYTHON` | `python3` | Python 解释器 |
| `ANALYZER_SCRIPT` | `./analyzer/analyze.py` | 算法入口脚本 |
| `ANALYZER_HTTP_URL` | `http://127.0.0.1:9001/analyze` | HTTP 模式下的算法服务地址 |
| `ANALYZER_TIMEOUT` | `3600` | 单次分析超时（秒） |
| `ANALYZER_SAMPLE_FPS` | `1` | 抽帧频率 |
| `SCHEDULER_ENABLED` | `true` | 是否启用规则调度 |
| `DEMO_ENABLED` | `true` | 是否写入演示数据 |

切换 MySQL：

```yaml
spring:
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/focus?useUnicode=true&characterEncoding=utf8
    username: focus
    password: ******
    driver-class-name: com.mysql.cj.jdbc.Driver
```

并在 `pom.xml` 中放开 `mysql-connector-j` 依赖注释。

## 6. Python 算法包

位置：`analyzer/`，详见 [analyzer/README.md](analyzer/README.md)。

- 任务：`focus`（头姿专注度）、`distraction`（时序走神）、`hands`（手部/小动作）、
  `phone`（手机检测）、`audio`（语音转写）。
- 开源参考：head-pose-estimation、MediaPipe FaceMesh/Hands、Ultralytics YOLOv8、
  Faster-Whisper。
- **惰性加载**：重型依赖缺失时自动降级为 OpenCV/几何规则，仍输出结构完整的 JSON，
  并在 `algorithms[].degraded` 与 `warnings` 中说明，不会导致分析任务失败。
- 模型权重放置见 `analyzer/models/README.md`（YuNet 人脸模型可显著提升头姿准确率）。

单独调试：

```bash
python3 analyzer/analyze.py --video data/videos/demo/demo-lesson.mp4 \
  --output /tmp/out.json --tasks focus,distraction,hands,phone,audio
python3 analyzer/analyze.py --video demo.mp4 --output /tmp/out.json --mock   # 无模型联调
```

## 7. 报告内容

每份报告持久化以下字段（页面与 `/api/v1/reports/{id}` 均可获取）：

- 视频总时长、学习时长、学习时长占比
- 综合专注度（0-100）
- 走神 / 玩手机 / 玩手 / 低头 / 离开座位累计时长
- 学习时间段列表：起止时间、时长、状态、专注度、各状态耗时、逐段总结
- 学习间隔列表：起止时间、时长、原因
- 异常事件列表：类型（玩手机/走神/玩手/离开座位）、起止时间、置信度
- 音频转写全文、使用的算法及是否降级
- 完整原始算法 JSON（可下载）

学习时间段规则：短暂离开（≤2 分钟）不打断学习；超过 30 分钟的连续学习会切片，
便于逐段给出专注度总结。

## 8. 开放接口

会话 Cookie 鉴权（与 Web 登录一致）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/health` | 健康检查 |
| GET | `/api/v1/cameras` | 摄像头列表 |
| GET | `/api/v1/videos?cameraId=&status=&limit=` | 视频列表 |
| GET | `/api/v1/videos/{id}` | 视频详情 |
| POST | `/api/v1/videos/{id}/analyze?tasks=` | 对已有视频发起分析 |
| GET | `/api/v1/tasks?videoId=` | 任务列表 |
| GET | `/api/v1/tasks/{id}` | 任务详情 |
| GET | `/api/v1/reports?cameraId=&limit=` | 报告列表 |
| GET | `/api/v1/reports/{id}` | 报告 JSON |
| GET | `/api/v1/reports/{id}/raw` | 原始算法 JSON |
| POST | `/api/v1/analysis/point` | 指定摄像头与时间段下载并分析 |

`POST /api/v1/analysis/point` 示例：

```bash
curl -b cookie.txt -H 'Content-Type: application/json' \
  -d '{"cameraId":1,"from":"2026-10-20 18:00:00","to":"2026-10-20 19:00:00",
       "tasks":"focus,distraction,hands,phone,audio","autoAnalyze":true}' \
  http://localhost:8080/api/v1/analysis/point
```

## 9. 生产部署建议

- 使用 MySQL 或 PostgreSQL，定期备份 `data/videos` 与数据库。
- 用 systemd / Docker 托管，配置 `SCHEDULER_ENABLED=true`、`DEMO_ENABLED=false`。
- 将 `ANALYZER_MODE` 设为 `http`，把算法部署为独立 GPU 服务，避免占用 Web 进程。
- 前置 Nginx 做 HTTPS 与访问控制；`/api/**` 建议增加独立 API Token 或网关鉴权。
- 萤石 HLS 地址有时效性，下载任务应尽快执行；单个时间段建议不超过 48 小时。

## 10. 已知限制

- 算法默认使用 CPU 抽帧（1 fps），长视频分析耗时较长，可调低 `sampleFps` 或使用 GPU。
- 未放置人脸模型且未安装 MediaPipe 时，头姿不可用，专注度仅依据在座/手部规则，
  结果偏保守（演示视频会全部判为离开座位）。
- 萤石不同应用权限返回的录像接口字段存在差异，客户端已做兼容与整段 HLS 降级，
  但若账号未开通回放权限，仍需先在开放平台申请。
