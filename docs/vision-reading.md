# Vision page reading

Reads the pages of a PDF that the text reader cannot structure (tables, charts, posters and cards, pages with no text layer) with the box's local
vision model, checks what it reports against the page's own text, and adds what holds up to the Source as one more chunk per page, from which
claims are made directly. Code: `app.alfrd.engram.vision`, `claims/StructuredClaimModel.kt`; routes `POST /debug/sources/{uid}/vision/plan` and
`/vision/page`; CLIs `VisionReadCliKt` (pages into chunks) and `VisionPageCliKt` (one page, nothing written). Plan and bake-off results: the
Notion task "Vision-aware PDF reading: get the PPS documents accurate".

Status: first box run 2026-10-22 (reader v1) on the districtwide sheet and the SEGC Equity Audit; results in the Notion task. v2 fixes what
that run showed: printed table headers and card text rejected because the text layer interleaves wrapped cells, numbers repeating table cells,
long top headings losing their claims, and values dropped under headings the model made up. Second box run 2026-10-22 (v2, identity
`pps-vision-2`): p.146 complete, no wrong value in 10 spot checks; v3 fixes what it showed: prose pages routed for nothing (17 of 20 empty
Equity pages), chart values lost because their labels are assembled from text printed apart, two unreadable answers, prose under a figure
filed under the figure, chart values without their year. Third box run 2026-10-23 (v3, `pps-vision-3`): 0 failed pages, p.44 and p.146
complete, chart years carried, no wrong value in 10 spot checks (5 lacked context). v4 fixes what it showed: misread names ("Sitten",
"Siton", "Dunaway") cost whole list items, long items with an "a" were not matched, text stored in the model's case ("SEGc"), and a caption
with a parenthesis after its number ("Figure 2.5 (DLI)") was not routed.

## Servers on the box (2026-10-22)

| Port | Model | Vision | Role here |
|---|---|---|---|
| 8081 | Qwen3.6-35B-A3B | off | chat, claims; shared with Hermes, not used for vision |
| 8083 | Qwen3.8-27B + mmproj | on | page structure: headings and what sits under each |
| 8084 | PaddleOCR-VL-1.5 + mmproj | on | fast near-verbatim OCR text of the page image |

`LocalVisionClient` refuses any host that is not loopback, so a page image never leaves the box.

## Reading a source's pages (`VisionReadCli`)

1. **Route.** A page goes to vision when the text reader found a table on it, it embeds an image of at least 300×200 pixels (charts, figures;
   logos and icons are smaller), it has a line starting a figure or table caption ("Figure 2.5", "Table 3.1": charts drawn as shapes), it has at
   least 12 text blocks averaging at most 140 characters (cards, posters; a prose page of many paragraphs and footnotes averages 200 or more),
   or it has no text layer. Everything else stays with the text
   reader. `--pages plan` prints the routing.
2. **Read** each routed page as below, then **keep** a value only when it is found on the page, is not found only outside the heading it was
   filed under, and (for a number) its label is printed: as written, or each of its words somewhere on the page (a chart value's label is
   its category, legend entry and year, printed apart from it). An answer that cannot be read is asked for once more; a second failure says
   why (for example cut off at the token limit). A name the model misread by a letter or two is kept with the page's spelling when the
   corrected value is then found whole (reported per page as `corrected`), and every kept value is written as the page prints it (its case). A table cell is kept with its row label and column header, all three
   found. Chart text is kept as text of the chunk but makes no claims. What is left out is reported per page (`rejectedValues`, naming which
   part failed: the cell, its row label or its column header), never stored.
   - A heading the page does not have is left out of the path; what the model put under it is checked as under the heading above. A number
     left with neither heading nor label is left out.
   - A number that is a part of a cell of a table in the same section is left out: the cell states it with its row and column.
