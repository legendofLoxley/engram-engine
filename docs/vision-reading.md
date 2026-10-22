# Vision page reading

Reads the pages of a PDF that the text reader cannot structure (tables, charts, posters and cards, pages with no text layer) with the box's local
vision model, checks what it reports against the page's own text, and adds what holds up to the Source as one more chunk per page, from which
claims are made directly. Code: `app.alfrd.engram.vision`, `claims/StructuredClaimModel.kt`; routes `POST /debug/sources/{uid}/vision/plan` and
`/vision/page`; CLIs `VisionReadCliKt` (pages into chunks) and `VisionPageCliKt` (one page, nothing written). Plan and bake-off results: the
Notion task "Vision-aware PDF reading: get the PPS documents accurate".

Status: implemented and tested against a scripted model on generated fixtures. Not yet run on the box against the real servers.

## Servers on the box (2026-10-22)

| Port | Model | Vision | Role here |
|---|---|---|---|
| 8081 | Qwen3.6-35B-A3B | off | chat, claims; shared with Hermes, not used for vision |
| 8083 | Qwen3.8-27B + mmproj | on | page structure: headings and what sits under each |
| 8084 | PaddleOCR-VL-1.5 + mmproj | on | fast near-verbatim OCR text of the page image |

`LocalVisionClient` refuses any host that is not loopback, so a page image never leaves the box.

## Reading a source's pages (`VisionReadCli`)

1. **Route.** A page goes to vision when the text reader found a table on it, it embeds an image of at least 300×200 pixels (charts, figures;
   logos and icons are smaller), it has at least 12 text blocks (cards, posters), or it has no text layer. Everything else stays with the text
   reader. `--pages plan` prints the routing.
2. **Read** each routed page as below, then **keep** a value only when it is found on the page, is not found only outside the heading it was
   filed under, and (for a number) the words printed with it are found too. A table cell is kept with its row label and column header, all three
   found. Chart text is kept as text of the chunk but makes no claims. What is left out is reported per page (`rejectedValues`), never stored.
3. **Add** one chunk per page (`kind = vision`, `extractor = vision:<model the server reported>`, location `pdf p. N, vision reading`) after the
   Source's existing chunks. The Source and its text-layer chunks are untouched. Its text is one line per kept entry under bare heading lines:
   ```
   SCENARIO A
   SCENARIO A > ATTENDANCE BOUNDARY CHANGES: 38
   Figure 6.1 Deriving Planning Floors for Well Resourced Schools > Elementary > Planning floor range estimate: Approximately 450+
   ```
   The entries are also stored as JSON on the chunk (`reading`). Keyed by source, page and reader version (`structure-v1`): reading a page
   again adds nothing and calls no model, so a stopped run is resumed by running it again. The file must be the Source's own (same SHA-256).
4. **Claims:** `ClaimExtractionCli --reader structure` makes one claim per kept entry, quoted by its line, placed under its top heading, with
   its sub-headings as the subject. No model call (the check was made when the chunk was made). The text passes (`--reader text`, the default)
   skip vision chunks, and a structure run skips text chunks, so neither questions the other's claims.

```
java -cp engram-engine.jar app.alfrd.engram.scripts.VisionReadCliKt --user pps-drive-test --source <uid> --file <the source's pdf> [--pages auto|plan|2,146] [--no-ocr]
java -cp engram-engine.jar app.alfrd.engram.scripts.ClaimExtractionCliKt --user pps-drive-test --source <uid> --reader structure --run-id <id>
```
Servers: `VISION_LLM_BASE_URL` (default `http://127.0.0.1:8083/v1`) and `OCR_LLM_BASE_URL` (default `http://127.0.0.1:8084/v1`), loopback only.

## One page, nothing written (`VisionPageCli`)

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
- A claim from a vision chunk and a claim from the text reader can state the same fact in two places (a narrative sentence and a card count);
  they are separate claims with separate provenance. Nothing merges them yet.
- Routing thresholds (12 blocks, 300×200 pixels) are first guesses from the PPS fixtures; the plan output shows why each page was routed.
- 8083 is a transient unit on the box: after a reboot it must be started again (see its Deployments row).
