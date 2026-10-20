# PDF to Source and chunks

The extraction half of the Google connector: a local PDF becomes one `document` Source with page-located chunks, through the existing
source commit path ([source-chunk-model.md](source-chunk-model.md)). Source and chunks only; no item is extracted. Fetching from Drive is a
later phase. Code: `app.alfrd.engram.pdf`, run in the Director process on the box, never in the Hermes container.

## What a chunk carries

`page` (physical, 1-based), `printedPage` (the number printed on it, or none), `section`, `kind` (`text` or `table`), `extractor`
(`textlayer` or `ocr`) and, for OCR, `ocrConfidence`; for text, the `box` on the page; for a table, `table` (JSON rows and cells) and `box`.
All are optional properties added to `Chunk`; `Source` gains `fileSha256`, `pageCount`, `ocrPages`, `unreadPages`. A chunk never crosses a
page, and has a text, a hash of it (as every chunk), and a `location` such as `p. 2 (pdf p. 3), table 1, section: Student enrollment outcomes`.

- **Printed page.** A line alone in the top or bottom 9 percent of a page that reads `12`, `Page 12`, `12 of 40`, `12 / 40`, `- 12 -` or a
  roman numeral. It is accepted only when at least two pages agree on the same offset from the physical page, so a lone number or a year in a
  footer is not taken for one. A page without a corroborated number has none (the location says `no printed number`); nothing is inferred.
  The number leaves the page text.
- **Section.** The last heading before the chunk, carried across pages. A heading is a short block set at least 25 percent larger than the
  body text, or a single bold line at body size that does not end in a full stop.

## Reading order

The order the file writes its text is never used. Words are built from the individual glyph positions (not from PDFBox's word grouping, which splits and joins words
unpredictably on scaled or overlapping lines, and whose reported font size can be wrong by a factor of four when the page content is scaled: sizes here come from the
measured glyph height). Words on a baseline form lines, lines split at column gutters into segments, segments stacked and overlapping form blocks, and blocks are ordered:
where a column gutter runs down the region and only a few blocks cross it (a heading or status line across the top), those are spanners and the bands between them are read
column by column, the whole left column then the whole right; otherwise recursive X-Y cut (split at the widest empty band, top to bottom or left to right; a horizontal cut has
to beat a column gutter clearly, not tie with it). Two boxes the file draws right to left still come out left to right. Each text chunk keeps its blocks with their boxes, so a
reader can check a passage against the rendered page (`PdfSourceCli --dry-run --render-dir`).

This addresses the extraction-order artifact seen in the Board comparison of Proposals A and B: plain text extraction returned the Scenario A and B narrative boxes in an order
that did not match the table. The ledger has since withdrawn the suspected label swap: it was this artifact, not a PPS error. Nothing here presumes a source contradiction.

## Tables: their own chunk, not part of the page chunk

A table is detected per page and kept as rows and cells. Three detectors, in this order, each taking its words out of the narrative:

1. `ruled`: cells bounded by drawn lines, at least 2 by 2 (three distinct horizontal and vertical lines). A single box is not a table.
2. `aligned`, figures: a run of at least three baselines that each carry two or more figures standing in the same columns (a designed page's side-by-side comparison, often with
   only a rule between rows and no vertical lines). The row label is the text left of the first column in the row's band (wrapped labels included, a sidebar further left is not
   taken); the header is the lines directly above the first row at the header's own line pitch (a caption further up is left out), each phrase placed by its position.
3. `aligned`, short cells: four or more consecutive lines of the same number (three or more) of aligned segments with at most six words per cell (shaded or banded text tables with no lines
   and no figures). Prose in columns has longer lines and is not taken. A column lines up by overlap, left, right or centre.

Each table is **its own chunk** with the page, box and cells, placed among the page's text chunks in reading order. Why:

1. A table flowed into page text loses which cell is under which header, which is exactly what goes wrong when columns are read out of order; a chunk whose text is
   `row | cell | cell` and whose `table` property holds the cells keeps both.
2. Cutting at 1,500 characters would split a table mid-row; a table chunk is cut at no length.
3. An item taken from the table can quote one row and tie to one chunk with one location (`table 1`), instead of a long page chunk.
4. Its words are not repeated in the page's narrative chunks, so nothing is counted twice. A heading directly above a table becomes the first line of the table chunk (its caption) and is also its section.

## How a page is read, and the read mode

Per page: text layer if it has at least 25 letters or digits that are not mostly unreadable characters; otherwise OCR if an engine is
available, otherwise (or when OCR finds nothing) the page is `unread`. The Source's `readMode` is honest about the whole file:
`full` every page from its text layer; `ocr` every page read and at least one by OCR; `partial` at least one page not read (`unreadPages`
says how many). OCR confidence is the engine's own mean word confidence (0 to 100), stored on the chunk and the page and not interpreted.
OCR pages get no table detection, and a page that has a short text layer plus images is not merged with OCR.

## One non-JVM dependency: Tesseract (not installed by this change)

