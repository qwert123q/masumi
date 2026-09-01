#!/usr/bin/env python3
"""Derive Masumi's 512px segmentation-only ONNX from the pinned CTD release."""

from __future__ import annotations

import argparse
from pathlib import Path

import onnx
from onnx import utils


SOURCE_BYTES = 94_669_756
OUTPUT_BYTES = 65_568_382
MODEL_SIDE = 512


def require_file(path: Path, expected_bytes: int) -> None:
    if path.stat().st_size != expected_bytes:
        raise ValueError(f"{path} has an unexpected byte length")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "source",
        type=Path,
        help="Pinned beta-0.3 comictextdetector.pt.onnx",
    )
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()

    require_file(arguments.source, SOURCE_BYTES)
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    utils.extract_model(
        arguments.source.as_posix(),
        arguments.output.as_posix(),
        ["images"],
        ["seg"],
    )
    model = onnx.load(arguments.output)
    for value_info in [*model.graph.input, *model.graph.output]:
        if value_info.name not in {"images", "seg"}:
            continue
        dimensions = value_info.type.tensor_type.shape.dim
        dimensions[-2].dim_value = MODEL_SIDE
        dimensions[-1].dim_value = MODEL_SIDE
    # The upstream export carries inferred 1024px shapes for every internal
    # tensor. Drop those stale hints, then infer them again from the 512px
    # public input so runtimes do not merge contradictory signatures.
    model.graph.ClearField("value_info")
    model = onnx.shape_inference.infer_shapes(model)
    onnx.checker.check_model(model)
    onnx.save(model, arguments.output)
    require_file(arguments.output, OUTPUT_BYTES)
    print(f"wrote {arguments.output} ({OUTPUT_BYTES} bytes)")


if __name__ == "__main__":
    main()
