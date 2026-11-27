# Build and import a Romanian dictionary

Plektra can import a locally generated `ro_base.dict` for Romanian suggestions
and autocorrection. You need Python 3 and a checkout of this repository on your
computer, plus a UTF-8 word/frequency file whose terms permit your intended use.
The conversion below uses only Python's standard library.

## Prepare the word list

Use one word and a positive integer frequency per line, separated by spaces or
tabs. Higher counts mean more common words. Counts must fit a signed 32-bit
integer (1–2147483647); blank lines are ignored. There is no header. For example:

```text
școală 24000
țară 2200
pâine 200
înger 18
```

Use Romanian comma-below letters `ș` and `ț`. The spelling and case in the input
are preserved for suggestions; separate lowercase, accent-free keys are built
for lookup. Review your list for spelling, unwanted tokens and duplicates before
converting it. Files with column order `frequency word`, CSV headers or extra
columns need to be converted to the format above first.

For a small reproducible trial, use the synthetic fixture
[`scripts/tests/fixtures/ro_words.txt`](../scripts/tests/fixtures/ro_words.txt).
It exercises `ș`, `ț`, `ă`, `â` and `î`; it is a test vocabulary, not a useful
full Romanian dictionary.

## Generate `ro_base.dict`

Run these commands from the repository root:

```sh
mkdir -p /tmp/plektra-ro
python3 convert_dictionaries.py scripts/tests/fixtures/ro_words.txt /tmp/plektra-ro/ro_base.json --scale-frequencies
python3 scripts/preprocess_dictionaries.py /tmp/plektra-ro/ro_base.json --output-dir /tmp/plektra-ro
```

For your real dictionary, replace `scripts/tests/fixtures/ro_words.txt` with your
own UTF-8 file. Keep the intermediate filename `ro_base.json`: the preprocessor
uses its language code to name the final `/tmp/plektra-ro/ro_base.dict`.
Choose an empty working directory if that path already contains your work.

The first command converts words and frequencies to the source JSON schema
(`w`, `f`). `--scale-frequencies` maps counts logarithmically to 1–255 using
`1 + round(254 × log(1 + count) / log(1 + maximum_count))`. This retains more
of the frequency range than linear scaling, though nearby counts can share a
score. Plektra caps ranking frequencies at 255, so raw corpus counts above that
limit would otherwise tie. Omit the flag if your list already uses 1–255 scores. The second builds `normalizedIndex` and `prefixCache` in the format
used by Plektra's runtime. Its `.dict` output is serialized JSON starting with
`{`. The current app supports this format as well as CBOR; no binary conversion
or extra package is required. Renaming the original text file to `.dict` does
not create these indexes.

You can run the conversion checks with:

```sh
python3 -m unittest discover -s scripts/tests -p test_dictionary_conversion.py
```

These checks execute the commands above in a temporary directory and compare
the output byte for byte with the fixture consumed by
`RomanianDictionaryImportTest`. That Robolectric test validates the file,
installs it with the app's importer, loads it through the current runtime decoder
and checks that Romanian words appear as suggestions.

## Import and enable it on Android

1. Copy **`ro_base.dict`** to a folder accessible to Android's file picker, such
   as Downloads. Preserve the exact lowercase filename and `.dict` extension.
2. In Plektra settings, open **Input Languages**. Tap the book icon
   (**Installed dictionaries**), then the **Import dictionary** (+) icon.
3. Select `ro_base.dict` in the Android file picker. After the import finishes,
   Romanian appears with the **Imported** badge.
4. Return to **Input Languages** and add or edit an input language. Select
   **Romanian**, choose the keyboard layout you use, and save. For an existing
   input language, you can instead include Romanian in its suggestion
   dictionaries. Enable suggestions/autocorrection in Plektra's text settings
   as needed, then switch to that input language when typing.
5. With the small fixture, type `scoa`, `tar`, `pain` or `ing` in an ordinary
   text field and check for `școală`, `țară`, `pâine` or `înger` respectively.

Importing another `ro_base.dict` replaces the previous custom Romanian file.
A format error leaves the previous valid import in place. To remove the custom
file, use its uninstall button in **Installed dictionaries**.

If the picker reports an invalid filename, check that the transfer did not add
`.txt` or change `ro_base.dict`. An invalid-format message means the content is
not an accepted serialized dictionary: repeat both conversion steps and transfer
the final `.dict`, rather than the intermediate `.json` or source text. If the
file imports but suggestions do not appear, check the selected input language,
its suggestion dictionaries and the suggestion switch.

## Finding a larger Romanian list

One candidate is Hermit Dave's
[FrequencyWords Romanian 2018 directory](https://github.com/hermitdave/FrequencyWords/tree/master/content/2018/ro),
which provides `ro_50k.txt` and `ro_full.txt` in word/count format. The
[project README](https://github.com/hermitdave/FrequencyWords#license) states
**CC BY-SA 4.0 for content** and **MIT for code**, and identifies OpenSubtitles
2018 as the source of the 2018 lists. Check the upstream terms and quality for
your intended use, and retain the source/version and required attribution when
sharing a derived dictionary. This guide links to that source; it does not
redistribute it or claim an official Romanian download is available.