Everything else is JVM (`org.apache.pdfbox:pdfbox`). OCR uses the `tesseract` command line, found at run time (`ENGRAM_TESSERACT` or the
PATH; language `ENGRAM_TESSERACT_LANG`, default `eng`). **Nothing in the build, the Dockerfile or `.do/app.yaml` installs it.** Where it is
absent (today, on DigitalOcean) pages with no text layer are recorded `unread` and the Source is `partial`. On the box it has to be installed
by whoever owns the box. Tests that need it skip when it is absent; the OCR path is also tested with a stand-in engine.

## Identity, the cache and the routes

- A Source is identified by (owner, `document`, locator, SHA-256 of the file's bytes); `contentHash` holds that SHA-256 and so does
  `fileSha256`. The same file again writes nothing; a changed file is a new Source and the old one stays.
- Preparation is cached by file hash, extractor version and OCR engine id, under `ENGRAM_PDF_CACHE_DIR` (default `./data/pdf-cache`), so a
  file is never prepared twice and an engine that appears later is not hidden behind an entry made without one. A damaged entry is rewritten.
  The cache holds extracted text: it is as private as the graph.
- `POST /debug/sources/pdf` (`syntheticUserId`, and `contentBase64` or `path` to a `.pdf`; optional `locator`, `title`) and
  `GET /debug/sources/{uid}/provenance?syntheticUserId=` (every chunk with its page, printed page, section, kind, how it was read). Same
  token, `DEBUG_CONVERSE_ENABLED` gate and loopback rule as `/debug/sources`; synthetic identities only, never owner-jacob.
- CLI: `app.alfrd.engram.scripts.PdfSourceCliKt <file.pdf> --user <label>` commits through that route; `--dry-run [--render-dir DIR]` prints the
  per-page result and renders the table pages. It goes through the running engine because the embedded graph has one owner at a time.

## Real documents (read in the development session, checked against rendered pages)

Three PDFs from the PPS Shared Drive, Evidence folder, read with this extractor (Tesseract 5.3.4 present; every page had a text layer, so no OCR ran). These were fetched in the
development session through a Drive connector, **not** through the engine's Drive client (see [google-drive-connector.md](google-drive-connector.md)); this is extraction evidence.

| File (Drive name) | Bytes | Pages | Chunks | Tables | Coverage | Processing (cold) |
|---|---|---|---|---|---|---|
| District wide scenarios 10.04.26 (2).pdf | 227,552 | 2 | 6 | 1 (p. 1) | 2 text-layer, 0 OCR, 0 unread, `full` | about 0.6 s |
| SEGC Equity Audit Evaluation Report.pdf | 1,393,167 | 157 | 381 | 2 (pp. 48, 93) | 157 text-layer, 0 OCR, 0 unread, `full` | about 1.4 s |
| SEGC School by School Analysis.pdf | 3,478,054 | 130 | 297 | 0 | 130 text-layer, 0 OCR, 0 unread, `full` | about 1.1 s |

SHA-256 of the files as downloaded (sizes equal Drive's `fileSize`): districtwide `20ad713d5e501614fb69375c86ffd7ce657995b674ca24c70c96e56baabee6b1`, equity audit
`c559a47eb56736166045e6a9ef81bbcfabf3c82558aa3606bffc2fab235672be`, school-by-school `331c4e32c26a39f56f2a8860cd7c664c0af8b70805c3dca84cdc7b317f02bdb1`.
Printed page numbers were found on every page of both SEGC reports (printed = physical, the number sits in the top margin; checked on pages 3, 12, 46, 48, 93) and on neither page of the
districtwide sheet (a poster with no page numbers; its chunks say `no printed number`).

Checked against the rendered pages: the districtwide comparison table (p. 1) comes out as its 8 rows and the header `Measure | SCENARIO A | SCENARIO B | STATUS QUO`, with the row
"Students attending a school above the threshold" = 80% (A), 73% (B), 48% (status quo), matching the page; the three narrative boxes stay in their columns, each with its own figure
(About 80% under A, About 73% under B, About 48% under Status quo). Tables 1.1 (p. 48, 7 rows by 6 columns with its multi-line header) and 3.1 (p. 93, with its header) match their pages.

### Extraction defects found and not fixed

- Districtwide p. 2 (a two-column poster of parallel cards): read column by column, A then B, with each card under its scenario. The count at a card's right edge (14, 38, 13, 8) is its own
  block and comes after that card's list, not beside its title.
- Equity audit p. 146, Figure 6.1 (a table of tall multi-line cells with a graphic strip above): not detected as a table; its words stay in the narrative as blocks, in position order.
- School-by-school: 30 embedded images (charts) are not read: a page with a text layer is not OCR'd, so text inside an image is absent. Pages that are only an image are OCR'd.
- Chart axes (equity audit pp. 46, 54, 58) are not tables and are not reported as one.
- Not verified: every page of the two long reports was not compared against its render. Pages checked are the ones named above.

## Not covered

Rotated pages; merged table cells (a spanning cell lands in the cell holding its centre); tables whose cells are tall multi-line text; text inside images on a page that also has a text layer;
scripts other than the OCR language; tables on OCR-read pages (none are detected there).
