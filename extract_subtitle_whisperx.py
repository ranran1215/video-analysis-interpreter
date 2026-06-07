import json
import os
import sys
import time
import traceback
import typing

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
    print("警告：未安装 opencc-python-reimplemented，无法进行繁简转换", file=sys.stderr)

# 修复 PyTorch 2.6+ 的 weights_only 问题。
torch.serialization.add_safe_globals([ListConfig, DictConfig, ContainerMetadata])
torch.serialization.add_safe_globals([typing.Any])
torch.serialization.add_safe_globals([type(lambda: None).__class__, type])


def get_bool_env(name, default):
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    return value.strip().lower() in ("1", "true", "yes", "y", "on")


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

    config = {
        "model": os.getenv("WHISPER_MODEL", "base").strip() or "base",
        "device": device,
        "compute_type": compute_type,
        "batch_size": get_int_env("WHISPER_BATCH_SIZE", 16),
        "enable_align": get_bool_env("WHISPER_ENABLE_ALIGN", True),
        "align_fallback": get_bool_env("WHISPER_ALIGN_FALLBACK", True),
    }
    return config


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


def normalize_word(word, language):
    text = convert_text_if_needed(word.get("word", ""), language)
    normalized = {
        "word": text,
    }
    if "start" in word:
        normalized["start"] = safe_float(word.get("start"))
    if "end" in word:
        normalized["end"] = safe_float(word.get("end"))
    if "score" in word:
        normalized["score"] = safe_float(word.get("score"))
    return normalized


def normalize_segments(segments, language):
    output_segments = []
    all_text = []

    for seg in segments or []:
        text = convert_text_if_needed(seg.get("text", ""), language).strip()
        if not text:
            continue

        normalized = {
            "start": safe_float(seg.get("start")),
            "end": safe_float(seg.get("end")),
            "text": text,
        }

        if isinstance(seg.get("words"), list):
            words = [normalize_word(word, language) for word in seg["words"] if isinstance(word, dict)]
            if words:
                normalized["words"] = words

        output_segments.append(normalized)
        all_text.append(text)

    return output_segments, " ".join(all_text)


