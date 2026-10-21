# focus_analyzer —— 小学生作业专注度分析器（Python）

`ezviz-focus-analysis` 的 Python 音视频分析算法包。Java 后端把从萤石摄像头下载并
按时间归档的 MP4 路径传给本分析器，本分析器输出结构化 JSON，Java 侧再把 JSON 落库
并生成报告。

分析场景：摄像头**俯视 / 斜俯视角**拍摄小学生在家做作业的过程。分析内容：

| 任务 | 输出 | 说明 |
| --- | --- | --- |
| `focus` | `overall` / `segments` / `focusSegments` / `timeline` | 专注度打分与状态机 |
| `distraction` | `events(DISTRACTION)` | 走神（注意力涣散） |
| `hands` | `events(HAND_PLAYING)` | 玩手 / 小动作 |
| `phone` | `events(PHONE_USE)` | 玩手机 |
| `audio` | `audio` | 音频转文本（ASR）+ VAD |

---

## 1. 安装与依赖

### 基础依赖（必需，跑通 mock 与降级模式即可）

```bash
cd analyzer
pip install -r requirements.txt      # opencv-python-headless + numpy
```

### 可选重型依赖（按需安装，代码全部惰性导入）

```bash
pip install mediapipe         # 人脸/手部关键点（FaceMesh + Hands）
pip install ultralytics torch  # YOLOv8 目标检测/人体关键点（手机检测）
pip install faster-whisper     # 语音识别（首选）
pip install openai-whisper     # 语音识别（回退）
pip install vosk              # 语音识别（低资源回退）
```

> **关键设计**：以上重型依赖**全部惰性导入**。任一个缺失都不会让程序崩溃，
> 会自动降级到 OpenCV + 几何规则的兜底实现，并在输出 JSON 的
> `algorithms[].degraded` 标记为 `true`，同时在 `warnings` 中给出说明。
> 因此本包在**只有 `opencv-python-headless` + `numpy`** 的环境下也能跑通并产出结构完整的 JSON。

模型权重放置方式见 [models/README.md](models/README.md)。

---

## 2. 命令行接口

```bash
python analyze.py --video <视频路径> --output <输出json路径> \
  [--tasks focus,distraction,hands,phone,audio] \
  [--sample-fps 1] [--roi 0.2,0.1,0.6,0.8] \
  [--config <config.json>] [--mock] [--lang zh]
```

| 参数 | 说明 |
| --- | --- |
| `--video` | 必填，输入 MP4 路径 |
| `--output` | 必填，输出 JSON 路径（UTF-8，`ensure_ascii=False`） |
| `--tasks` | 可选，默认 `focus,distraction,hands,phone,audio` |
| `--sample-fps` | 抽帧频率，默认 `1` 帧/秒 |
| `--roi` | 书桌区域 ROI，归一化 `x,y,w,h` |
| `--config` | 配置文件（JSON），见 `config.example.json` |
| `--mock` | mock 模式，不依赖任何模型权重 |
| `--lang` | ASR 语言，默认 `zh` |

**退出码**：`0` 成功；`2` 输入参数 / 文件错误；`3` 运行异常（此时也会输出带 `error` 字段的 JSON）。

**输出约定**：日志打到 **stderr**，**stdout 只输出最终 JSON 的路径**，便于 Java 解析。

示例：

```bash
# mock 联调
python analyze.py --video /tmp/testsrc.mp4 --output /tmp/out.json --mock

# 真实分析（无重型依赖时自动降级）
python analyze.py --video /tmp/testsrc.mp4 --output /tmp/out.json --tasks focus,audio

# 带配置
python analyze.py --video /tmp/testsrc.mp4 --output /tmp/out.json \
  --config config.example.json --sample-fps 2 --roi 0.2,0.1,0.6,0.8
```

---

## 3. 算法能力与来源

