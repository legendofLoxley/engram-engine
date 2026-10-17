# Source and chunk model

What the graph holds about a document, page or email message that was read, and the items taken from it.

```
User -TRUSTS-> Source -CONTAINS(position = ordinal)-> Chunk <-QUOTES(writer, claimant)- Item
                                                    User/conversation Source -ASSERTS-> Item   (unchanged)
```

- **Source** (existing vertex, extended): `type` (`notion_page`, `document`, `email_message`), `locator` (where it lives: a page URL or id, a file, an
  email Message-ID), `contentHash` (SHA-256 of the text as read, line endings and outer blank space normalised), `readAt`, `readMode`
  (`full`, `partial`, `ocr`), `ownerEmail`, and for email `threadId`. One email message is one Source; the thread is a property.
  All new properties are optional, so no existing row changes.
- **Chunk** (new vertex): `ownerEmail`, `sourceUid`, `ordinal`, `location` (`section: <heading>`, `part n`, `message, part n`), `text`, `hash`.
  Documents are cut at headings and paragraph edges up to 1,500 characters, with no overlap. For email the quoted reply history ("On … wrote:",
  `>` lines (each such line only), "Original Message" and Outlook header blocks) is dropped before chunking. A chunk is never ASSERTed and is not a Phrase, so the Context
  Horizon cannot surface it as an item.
- **Items** keep being made by the existing Horizon operations. `record_item` gains three optional fields (`chunkUid`, `claimant`, `writer`) that only
  code holding a chunk sets; the applier checks the quote is a passage of that chunk and the chunk is the user's, records the item as usual, and adds
  the `QUOTES` edge (Item -> Chunk) with the writer (who recorded it: `director`, `user`, `hermes`) and, apart from it, the claimant (who asserted it).
  The item stays asserted by the user's conversation Source, as every item is today, because the Horizon labels items from any other Source type as
  the user's explicit statements.
- **Idempotent by content hash.** A Source is identified by (owner, type, locator, content hash). The same content again finds it and writes nothing.
  Changed content under the same locator is a new Source; the old one stays. A new Source and all its chunks are written in one transaction. Items are
  keyed by the content hash and their position, so a repeat commit finds each and adds nothing, not even a cycle number.
- No score or confidence is computed from any of this, and memory text is never logged: logs carry counts and ids.

Debug harness (loopback, debug token, synthetic identities only, never owner-jacob): `POST /debug/sources/commit`, `GET /debug/sources`,
`GET /debug/items/{uid}/provenance`. It stands in for the Hermes fetch assignment, which is not built yet.
