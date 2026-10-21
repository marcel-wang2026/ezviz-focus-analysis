# models —— 模型权重放置目录

本目录用于放置可选的重型模型权重。**所有模型都是可选的**：缺失时算法包会自动降级为
OpenCV + 几何规则的实现，并在输出 JSON 的 `algorithms[].degraded` 标记为 `true`，
同时在 `warnings` 中说明。

## 目录约定

```
models/
  yolov8n.pt        # ultralytics YOLOv8 COCO 预训练权重（检测 cell phone / person）
  yolov8n.onnx      # 同上，OpenCV DNN 可加载的 ONNX 版本（无 torch 时使用）
  yunet.onnx        # OpenCV 5.x FaceDetectorYN 人脸检测权重（头姿兜底，替代已被移除的 Haar）
```

模型放置后，`phone.py` 会按以下优先级自动选择后端：

1. `ultralytics`（若已安装且 `yolov8n.pt` 存在）
2. `opencv-python-headless` 的 `cv2.dnn` 读取 `yolov8n.onnx`
3. 无模型时使用桌面 ROI 启发式规则（`degraded=true`）

`head_pose.py` 的兜底探测顺序为：OpenCV 4.x 的 Haar 级联 →
OpenCV 5.x 的 `cv2.FaceDetectorYN` + `yunet.onnx` → 不可用。由于 OpenCV 5.x
已移除 `CascadeClassifier`，若使用 OpenCV 5.x 建议放置 `yunet.onnx`。

## 下载方式

### YOLOv8n（优先推荐）

```bash
pip install ultralytics
# 首次调用会自动下载 yolov8n.pt；或手动下载后放入本目录
wget https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.pt -O models/yolov8n.pt
```

### YOLOv8n ONNX（无 torch 环境）

```bash
pip install ultralytics onnx
yolo export model=yolov8n.pt format=onnx
mv yolov8n.onnx models/
```

### YuNet（OpenCV 5.x 人脸检测，头姿兜底）

```bash
wget https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx -O models/yunet.onnx
```

### MediaPipe（人脸/手部关键点，无需权重文件）

```bash
pip install mediapipe
```

`head_pose.py` / `hands.py` 检测到 `mediapipe` 后会自动启用 FaceMesh / Hands 后端，
无需额外权重文件（首次运行会自动下载内置模型包）。

### faster-whisper（语音识别，自动下载模型）

```bash
pip install faster-whisper
```

模型大小由 `config.example.json` 的 `audio.modelSize`（默认 `small`）控制。

### openai-whisper / vosk（回退）

```bash
pip install openai-whisper      # 自动下载模型
pip install vosk                # 需自行下载中文模型，如 vosk-model-small-cn-0.22
```

## 说明

- 不允许下载模型时，直接使用 `--mock` 或依赖 OpenCV 兜底即可产出结构完整的 JSON。
- 模型权重不要提交到 git（建议在 `.gitignore` 忽略 `models/*.pt`、`models/*.onnx`）。
