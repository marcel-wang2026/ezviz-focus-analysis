"""音频转文本（ASR）与语音活动检测（VAD）。

引擎优先级（全部惰性导入）:
1. ``faster-whisper`` (https://github.com/SYSTRAN/faster-whisper)
2. ``openai-whisper`` (https://github.com/openai/whisper)
3. ``vosk`` (https://github.com/alphacep/vosk-api)
4. 兜底: ffmpeg ``silencedetect`` 只做静音/VAD 切分，无文本（``degraded=True``）

同时参考 ``py-webrtcvad`` (https://github.com/wiseman/py-webrtcvad) 与
``silero-vad`` (https://github.com/snakers4/silero-vad) 的 VAD 思路，
用 ffmpeg 静音检测近似得到语音时间占比。
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile

from .scoring import ScoringConfig
from .utils import get_logger, num, sec, try_import

logger = get_logger()

REF = "SYSTRAN/faster-whisper"
REF_URL = "https://github.com/SYSTRAN/faster-whisper"

_SILENCE_START = re.compile(r"silence_start:\s*([0-9.]+)")
_SILENCE_END = re.compile(r"silence_end:\s*([0-9.]+)")


class AudioAnalyzer:
    """音频转文本 + VAD 切分。"""

    def __init__(self, config: ScoringConfig) -> None:
        self.cfg = config
        self.engine = "none"
        self.degraded = True

    def analyze(self, video_path: str, lang: str = "zh") -> dict:
        """输出符合 JSON 契约的 audio 字段。

        未启用 audio 任务时由调用方直接返回空结构；此处假定已启用。
        """
        if not os.path.isfile(video_path):
            return _empty_audio()

        workdir = tempfile.mkdtemp(prefix="focus_audio_")
        wav_path = os.path.join(workdir, "audio.wav")
        try:
            if not self._extract_wav(video_path, wav_path):
                return _empty_audio()

            duration = self._wav_duration(wav_path)
            speech_segments = []

            engine = self._transcribe(wav_path, lang)
            if engine is not None:
                speech_segments = engine
                self.degraded = False
            else:
                # 兜底: 仅用 ffmpeg silencedetect 做 VAD 切分，无文本
                speech_segments = self._vad_segments(video_path, duration)
                self.engine = "ffmpeg_silencedetect"
                self.degraded = True

            speech_seconds = 0.0
            for item in speech_segments:
                speech_seconds += max(0.0, num(item.get("endSec")) - num(item.get("startSec")))
            full_text = "".join(item.get("text", "") for item in speech_segments)
            silence_ratio = 1.0 - (speech_seconds / duration) if duration > 0 else 0.0

            return {
                "engine": self.engine,
                "degraded": self.degraded,
                "durationSec": sec(duration),
                "speechDurationSec": sec(speech_seconds),
                "silenceRatio": round(max(0.0, min(1.0, silence_ratio)), 2),
                "segments": [
                    {
                        "startSec": sec(item.get("startSec")),
                        "endSec": sec(item.get("endSec")),
                        "text": item.get("text", ""),
                    }
                    for item in speech_segments
                ],
                "fullText": full_text,
                "keywords": self._extract_keywords(full_text),
            }
        finally:
            shutil.rmtree(workdir, ignore_errors=True)

    # -- 音频抽取 ------------------------------------------------------------
    def _extract_wav(self, video_path: str, wav_path: str) -> bool:
        if shutil.which("ffmpeg") is None:
            logger.warning("未找到 ffmpeg，无法抽取音频")
            return False
        cmd = [
            "ffmpeg", "-y", "-v", "error", "-i", video_path,
            "-vn", "-ac", "1", "-ar", "16000", wav_path,
        ]
        try:
            proc = subprocess.run(
                cmd, capture_output=True, text=True, timeout=1800, check=False
            )
            return proc.returncode == 0 and os.path.getsize(wav_path) > 1024
        except Exception:  # noqa: BLE001
            return False

    def _wav_duration(self, wav_path: str) -> float:
        if shutil.which("ffprobe") is None:
            return 0.0
        try:
            proc = subprocess.run(
                ["ffprobe", "-v", "error", "-show_entries", "format=duration",
                 "-of", "json", wav_path],
                capture_output=True, text=True, timeout=60, check=False,
            )
            data = json.loads(proc.stdout or "{}")
            return float(data.get("format", {}).get("duration") or 0.0)
        except Exception:  # noqa: BLE001
            return 0.0

    # -- 引擎尝试 ------------------------------------------------------------
    def _transcribe(self, wav_path: str, lang: str):
        result = self._transcribe_faster_whisper(wav_path, lang)
        if result is not None:
            return result
        result = self._transcribe_openai_whisper(wav_path, lang)
        if result is not None:
            return result
        result = self._transcribe_vosk(wav_path)
        if result is not None:
            return result
        return None

    def _transcribe_faster_whisper(self, wav_path: str, lang: str):
        module = try_import("faster_whisper")
        if module is None:
            return None
        try:
            model = module.WhisperModel(
                self.cfg.audio_model_size,
                device="cpu",
                compute_type="int8",
            )
            segments, _info = model.transcribe(wav_path, language=lang or "zh")
            self.engine = "faster-whisper"
            items = []
            for segment in segments:
                items.append(
                    {
                        "startSec": num(getattr(segment, "start", 0.0)),
                        "endSec": num(getattr(segment, "end", 0.0)),
                        "text": (getattr(segment, "text", "") or "").strip(),
                    }
                )
            return items
        except Exception as exc:  # noqa: BLE001
            logger.warning("faster-whisper 转写失败: %s", exc)
            return None

    def _transcribe_openai_whisper(self, wav_path: str, lang: str):
        module = try_import("whisper")
        if module is None:
            return None
        try:
            model = module.load_model(self.cfg.audio_model_size)
            result = model.transcribe(wav_path, language=lang or "zh")
            self.engine = "openai-whisper"
            return [
                {
                    "startSec": num(seg.get("start", 0.0)),
                    "endSec": num(seg.get("end", 0.0)),
                    "text": (seg.get("text", "") or "").strip(),
                }
                for seg in result.get("segments", [])
            ]
        except Exception as exc:  # noqa: BLE001
            logger.warning("openai-whisper 转写失败: %s", exc)
            return None

    def _transcribe_vosk(self, wav_path: str):
        module = try_import("vosk")
        if module is None:
            return None
        try:
            model = module.Model(lang="cn")
        except Exception:  # noqa: BLE001
            return None
        # Vosk 需要 PCM 原始流，这里简化处理为整段识别
        try:
            import wave

            wf = wave.open(wav_path, "rb")
            if wf.getnchannels() != 1 or wf.getframerate() != 16000:
                wf.close()
                return None
            recognizer = module.KaldiRecognizer(model, wf.getframerate())
            recognizer.SetWords(True)
            text_parts = []
            while True:
                data = wf.readframes(4000)
                if len(data) == 0:
                    break
                if recognizer.AcceptWaveform(data):
                    text_parts.append(
                        json.loads(recognizer.Result()).get("text", "")
                    )
            text_parts.append(json.loads(recognizer.FinalResult()).get("text", ""))
            wf.close()
            self.engine = "vosk"
            full = " ".join(p for p in text_parts if p)
            return [{"startSec": 0.0, "endSec": 0.0, "text": full}] if full else []
        except Exception as exc:  # noqa: BLE001
            logger.warning("vosk 转写失败: %s", exc)
            return None

    # -- VAD 静音切分兜底 ---------------------------------------------------
    def _vad_segments(self, video_path: str, duration: float) -> list:
        if shutil.which("ffmpeg") is None or duration <= 0:
            return []
        cmd = [
            "ffmpeg", "-v", "error", "-i", video_path,
            "-af", "silencedetect=noise=-35dB:d=1.0", "-f", "null", "-",
        ]
        try:
            proc = subprocess.run(
                cmd, capture_output=True, text=True, timeout=1800, check=False
            )
        except Exception:  # noqa: BLE001
            return []

        text = proc.stderr or ""
        starts = [float(m.group(1)) for m in _SILENCE_START.finditer(text)]
        ends = [float(m.group(1)) for m in _SILENCE_END.finditer(text)]

        # 语音段 = 静音段之间的补集
        speech = []
        cursor = 0.0
        for index, start in enumerate(starts):
            end = ends[index] if index < len(ends) else duration
            if start > cursor + 0.2:
                speech.append(
                    {"startSec": round(cursor, 1), "endSec": round(start, 1), "text": ""}
                )
            cursor = max(cursor, end)
        if cursor < duration - 0.2:
            speech.append(
                {"startSec": round(cursor, 1), "endSec": round(duration, 1), "text": ""}
            )
        return speech

    # -- 关键词 --------------------------------------------------------------
    def _extract_keywords(self, text: str) -> list:
        if not text:
            return []
        keywords = [kw for kw in self.cfg.audio_keywords if kw and kw in text]
        # 去重并保持出现顺序
        seen = set()
        ordered = []
        for kw in keywords:
            if kw not in seen:
                seen.add(kw)
                ordered.append(kw)
        return ordered


def _empty_audio() -> dict:
    """未启用 audio 任务或音频不可用时的空结构。"""
    return {
        "engine": None,
        "degraded": True,
        "durationSec": 0.0,
        "speechDurationSec": 0.0,
        "silenceRatio": 0.0,
        "segments": [],
        "fullText": "",
        "keywords": [],
    }