| 能力 | 实现 | 来源项目 | 链接 |
| --- | --- | --- | --- |
| 头姿 / 视线（专注度） | OpenCV `solvePnP` + 通用 3D 人脸模型 | `yinguobing/head-pose-estimation` | https://github.com/yinguobing/head-pose-estimation |
| 人脸关键点 | MediaPipe FaceMesh | `google-ai-edge/mediapipe` | https://github.com/google-ai-edge/mediapipe |
| 人体关键点 | YOLOv8-pose（COCO-17） | `ultralytics/ultralytics` | https://github.com/ultralytics/ultralytics |
| 走神 / 课堂行为 | 时间序列几何规则 | `classroom-behavior-recognition` 类项目 | https://github.com/search?q=classroom+behavior+recognition |
| 玩手 / 小动作 | MediaPipe Hands（wrist/elbow）+ 桌面 ROI | `google-ai-edge/mediapipe` | https://github.com/google-ai-edge/mediapipe |
| 玩手机 | YOLOv8 COCO `cell phone` 类 + 手部重叠 | `FPI-Det`（Face–Phone Interaction 论文） | https://github.com/ultralytics/ultralytics |
| 音频转文本 | faster-whisper → openai-whisper → vosk → ffmpeg 静音切分 | `SYSTRAN/faster-whisper` | https://github.com/SYSTRAN/faster-whisper |
| VAD / 静音 | ffmpeg `silencedetect`（参考 `py-webrtcvad` / `silero-vad` 思路） | `wiseman/py-webrtcvad` | https://github.com/wiseman/py-webrtcvad |
| 学习时间段切分 | 连续 `FOCUSED` 片段合并 + 分块聚合 | 本包 `segmenter.py` | — |

### 降级策略

| 缺失依赖 | 降级方案 | `degraded` |
| --- | --- | --- |
| `mediapipe` | OpenCV Haar 级联 + 几何规则（头姿）/ 皮肤色 + ROI（手部） | `true` |
| `ultralytics` | OpenCV `cv2.dnn` 加载 `yolov8n.onnx`；无模型时启发式规则 | `true` |
| `faster-whisper` / `openai-whisper` / `vosk` | ffmpeg `silencedetect` 仅做 VAD，无文本 | `true` |
| 全部缺失 + `--mock` | 合成样例结果 | `false`（mock 数据） |

---

## 4. 输出 JSON 契约（Java 侧依赖）

```json
{
  "version": "1.0",
  "video": {
    "path": "...", "durationSec": 1234.5, "fps": 25.0,
    "width": 1920, "height": 1080, "sampledFrames": 1234
  },
  "config": { "sampleFps": 1, "roi": [0.2, 0.1, 0.6, 0.8], "lang": "zh" },
  "algorithms": [
    { "name": "head_pose_focus", "ref": "yinguobing/head-pose-estimation",
      "url": "https://github.com/...", "degraded": true }
  ],
  "overall": {
    "focusScore": 76.2, "videoDurationSec": 1234.5, "studyDurationSec": 980.0,
    "distractedSec": 120.0, "absentSec": 134.5, "phoneSec": 45.0,
    "handPlayingSec": 60.0, "headDownSec": 300.0, "focusRatio": 0.794,
    "summary": "整体专注度良好……"
  },
  "segments": [
    { "index": 0, "startSec": 0.0, "endSec": 300.0, "durationSec": 300.0,
      "focusScore": 82.1, "state": "FOCUSED", "focusedSec": 250.0,
      "distractedSec": 30.0, "absentSec": 20.0, "phoneSec": 0.0,
      "handPlayingSec": 10.0, "headDownSec": 60.0, "summary": "..." }
  ],
  "focusSegments": [
    { "startSec": 0.0, "endSec": 300.0, "durationSec": 300.0,
      "focusScore": 82.1, "state": "FOCUSED" }
  ],
  "breaks": [
    { "startSec": 300.0, "endSec": 330.0, "durationSec": 30.0, "reason": "ABSENT" }
  ],
  "events": [
    { "type": "PHONE_USE", "startSec": 310.0, "endSec": 355.0,
      "durationSec": 45.0, "confidence": 0.86, "note": "检测到手机" }
  ],
  "timeline": [
    { "sec": 0, "state": "FOCUSED", "focusScore": 85.0, "headYaw": 3.0,
      "headPitch": -8.0, "handsOnDesk": true, "phone": false }
  ],
  "audio": {
    "engine": "faster-whisper", "degraded": false, "durationSec": 1234.5,
    "speechDurationSec": 300.0, "silenceRatio": 0.75,
    "segments": [ { "startSec": 1.2, "endSec": 4.8, "text": "我们来看这道题……" } ],
    "fullText": "……", "keywords": ["数学", "作业"]
  },
  "warnings": ["未安装 mediapipe，手部检测降级为几何规则"]
}
```

