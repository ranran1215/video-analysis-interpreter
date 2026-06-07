import json
import os
import sys
import traceback
import typing
from contextlib import redirect_stdout

ORIGINAL_STDOUT = sys.stdout

os.environ["TORCH_FORCE_WEIGHTS_ONLY_LOAD"] = "0"

import torch

_original_torch_load = torch.load


def _patched_torch_load(*args, **kwargs):
    kwargs["weights_only"] = False
    return _original_torch_load(*args, **kwargs)


torch.load = _patched_torch_load

import whisperx
from omegaconf import DictConfig, ListConfig
from omegaconf.base import ContainerMetadata

try:
    from opencc import OpenCC

    cc = OpenCC("t2s")
    HAS_OPENCC = True
except ImportError:
    HAS_OPENCC = False

torch.serialization.add_safe_globals([ListConfig, DictConfig, ContainerMetadata])
torch.serialization.add_safe_globals([typing.Any])
torch.serialization.add_safe_globals([type(lambda: None).__class__, type])


def get_int_env(name, default):
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value)
    except ValueError:
        print(f"警告：环境变量 {name}={value} 不是整数，使用默认值 {default}", file=sys.stderr)
        return default


def resolve_runtime_config():
    requested_device = os.getenv("WHISPER_DEVICE", "auto").strip().lower()
    if requested_device in ("", "auto"):
        device = "cuda" if torch.cuda.is_available() else "cpu"
    else:
        device = requested_device

    compute_type = os.getenv("WHISPER_COMPUTE_TYPE", "").strip()
    if not compute_type:
        compute_type = "float16" if device == "cuda" else "int8"

    return {
        "model": os.getenv("WHISPER_MODEL", "tiny").strip() or "tiny",
        "device": device,
        "compute_type": compute_type,
        "batch_size": get_int_env("WHISPER_BATCH_SIZE", 16),
    }


def normalize_language(language):
    if language is None:
        return None
    language = language.strip()
    if not language or language == "auto":
        return None
    return language


def convert_text_if_needed(text, language):
    if text is None:
        return ""
    if HAS_OPENCC and language == "zh":
        try:
            return cc.convert(text)
        except Exception as exc:
            print(f"繁简转换失败: {exc}，使用原始文本", file=sys.stderr)
    return text


def safe_float(value, default=0.0):
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def normalize_segments(raw_segments, language, audio_duration_sec=None):
    segments = []
    full_text = []

    for raw in raw_segments or []:
        text = convert_text_if_needed(raw.get("text", ""), language).strip()
        if not text:
            continue
        start = safe_float(raw.get("start"))
        end = safe_float(raw.get("end"), audio_duration_sec or start)
        if audio_duration_sec is not None:
            start = max(0.0, min(start, audio_duration_sec))
            end = max(start, min(end, audio_duration_sec))
        segments.append({
            "start": start,
            "end": end,
            "text": text,
        })
        full_text.append(text)

    return segments, " ".join(full_text)


class RealtimeTranscriber:
    def __init__(self):
        self.config = resolve_runtime_config()
        self.model = None

    def ensure_model(self):
        if self.model is not None:
            return

        print(
            "配置: "
            f"model={self.config['model']}, device={self.config['device']}, "
            f"compute_type={self.config['compute_type']}, batch_size={self.config['batch_size']}",
            file=sys.stderr,
        )

        if self.config["device"] == "cuda" and torch.cuda.is_available():
            torch.backends.cuda.matmul.allow_tf32 = True
            torch.backends.cudnn.allow_tf32 = True

        # Short chunks need a more permissive VAD than full-video transcription.
        vad_options = {
            "vad_onset": 0.3,
            "vad_offset": 0.2,
        }

        print("加载 Whisper 模型（worker 只加载一次）...", file=sys.stderr)
        self.model = whisperx.load_model(
            self.config["model"],
            self.config["device"],
            compute_type=self.config["compute_type"],
            vad_options=vad_options,
        )
        print("Whisper 模型已就绪", file=sys.stderr)

    def transcribe(self, audio_path, language=None):
        if not os.path.exists(audio_path):
            return {"error": f"音频分片不存在: {audio_path}"}

        requested_language = normalize_language(language)

        try:
            print(f"实时分片转录: {audio_path}", file=sys.stderr)
            self.ensure_model()

            print("加载音频分片...", file=sys.stderr)
            audio = whisperx.load_audio(audio_path)
            audio_duration_sec = None
            try:
                audio_duration_sec = float(len(audio)) / 16000.0
            except Exception:
                audio_duration_sec = None

            print("开始转录分片...", file=sys.stderr)
            if requested_language:
                raw_result = self.model.transcribe(
                    audio,
                    batch_size=self.config["batch_size"],
                    language=requested_language,
                )
            else:
                raw_result = self.model.transcribe(audio, batch_size=self.config["batch_size"])

            detected_language = raw_result.get("language") or requested_language or "unknown"
            segments, full_text = normalize_segments(raw_result.get("segments", []), detected_language, audio_duration_sec)

            return {
                "language": detected_language,
                "text": full_text,
                "segments": segments,
            }
        except Exception as exc:
            print(f"实时转录失败: {exc}", file=sys.stderr)
            traceback.print_exc(file=sys.stderr)
            return {"error": f"实时转录失败: {exc}"}


def transcribe_chunk(audio_path, language=None):
    transcriber = RealtimeTranscriber()
    # Some dependencies write logs to stdout; keep stdout clean for the final JSON response.
    with redirect_stdout(sys.stderr):
        return transcriber.transcribe(audio_path, language)


def write_json_line(payload):
    ORIGINAL_STDOUT.write(json.dumps(payload, ensure_ascii=False) + "\n")
    ORIGINAL_STDOUT.flush()


def run_worker():
    transcriber = RealtimeTranscriber()
    print("实时转录 worker 启动，准备预加载模型", file=sys.stderr)
    try:
        with redirect_stdout(sys.stderr):
            transcriber.ensure_model()
        write_json_line({"type": "ready"})
    except Exception as exc:
        traceback.print_exc(file=sys.stderr)
        write_json_line({"type": "error", "error": f"worker 初始化失败: {exc}"})
        sys.exit(1)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
            if request.get("type") == "shutdown":
                write_json_line({"type": "bye"})
                return
            audio_path = request.get("audioPath")
            language = request.get("language")
            with redirect_stdout(sys.stderr):
                result = transcriber.transcribe(audio_path, language)
            result["type"] = "result"
            result["requestId"] = request.get("requestId")
            write_json_line(result)
        except Exception as exc:
            traceback.print_exc(file=sys.stderr)
            write_json_line({
                "type": "result",
                "requestId": None,
                "error": f"worker 请求处理失败: {exc}",
            })


def main():
    if len(sys.argv) >= 2 and sys.argv[1] == "--worker":
        run_worker()
        return

    if len(sys.argv) < 2:
        print("使用方法: python scripts/realtime_transcribe.py <音频分片路径> [语言代码]", file=sys.stderr)
        print("或: python scripts/realtime_transcribe.py --worker", file=sys.stderr)
        sys.exit(1)

    audio_path = sys.argv[1]
    language = sys.argv[2] if len(sys.argv) > 2 else None
    result = transcribe_chunk(audio_path, language)
    write_json_line(result)

    if "error" in result:
        sys.exit(1)
    sys.exit(0)


if __name__ == "__main__":
    main()
