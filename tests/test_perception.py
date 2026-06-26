"""视觉感知字段与 LLM 上下文拼接。"""
from backend.schemas import (
    PerceptionInput,
    VisionObjectDetail,
    VisionPerception,
    format_perception_dict,
    is_vision_only_perception,
    perception_to_dict,
    vision_scene_text,
)
from backend.main import _build_user_text


def test_root_level_scene_normalized_to_vision():
    p = PerceptionInput.model_validate({
        "scene": "画面中可见：盆栽",
        "objects_detail": [{"name": "盆栽", "confidence": 0.45, "location": "画面下左方"}],
    })
    assert p.vision is not None
    assert p.vision.scene == "画面中可见：盆栽"
    assert len(p.vision.objects_detail) == 1


def test_perception_to_dict_prefers_scene():
    p = PerceptionInput(vision=VisionPerception(
        scene="画面中可见：盆栽（画面下左方，识别置信度45%）；克俭有些走神",
        objects_detail=[VisionObjectDetail(name="盆栽", confidence=0.45, location="画面下左方")],
    ))
    data = perception_to_dict(p)
    assert "scene" in data
    assert "盆栽" in data["scene"]
    assert "objects_detail" in data


def test_vision_scene_from_objects_when_no_scene():
    text = vision_scene_text(VisionPerception(
        objects_detail=[VisionObjectDetail(name="盆栽", confidence=0.45, location="画面下左方")],
    ))
    assert "盆栽" in text
    assert "45%" in text


def test_is_vision_only_perception():
    assert is_vision_only_perception({"scene": "日常画面"})
    assert not is_vision_only_perception({"scene": "x", "facial_expression": "开心"})
    assert not is_vision_only_perception({})


def test_build_user_text_vision_only():
    p_data = {"scene": "画面中可见：盆栽；克俭有些走神"}
    assert _build_user_text("", p_data) == "[视觉感知 画面:画面中可见：盆栽；克俭有些走神]"


def test_build_user_text_mixed_with_text():
    p_data = {"scene": "克俭在看手机", "facial_expression": "中性"}
    out = _build_user_text("怎么了", p_data)
    assert out.startswith("[感知 ")
    assert "怎么了" in out


def test_format_perception_dict_includes_scene():
    s = format_perception_dict({"scene": "测试场景"})
    assert s == "画面:测试场景"
