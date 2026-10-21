"""mock 模式端到端测试：验证输出 JSON 契约与 CLI 退出码。

无需任何重型依赖（mediapipe / ultralytics / faster-whisper 均可缺失），
仅依赖 opencv-python-headless + numpy + pytest。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys

import pytest

ANALYZER_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ANALYZE_PY = os.path.join(ANALYZER_DIR, "analyze.py")

STATE_LEVELS = {"FOCUSED", "DISTRACTED", "PHONE", "ABSENT", "HAND_PLAYING"}
EVENT_TYPES = {"PHONE_USE", "DISTRACTION", "HAND_PLAYING", "LEAVE_SEAT"}


# ---------------------------------------------------------------------------
# 工具：生成测试视频 / 运行 CLI
# ---------------------------------------------------------------------------
def _make_test_video(path: str, duration: int = 10) -> bool:
    """用 ffmpeg 生成 testsrc 测试视频，失败返回 False。"""
    cmd = [
        "ffmpeg", "-y", "-v", "error",
        "-f", "lavfi", "-i", f"testsrc=duration={duration}:size=320x240:rate=10",
        "-pix_fmt", "yuv420p", path,
    ]
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120, check=False)
        return proc.returncode == 0 and os.path.isfile(path)
    except Exception:  # noqa: BLE001
        return False


def _run_cli(args: list) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, ANALYZE_PY] + args,
        capture_output=True, text=True, timeout=300, check=False,
    )


@pytest.fixture(scope="session")
def test_video(tmp_path_factory) -> str:
    video = str(tmp_path_factory.mktemp("video") / "testsrc.mp4")
    if not _make_test_video(video):
        pytest.skip("环境缺少 ffmpeg，跳过依赖视频的测试")
    return video


def _assert_schema(data: dict) -> None:
    """校验输出 JSON 的完整契约。"""
    assert data["version"] == "1.0"

    video = data["video"]
    for key in ("path", "durationSec", "fps", "width", "height", "sampledFrames"):
        assert key in video
    assert isinstance(video["durationSec"], float)
    assert isinstance(video["fps"], float)
    assert isinstance(video["width"], int)
    assert isinstance(video["height"], int)
    assert isinstance(video["sampledFrames"], int)

    config = data["config"]
    assert set(config) == {"sampleFps", "roi", "lang"}
    assert isinstance(config["roi"], list)
    assert isinstance(config["lang"], str)

    assert isinstance(data["algorithms"], list)
    for algo in data["algorithms"]:
        assert set(algo) == {"name", "ref", "url", "degraded"}
        assert isinstance(algo["degraded"], bool)
        assert isinstance(algo["url"], str) and algo["url"]

    overall = data["overall"]
    for key in (
        "focusScore", "videoDurationSec", "studyDurationSec", "distractedSec",
        "absentSec", "phoneSec", "handPlayingSec", "headDownSec", "focusRatio",
        "summary",
    ):
        assert key in overall
        if key != "summary":
            assert isinstance(overall[key], (int, float))
            assert overall[key] is not None
    assert isinstance(overall["summary"], str) and overall["summary"]

    assert isinstance(data["segments"], list)
    for seg in data["segments"]:
        assert set(seg) == {
            "index", "startSec", "endSec", "durationSec", "focusScore", "state",
            "focusedSec", "distractedSec", "absentSec", "phoneSec",
            "handPlayingSec", "headDownSec", "summary",
        }
        assert seg["state"] in STATE_LEVELS

    assert isinstance(data["focusSegments"], list)
    for seg in data["focusSegments"]:
        assert set(seg) == {
            "startSec", "endSec", "durationSec", "focusScore", "state",
        }
        assert seg["state"] in STATE_LEVELS

    assert isinstance(data["breaks"], list)
    for br in data["breaks"]:
        assert set(br) == {"startSec", "endSec", "durationSec", "reason"}
        assert br["reason"] == "ABSENT"

    assert isinstance(data["events"], list)
    for event in data["events"]:
        assert set(event) == {
            "type", "startSec", "endSec", "durationSec", "confidence", "note",
        }
        assert event["type"] in EVENT_TYPES

    assert isinstance(data["timeline"], list)
    for item in data["timeline"]:
        assert set(item) == {
            "sec", "state", "focusScore", "headYaw", "headPitch",
            "handsOnDesk", "phone",
        }
        assert item["state"] in STATE_LEVELS
        assert isinstance(item["handsOnDesk"], bool)
        assert isinstance(item["phone"], bool)

    audio = data["audio"]
    assert set(audio) == {
        "engine", "degraded", "durationSec", "speechDurationSec",
        "silenceRatio", "segments", "fullText", "keywords",
    }
    assert isinstance(audio["degraded"], bool)
    assert isinstance(audio["segments"], list)
    assert isinstance(audio["fullText"], str)
    assert isinstance(audio["keywords"], list)

    assert isinstance(data["warnings"], list)


# ---------------------------------------------------------------------------
# 测试用例
# ---------------------------------------------------------------------------
def test_mock_all_tasks(test_video, tmp_path):
    output = str(tmp_path / "out.json")
    proc = _run_cli(["--video", test_video, "--output", output, "--mock"])
    assert proc.returncode == 0, proc.stderr
    # stdout 只输出最终 JSON 路径
    assert os.path.abspath(output) == proc.stdout.strip()

    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    _assert_schema(data)
    assert len(data["timeline"]) > 0
    assert len(data["algorithms"]) == 5
    assert data["audio"]["degraded"] is False  # mock 下音频可用


def test_mock_audio_task(test_video, tmp_path):
    output = str(tmp_path / "audio.json")
    proc = _run_cli(
        ["--video", test_video, "--output", output, "--mock", "--tasks", "audio"]
    )
    assert proc.returncode == 0, proc.stderr

    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    _assert_schema(data)
    assert len(data["timeline"]) == 0  # audio 任务不抽帧
    assert data["audio"]["engine"] == "mock"
    assert data["audio"]["fullText"]
    assert len(data["algorithms"]) == 1
    assert data["algorithms"][0]["name"] == "speech_to_text"


def test_mock_focus_only(test_video, tmp_path):
    output = str(tmp_path / "focus.json")
    proc = _run_cli(
        ["--video", test_video, "--output", output, "--mock",
         "--tasks", "focus", "--sample-fps", "2", "--roi", "0.2,0.1,0.6,0.8"]
    )
    assert proc.returncode == 0, proc.stderr

    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    _assert_schema(data)
    assert len(data["timeline"]) > 0
    assert data["config"]["sampleFps"] == 2
    assert data["audio"]["engine"] is None
    assert data["audio"]["degraded"] is True
    assert data["audio"]["segments"] == []


def test_missing_video_exit_code_2(tmp_path):
    output = str(tmp_path / "missing.json")
    proc = _run_cli(["--video", "/nonexistent/video.mp4", "--output", output])
    assert proc.returncode == 2
    # 仍应输出带 error 字段的 JSON
    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    assert "error" in data


def test_invalid_task_exit_code_2(test_video, tmp_path):
    output = str(tmp_path / "bad.json")
    proc = _run_cli(
        ["--video", test_video, "--output", output, "--mock", "--tasks", "focus,bogus"]
    )
    assert proc.returncode == 2
    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    assert "error" in data


def test_real_degraded_no_models(test_video, tmp_path):
    """无重型依赖时，真实模式应降级为 OpenCV 几何规则并产出合法 JSON。"""
    output = str(tmp_path / "real.json")
    proc = _run_cli(["--video", test_video, "--output", output, "--sample-fps", "1"])
    # 环境若恰好装了重型依赖也可能成功；只要不是运行异常(3) 即视为可接受的降级路径
    assert proc.returncode in (0,), proc.stderr

    with open(output, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    _assert_schema(data)
    assert any(algo["degraded"] for algo in data["algorithms"])