### 契约约束

- `state` 枚举只允许：`FOCUSED` / `DISTRACTED` / `PHONE` / `ABSENT` / `HAND_PLAYING`。
- `events[].type` 枚举只允许：`PHONE_USE` / `DISTRACTION` / `HAND_PLAYING` / `LEAVE_SEAT`。
- 数值字段一律用浮点数（秒保留 1 位小数），**不出现 `null`**；缺失时给 `0` 或空数组。
- 当 `audio` 任务未启用时，`audio` 字段固定为
  `{ "engine": null, "degraded": true, "durationSec": 0.0, "speechDurationSec": 0.0, "silenceRatio": 0.0, "segments": [], "fullText": "", "keywords": [] }`。
- 运行异常时输出中额外包含 `error` 字符串字段，其余结构保持完整。

---

## 5. 目录结构

```
analyzer/
  analyze.py                # CLI 入口
  requirements.txt          # 基础/可选两段依赖
  config.example.json       # 示例配置
  README.md                 # 本文档
  focus_analyzer/
    __init__.py
    video_reader.py         # 抽帧 / 元信息
    head_pose.py            # 头姿 / 视线
    focus.py                # 专注度打分与状态机
    distraction.py          # 走神
    hands.py                # 玩手 / 小动作
    phone.py                # 玩手机
    audio_asr.py            # 音频转文本
    segmenter.py            # 学习时间段 / 间隔切分
    scoring.py              # 打分权重与阈值（可配置）
    report.py               # 组装最终 JSON
    utils.py                # 通用工具
  models/
    README.md               # 模型权重放置位置与下载方式
  tests/
    test_analyze_mock.py    # pytest 跑 mock 模式验证 JSON 契约
```

---

## 6. 测试

```bash
# 生成 10 秒测试视频
ffmpeg -y -f lavfi -i testsrc=duration=10:size=320x240:rate=10 -pix_fmt yuv420p /tmp/testsrc.mp4

# 运行 mock 模式
python analyze.py --video /tmp/testsrc.mp4 --output /tmp/out.json --mock
python -m json.tool /tmp/out.json > /dev/null && echo "JSON OK"

# 运行 pytest
pip install pytest
python -m pytest tests -q
```

---

## 7. 头部姿态 yaw / pitch 约定

- `headYaw`：偏航角，**正值 = 向右转头**，`|yaw| > yawMax` 判为转头（走神）。
- `headPitch`：俯仰角，**正值 = 抬头，负值 = 低头**，`pitch < -headDownPitch` 判为低头（走神）。
- `headDownSec`：`headDown` 为真的采样点累计时长。

> 视线方向：MediaPipe FaceMesh（`refine_landmarks=True`）的虹膜关键点（468/473）相对眼眶中心
> 计算眼球偏移角 `gaze_yaw/gaze_pitch`，与头部角度合成得到 `effective_yaw/effective_pitch`
> 参与状态判定与打分；`timeline` 中的 `headYaw/headPitch` 仍输出原始头部姿态角。
> 若仅使用 OpenCV 几何兜底（无 FaceMesh），则视线偏移不可得，退化为 0。
