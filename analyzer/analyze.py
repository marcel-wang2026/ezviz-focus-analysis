#!/usr/bin/env python3
"""ezviz-focus-analysis Python 音视频分析算法包入口。

用法::

    python analyze.py --video <视频路径> --output <输出json路径> \
        [--tasks focus,distraction,hands,phone,audio] \
        [--sample-fps 1] [--roi 0.2,0.1,0.6,0.8] \
        [--config <config.json>] [--mock] [--lang zh]

退出码::

    0 成功
    2 输入参数 / 文件错误
    3 运行异常（仍会输出带 error 字段的 JSON）

所有重型依赖均为惰性导入，缺失时自动降级，日志输出到 stderr，
stdout 只输出最终 JSON 的路径。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Dict, List, Optional, Sequence

# 兼容 `python analyze.py`（脚本目录）与 `python -m`（包）两种运行方式
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from focus_analyzer import report as report_mod  # noqa: E402
from focus_analyzer.audio_asr import AudioAnalyzer  # noqa: E402
from focus_analyzer.distraction import DistractionDetector  # noqa: E402
from focus_analyzer.focus import FocusStateMachine  # noqa: E402
from focus_analyzer.hands import HandAnalyzer, HandEventBuilder  # noqa: E402
from focus_analyzer.head_pose import HeadPoseEstimator  # noqa: E402
from focus_analyzer.phone import PhoneDetector, PhoneEventBuilder  # noqa: E402
from focus_analyzer.scoring import ScoringConfig, TimelineSample  # noqa: E402
from focus_analyzer.segmenter import (  # noqa: E402
    aggregate,
    build_breaks,
    build_focus_segments,
    build_leave_events,
    build_segments,
    overall_summary,
)
from focus_analyzer.utils import get_logger, parse_roi, try_import  # noqa: E402
from focus_analyzer.video_reader import VideoMeta, VideoReader  # noqa: E402

logger = get_logger()

VALID_TASKS = ["focus", "distraction", "hands", "phone", "audio"]
EXIT_OK = 0
EXIT_INPUT = 2
EXIT_RUNTIME = 3


# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="analyze.py",
        description="萤石摄像头小学生作业专注度分析器（俯视/斜俯视角）",
    )
    parser.add_argument("--video", required=True, help="输入视频路径 (MP4)")
    parser.add_argument("--output", required=True, help="输出 JSON 路径")
    parser.add_argument(
        "--tasks",
        default=",".join(VALID_TASKS),
        help="分析任务，逗号分隔，可选: " + ",".join(VALID_TASKS),
    )
    parser.add_argument("--sample-fps", type=float, default=None, help="抽帧频率（默认 1）")
    parser.add_argument(
        "--roi", default=None, help="书桌区域 ROI，归一化 x,y,w,h，例如 0.2,0.1,0.6,0.8"
    )
    parser.add_argument("--config", default=None, help="配置文件路径 (JSON)")
    parser.add_argument("--mock", action="store_true", help="mock 模式，不依赖任何模型")
    parser.add_argument("--lang", default=None, help="ASR 语言，默认 zh")
    return parser


def parse_tasks(raw: str) -> List[str]:
    tasks = [t.strip() for t in (raw or "").split(",") if t.strip()]
    if not tasks:
        raise ValueError("--tasks 不能为空")
    invalid = [t for t in tasks if t not in VALID_TASKS]
    if invalid:
        raise ValueError("未知任务: " + ", ".join(invalid))
    # 去重并保序
    seen = set()
    ordered = []
    for t in tasks:
        if t not in seen:
            seen.add(t)
            ordered.append(t)
    return ordered


def load_config(path: Optional[str]) -> dict:
    if not path:
        return {}
    if not os.path.isfile(path):
        raise FileNotFoundError(f"配置文件不存在: {path}")
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise ValueError("配置文件必须是 JSON 对象")
    return data


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------
def run(args: argparse.Namespace) -> dict:
    tasks = parse_tasks(args.tasks)
    config_data = load_config(args.config)

    cfg = ScoringConfig.from_dict(config_data)
    if args.sample_fps is not None:
        cfg.sample_fps = max(0.01, float(args.sample_fps))
    if args.roi is not None:
        cfg.roi = parse_roi(args.roi)
    if args.lang is not None:
        cfg.lang = args.lang

    if args.mock:
        return run_mock(args.video, cfg, tasks)

    if not os.path.isfile(args.video):
        raise FileNotFoundError(f"视频文件不存在: {args.video}")
    return run_real(args.video, cfg, tasks)


def _needs_frames(tasks: Sequence[str]) -> bool:
    return any(t in tasks for t in ("focus", "distraction", "hands", "phone"))


def _collect_dependency_warnings(tasks: Sequence[str]) -> List[str]:
    """检测重型依赖缺失情况并生成 warnings。"""
    warnings: List[str] = []
    if _needs_frames(tasks):
        if try_import("mediapipe") is None:
            warnings.append("未安装 mediapipe，头部姿态/手部检测降级为 OpenCV 几何规则")
        if "phone" in tasks and try_import("ultralytics") is None:
            warnings.append("未安装 ultralytics，手机检测降级为启发式规则，召回率有限")
    if "audio" in tasks:
        has_faster = try_import("faster_whisper") is not None
        has_openai = try_import("whisper") is not None
        has_vosk = try_import("vosk") is not None
        if not has_faster:
            warnings.append("未安装 faster-whisper，尝试回退 openai-whisper / vosk")
        if not (has_faster or has_openai or has_vosk):
            warnings.append("未安装 faster-whisper/openai-whisper/vosk，音频仅做静音切分，无文本")
    return warnings


def run_real(video_path: str, cfg: ScoringConfig, tasks: Sequence[str]) -> dict:
    warnings = _collect_dependency_warnings(tasks)
    degraded: Dict[str, bool] = {}

    reader = VideoReader(video_path, sample_fps=cfg.sample_fps, roi=cfg.roi)
    meta = reader.meta
    samples: List[TimelineSample] = []

    if _needs_frames(tasks):
        head_pose = HeadPoseEstimator(cfg.roi)
        hand_analyzer = HandAnalyzer(cfg)
        phone_detector = PhoneDetector(cfg) if "phone" in tasks else None
        focus_sm = FocusStateMachine(cfg)

        degraded["focus"] = head_pose.degraded
        degraded["distraction"] = head_pose.degraded
        degraded["hands"] = hand_analyzer.degraded
        degraded["phone"] = phone_detector.degraded if phone_detector else True
        if head_pose.method == "none":
            warnings.append(
                "未检测到可用的人脸检测后端（OpenCV 5.x 需放置 models/yunet.onnx，"
                "或安装 mediapipe），头姿特征不可用，专注度仅基于在座/手部规则"
            )

        try:
            for timestamp, frame in reader.iter_frames():
                pose = head_pose.estimate(frame)
                hands_on_desk, hand_playing, person_present, _skin = (
                    hand_analyzer.analyze(frame, pose.detected)
                    if "hands" in tasks
                    else (False, False, pose.detected, 0.0)
                )
                if phone_detector is not None:
                    phone, _conf = phone_detector.detect(frame)
                else:
                    phone = False
                present = pose.detected or person_present
                state = focus_sm.state(pose, present, hands_on_desk, hand_playing, phone)
                samples.append(
                    TimelineSample(
                        sec=timestamp,
                        state=state,
                        focus_score=focus_sm.score(state, pose),
                        head_yaw=pose.yaw,
                        head_pitch=pose.pitch,
                        hands_on_desk=hands_on_desk,
                        phone=phone,
                        person_present=present,
                        head_down=focus_sm.head_down(pose),
                    )
                )
        finally:
            head_pose.close()
            hand_analyzer.close()

        if not samples:
            warnings.append("未能从视频中抽帧成功，专注度结果可能不可用")
        elif meta.duration_sec <= 0:
            meta.duration_sec = samples[-1].sec + 1.0 / max(0.01, cfg.sample_fps)

    overall = aggregate(samples, cfg) if samples else aggregate([], cfg)
    segments = build_segments(samples, meta.duration_sec, cfg) if samples else []
    focus_segments = build_focus_segments(samples, cfg) if samples else []
    breaks = build_breaks(samples, cfg) if samples else []

    events: List[dict] = []
    if samples:
        if "phone" in tasks:
            events.extend(PhoneEventBuilder(cfg).build(samples))
        if "distraction" in tasks:
            events.extend(DistractionDetector(cfg).detect(samples))
        if "hands" in tasks:
            events.extend(HandEventBuilder(cfg).build(samples))
        if "focus" in tasks:
            events.extend(build_leave_events(samples, cfg))

    audio = None
    if "audio" in tasks:
        analyzer = AudioAnalyzer(cfg)
        audio = analyzer.analyze(video_path, cfg.lang)
        degraded["audio"] = bool(audio.get("degraded", True))
    else:
        audio = report_mod.empty_audio()
        degraded["audio"] = True

    algorithms = report_mod.build_algorithms(tasks, degraded)
    summary = overall_summary(overall, cfg)

    return report_mod.build_report(
        meta=meta,
        sampled_frames=len(samples),
        cfg=cfg,
        overall=overall,
        segments=segments,
        focus_segments=focus_segments,
        breaks=breaks,
        events=events,
        samples=samples,
        audio=audio,
        algorithms=algorithms,
        warnings=warnings,
        summary=summary,
    )


def run_mock(video_path: str, cfg: ScoringConfig, tasks: Sequence[str]) -> dict:
    """mock 模式：不依赖任何模型权重，生成结构完整的样例结果。"""
    warnings: List[str] = []

    if os.path.isfile(video_path):
        try:
            reader = VideoReader(video_path, sample_fps=cfg.sample_fps, roi=cfg.roi)
            meta = reader.meta
        except Exception:  # noqa: BLE001
            meta = VideoMeta(video_path, duration_sec=300.0, fps=25.0, width=1280, height=720)
            warnings.append("mock 模式无法读取视频元信息，使用默认时长 300 秒")
    else:
        meta = VideoMeta(video_path, duration_sec=300.0, fps=25.0, width=1280, height=720)
        warnings.append("mock 模式未提供有效视频，使用默认元信息")

    if meta.duration_sec <= 0:
        meta.duration_sec = 300.0

    if _needs_frames(tasks):
        samples = report_mod.make_mock_samples(meta.duration_sec, cfg)
        events = report_mod.make_mock_events(samples, cfg)
        overall = aggregate(samples, cfg, 0.0, meta.duration_sec)
        segments = build_segments(samples, meta.duration_sec, cfg)
        focus_segments = build_focus_segments(samples, cfg)
        breaks = build_breaks(samples, cfg)
    else:
        samples = []
        events = []
        overall = aggregate([], cfg)
        segments = []
        focus_segments = []
        breaks = []

    audio = report_mod.make_mock_audio(meta.duration_sec, cfg, "audio" in tasks)

    degraded = {task: False for task in tasks}
    degraded["audio"] = "audio" not in tasks
    algorithms = report_mod.build_algorithms(tasks, degraded)

    warnings.append("mock 模式：未使用任何真实模型，仅用于 Java 侧联调")
    if not _needs_frames(tasks):
        warnings.append("未启用 focus/distraction/hands/phone 任务，未生成时间线")
    summary = overall_summary(overall, cfg)

    return report_mod.build_report(
        meta=meta,
        sampled_frames=len(samples),
        cfg=cfg,
        overall=overall,
        segments=segments,
        focus_segments=focus_segments,
        breaks=breaks,
        events=events,
        samples=samples,
        audio=audio,
        algorithms=algorithms,
        warnings=warnings,
        summary=summary,
    )


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
def write_json(path: str, data: dict) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        data = run(args)
    except (ValueError, FileNotFoundError) as exc:
        # 输入参数 / 文件错误
        logger.error("输入错误: %s", exc)
        data = report_mod.error_report(str(exc), getattr(args, "video", ""))
        try:
            write_json(args.output, data)
        except Exception:  # noqa: BLE001
            pass
        return EXIT_INPUT
    except Exception as exc:  # noqa: BLE001
        # 运行异常 —— 尽量输出带 error 字段的 JSON
        logger.exception("运行异常: %s", exc)
        data = report_mod.error_report(str(exc), getattr(args, "video", ""))
        try:
            write_json(args.output, data)
        except Exception:  # noqa: BLE001
            logger.error("输出 JSON 失败")
        return EXIT_RUNTIME

    write_json(args.output, data)
    print(os.path.abspath(args.output))
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
