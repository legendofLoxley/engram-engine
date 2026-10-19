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

The order the file writes its text is never used. Words come with positions and are laid out from those: words on a baseline form lines,
lines split at column gutters into segments, segments stacked and overlapping form blocks, and blocks are ordered by recursive X-Y cut
(split at the widest empty band, top to bottom or left to right). Two boxes the file draws right to left still come out left to right. This is
the Board comparison case: plain text extraction returned the Scenario A and B narrative boxes swapped relative to the table (table: A 80
percent of students in sustainable schools, B 73 percent). Each text chunk keeps its blocks with their boxes, so a reader can check a
passage against the rendered page (`PdfSourceCli --dry-run --render-dir`).

## Tables: their own chunk, not part of the page chunk

A table is detected per page and kept as rows and cells (`ruled`: cells bounded by drawn lines, at least 2 by 2; `aligned`: three or more
consecutive lines whose columns line up and whose later cells are mostly numbers). Each table is **its own chunk** with the page, box and
cells, placed among the page's text chunks in reading order. Why:

1. A table flowed into page text loses which cell is under which header, which is exactly what went wrong in the Board comparison; a chunk
   whose text is `row | cell | cell` and whose `table` property holds the cells keeps both.
2. Cutting at 1,500 characters would split a table mid-row; a table chunk is cut at no length.
3. An item taken from the table can quote one row and tie to one chunk with one location (`table 1`), instead of a long page chunk.
4. Its words are not repeated in the page's narrative chunks, so nothing is counted twice. A heading directly above a table becomes the first
   line of the table chunk (its caption) and is also its section.

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
- CLI: `app.alfrd.engram.scripts.PdfSourceCli <file.pdf> --user <label>` commits through that route; `--dry-run [--render-dir DIR]` prints the
  per-page result and renders the table pages. It goes through the running engine because the embedded graph has one owner at a time.

## Not covered

Rotated pages; merged table cells (a spanning cell lands in the cell holding its centre); tables without drawn lines and without numeric
columns; multi-column text with no gutter wider than 2.2 em; scripts other than the OCR language. Checked on generated PDFs and, when
`ENGRAM_PPS_PDF_DIR` is set, on the public PPS PDFs (`PpsDocumentsTest`).
