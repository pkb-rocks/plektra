# Rich-text document storage

This contribution defines local rich-text documents and their persistence contract as the first step toward a keyboard-friendly editor. Each document has a stable UUID, a title, paragraphs and styled text runs.

## Format and limits

The JSON format is version 1. Paragraphs preserve alignment, indentation, heading level and list kind. Runs preserve text, bold, italic, underline and optional text colour and size. Optional page colour and wallpaper framing metadata are retained in the format for subsequent appearance contributions. Wallpaper assets are referenced by a validated content-hash filename.

Parsing validates a collection before accepting it. A collection contains at most 100 documents with distinct identifiers. Each document permits up to 200,000 UTF-16 code units, 10,000 paragraphs and 20,000 runs. The serialized collection is bounded at 10 million UTF-16 code units.

Optional fields from earlier version-1 documents use their original defaults. Unknown versions and malformed collections raise an error so callers can report a recovery problem without silently replacing stored data.

## Persistence

DocumentsRepository stores the collection in the application-private documents preferences. Existing Markdown notes use their own repository.

Saves validate both the document and the resulting collection before committing. Renaming or changing content preserves the document identifier and unrelated documents. Empty, untitled drafts can be discarded while titled or nonempty notes are retained. A shared lock serializes edits from repository instances in the owning process.

The editor contribution will add a separate UI process and a private endpoint to this storage owner. Repository access belongs to one process. Calling the repository independently from multiple processes would require that endpoint.

## Review sequence

1. Versioned document format and local persistence, covered by this contribution.
2. Offline Word export and package validation, including Unicode and nonbreaking spaces.
3. Editor UI, language-aware shortcuts, translations and the private storage endpoint, with an approved recording using fictional documents.
4. Optional document ZIP transfer.
5. Appearance, custom shortcuts, dictionary lookup, viewport gestures and launcher integration as further contributions.

## Validation

The focused tests cover save/reopen, retention of unrelated documents during edits, malformed-data preservation, optional-field defaults, duplicate identifiers, bounds checking, empty-draft cleanup and concurrent edits by repository instances. Existing Markdown repository and activity tests are run alongside them.