def align_segments(raw_result, audio, detected_language, device, enable_align, align_fallback):
    raw_segments = raw_result.get("segments", [])
    if not enable_align:
        print("WHISPER_ENABLE_ALIGN=false，跳过 forced alignment，使用 Whisper 原始 segment 时间戳", file=sys.stderr)
        return raw_segments, False, 0

    align_start = time.perf_counter()
    try:
        print(f"加载 WhisperX align 模型，language_code={detected_language}, device={device}", file=sys.stderr)
        align_model, metadata = whisperx.load_align_model(language_code=detected_language, device=device)

        print("正在执行 WhisperX forced alignment...", file=sys.stderr)
        aligned_result = whisperx.align(
            raw_segments,
            align_model,
            metadata,
            audio,
            device,
            return_char_alignments=False,
        )

        aligned_segments = aligned_result.get("segments", raw_segments)
        align_duration_ms = round((time.perf_counter() - align_start) * 1000)
        print(f"forced alignment 完成，共 {len(aligned_segments)} 个片段，耗时 {align_duration_ms} ms", file=sys.stderr)
        return aligned_segments, True, align_duration_ms
    except Exception as exc:
        align_duration_ms = round((time.perf_counter() - align_start) * 1000)
        print(f"警告：WhisperX forced alignment 失败: {exc}", file=sys.stderr)
        print(f"forced alignment 失败前耗时 {align_duration_ms} ms", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        if align_fallback:
            print("已回退到 Whisper 原始 segment 时间戳", file=sys.stderr)
            return raw_segments, False, align_duration_ms
        raise


def extract_subtitle_whisperx(video_path, language=None, output_dir=None):
    """
    使用 WhisperX 提取字幕：
    1. whisperx.load_model().transcribe() 转录；
    2. whisperx.load_align_model() + whisperx.align() 做 forced alignment；
    3. 如果 forced alignment 失败且启用 fallback，则回退到原始 segment 时间戳。
    """
    if not os.path.exists(video_path):
        return {"error": f"视频文件不存在: {video_path}"}

    if output_dir is None:
        output_dir = os.path.dirname(video_path)

    config = resolve_runtime_config()
    requested_language = normalize_language(language)

    try:
        print(f"正在处理视频: {video_path}", file=sys.stderr)
        print("使用 WhisperX 转录 + forced alignment 提取字幕", file=sys.stderr)
        print(
            "配置: "
            f"model={config['model']}, device={config['device']}, "
            f"compute_type={config['compute_type']}, batch_size={config['batch_size']}, "
            f"enable_align={config['enable_align']}, align_fallback={config['align_fallback']}",
            file=sys.stderr,
        )

        if config["device"] == "cuda" and torch.cuda.is_available():
            torch.backends.cuda.matmul.allow_tf32 = True
            torch.backends.cudnn.allow_tf32 = True

        vad_options = {
            "vad_onset": 0.8,
            "vad_offset": 0.5,
        }

        print(f"加载 Whisper 模型 ({config['model']})...", file=sys.stderr)
        model = whisperx.load_model(
            config["model"],
            config["device"],
            compute_type=config["compute_type"],
            vad_options=vad_options,
        )

        print("加载音频...", file=sys.stderr)
        audio = whisperx.load_audio(video_path)

        print("正在转录...", file=sys.stderr)
        if requested_language:
            print(f"强制使用指定语言: {requested_language}", file=sys.stderr)
            raw_result = model.transcribe(audio, batch_size=config["batch_size"], language=requested_language)
        else:
            print("自动检测语言...", file=sys.stderr)
            raw_result = model.transcribe(audio, batch_size=config["batch_size"])

        detected_language = raw_result.get("language") or requested_language or "unknown"
        print(f"转录完成，检测到语言: {detected_language}", file=sys.stderr)

        selected_segments, aligned, align_duration_ms = align_segments(
            raw_result,
            audio,
            detected_language,
            config["device"],
            config["enable_align"],
            config["align_fallback"],
        )

        print("开始处理字幕片段...", file=sys.stderr)
        output_segments, full_text = normalize_segments(selected_segments, detected_language)
        output_result = {
            "language": detected_language,
            "text": full_text,
            "segments": output_segments,
            "aligned": aligned,
            "alignDurationMs": align_duration_ms,
        }

        print(f"字幕处理完成，共 {len(output_segments)} 个片段", file=sys.stderr)
        print("\n前3条字幕:", file=sys.stderr)
        for seg in output_segments[:3]:
            print(f"  [{seg['start']:.2f}s - {seg['end']:.2f}s] {seg['text']}", file=sys.stderr)

        output_file = os.path.splitext(video_path)[0] + "_subtitle.json"
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(output_result, f, ensure_ascii=False, indent=2)

        print(f"\n✓ 字幕已保存到: {output_file}", file=sys.stderr)
        print(f"✓ 共提取 {len(output_segments)} 个片段，forced alignment={'成功' if aligned else '未启用或已回退'}", file=sys.stderr)

        return output_result
    except Exception as exc:
        print(f"错误: {str(exc)}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        return {"error": f"处理失败: {str(exc)}"}


def main():
    if len(sys.argv) < 2:
        print("使用方法: python extract_subtitle_whisperx.py <视频路径> [语言代码]", file=sys.stderr)
        print("语言代码可选，例如: zh, en。如果忽略或设为 'auto'，则自动检测。", file=sys.stderr)
        sys.exit(1)

    video_path = sys.argv[1]
    language = sys.argv[2] if len(sys.argv) > 2 else None

    result = extract_subtitle_whisperx(video_path, language=language)
    print(json.dumps(result, ensure_ascii=False))

    if "error" not in result:
        sys.exit(0)
    sys.exit(1)


if __name__ == "__main__":
    main()
