# Vision page reading (first piece)

Status: a tool, not yet part of ingestion or claims. It reads one page of a local PDF with the box's vision servers and checks what they report
against the page's own text. It writes nothing to the graph. Code: `app.alfrd.engram.vision`; CLI `app.alfrd.engram.scripts.VisionPageCliKt`.
Plan and results so far: the Notion task "Vision-aware PDF reading: get the PPS documents accurate".

## Servers on the box (2026-10-22)

| Port | Model | Vision | Role here |
|---|---|---|---|
| 8081 | Qwen3.6-35B-A3B | off | chat, claims; shared with Hermes, not used for vision |
| 8083 | Qwen3.8-27B + mmproj | on | page structure: headings and what sits under each |
| 8084 | PaddleOCR-VL-1.5 + mmproj | on | fast near-verbatim OCR text of the page image |

`LocalVisionClient` refuses any host that is not loopback, so a page image never leaves the box.

## What the tool does

1. Renders the page to PNG (150 dpi by default).
2. Asks 8083 for JSON (`PageStructure.PROMPT`): every heading in reading order, and under each its numbers, list items, tables (rows of cells),
   charts and nested sub-headings, copied exactly as printed.
3. With `--ocr`, asks 8084 for an OCR transcript (`OCR:`).
4. `StructureCheck`: every heading and value is looked for in the page's text layer (in the PDF reader's reading order) and in the OCR
   transcript. Case, spacing and typographic dashes do not matter; a number must stand on its own ("38" is not found inside "138"). For each
   value it also reports whether an occurrence lies under the heading the model put it under (between that heading and its next sibling in
   reading order), or that this cannot be judged.

The two readers are independent: the model reads the image, the check reads the text. A value the model reports that neither text holds is
not on the page as reported; a value found only outside its heading's span is a misfiling to look at. Nothing here establishes a claim.

```
java -cp engram-engine.jar app.alfrd.engram.scripts.VisionPageCliKt <file.pdf> --page 2 \
     [--vision http://127.0.0.1:8083/v1] [--ocr http://127.0.0.1:8084/v1] [--no-thinking] [--dpi 150] [--out report.json]
```
Exit status: 0 read and checked, 2 a server failed or its answer could not be read, 64 usage.

## Known limits

- The section check relies on the text layer's reading order; on designed pages the PDF reader can order blocks differently from the eye,
  so "elsewhere" is a prompt to look, not a verdict. Scanned pages have no text layer; only OCR presence is checked there.
- 8083 takes about 100 s per page with reasoning on (`--no-thinking` is faster; its accuracy is not yet measured).
- Next: route pages (prose stays on the text reader), turn checked structure into chunks and claims, then the remaining PPS pages.