3. **Add** one chunk per page (`kind = vision`, `extractor = vision:<model the server reported>`, location `pdf p. N, vision reading`) after the
   Source's existing chunks. The Source and its text-layer chunks are untouched. Its text is one line per kept entry under bare heading lines:
   ```
   SCENARIO A
   SCENARIO A > ATTENDANCE BOUNDARY CHANGES: 38
   Figure 6.1 Deriving Planning Floors for Well Resourced Schools > Elementary > Planning floor range estimate: Approximately 450+
   ```
   The entries are also stored as JSON on the chunk (`reading`). Keyed by source, page and reader version (`structure-v4`): reading a page
   again adds nothing and calls no model, so a stopped run is resumed by running it again. The file must be the Source's own (same SHA-256).
4. **Claims:** `ClaimExtractionCli --reader structure` makes one claim per kept entry, quoted by its line, placed under its top heading, with
   its sub-headings as the subject (a top heading longer than a place can be, such as a survey question set as a title, goes in the subject). No model call (the check was made when the chunk was made). The text passes (`--reader text`, the default)
   skip vision chunks, and a structure run skips text chunks, so neither questions the other's claims.

```
java -cp engram-engine.jar app.alfrd.engram.scripts.VisionReadCliKt --user pps-drive-test --source <uid> --file <the source's pdf> [--pages auto|plan|2,146] [--no-ocr] [--stop-after 3] --url <engine>
java -cp engram-engine.jar app.alfrd.engram.scripts.ClaimExtractionCliKt --user pps-drive-test --source <uid> --reader structure --run-id <id>
```
Servers: `VISION_LLM_BASE_URL` (default `http://127.0.0.1:8083/v1`) and `OCR_LLM_BASE_URL` (default `http://127.0.0.1:8084/v1`), loopback only.
`--show N` prints the stored vision chunk of page N (`POST /debug/sources/{uid}/vision/chunks`; text and entries), so what was kept can be
checked against the page. The file is passed by path, so the engine's process must be able to read it: `engram-dev` runs with `PrivateTmp=yes`, so not under `/tmp`.
The run stops after `--stop-after` pages fail in a row (a server that is down fails every page); run the same command again to resume.

Chunks are keyed by reader version (now `structure-v4`), so a new version's reading of a page already read is a second chunk beside the
first, and its claims sit beside the old ones. Read a new version into a Source that has no older vision chunks (a fresh synthetic identity).

## One page, nothing written (`VisionPageCli`)

1. Renders the page to PNG (150 dpi by default).
2. Asks 8083 for JSON (`PageStructure.PROMPT`): every heading in reading order, and under each its numbers, list items, tables (rows of cells),
   charts and nested sub-headings, copied exactly as printed.
3. With `--ocr`, asks 8084 for an OCR transcript (`OCR:`).
4. `StructureCheck`: every heading and value is looked for in the page's text layer (in the PDF reader's reading order) and in the OCR
   transcript. Case, spacing and typographic dashes do not matter; a number must stand on its own ("38" is not found inside "138"). A value
   of two or more words not found as written is found when all its words occur within four words of each other in any order (wrapped table
   headers, a card title read with its text); never for one word, a number, or a value with a one-letter word ("SCENARIO A"). For each
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
- Routing thresholds (12 blocks of at most 140 characters, 300×200 pixels) are set from the three PPS documents; the plan output shows why
  each page was routed. On the SEGC Equity Audit they route 17 of 157 pages (v2 routed 56, mostly prose).
- The section check passes a value that lies anywhere after its heading and before the next one, so a figure caption's span can swallow the
  paragraph after the figure (v2 spot check: 2 of 10 claims). The v3 prompt asks for that text under the section heading; the check cannot
  tell the figure from the paragraph.
- A misread name is corrected only to the one word the page prints within two edits with the same first letter, for capitalized words of
  five letters or more; a shorter name, or one with two near neighbours on the page, is still rejected with its item.
- Values often lose their context: "78% students of color" without the school or year, "150 students" without "over" (v3 spot check: 5 of
  10). The value is right and is checked; what it is about rests on the model's label and the headings.
- The model sometimes leaves a chart value's year out of its label (v2: "216 White students" without 2020–21). The v3 prompt asks for it; the
  check cannot add what the model leaves out.
- 8083 is a transient unit on the box: after a reboot it must be started again (see its Deployments row).
