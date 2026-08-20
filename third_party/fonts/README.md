# Bundled Lean fonts

Lean bundles static instances of two Google Fonts families so typography does
not depend on Google Play Services, network access, or a downloadable-font
provider at runtime.

## Reproducible source

- Repository: <https://github.com/google/fonts>
- Revision: `9fab8b6c1404bc83b164b41c12427f7032b60f42`
- Onest source:
  `ofl/onest/Onest[wght].ttf`,
  SHA-256 `3FAA4B905661849B2332E394B42F91B5BF5575E553C516CAA81811E868A4D589`
- Unbounded source:
  `ofl/unbounded/Unbounded[wght].ttf`,
  SHA-256 `323B511BE380C8D474EF030686B71AEDDE501F8D9CD46DA558B7C40454372C3F`
- Instancer: FontTools `4.59.1`,
  `python -m fontTools.varLib.instancer <source> wght=<weight>
  --update-name-table --output=<resource>`

Static Onest weights 400, 500, 600, and 700 and static Unbounded weights 600,
700, and 800 are stored in `app/src/main/res/font`. Each output has no `fvar`
table, its `OS/2.usWeightClass` matches the requested weight, and its cmap was
validated for Russian Cyrillic letters and punctuation.

## Licenses

Both families are licensed under the SIL Open Font License 1.1. The verbatim
upstream license files are stored beside this document:

- `onest/OFL.txt`, SHA-256
  `38067ADA6F2FEB2BF53F0E9BD01D25289CFA303194DEC55F49F702CB3A2DAC24`
- `unbounded/OFL.txt`, SHA-256
  `AAC6B47FF0107FF0BBA66244F245B61F34093ADA2C715E22F847C7E43ACACC2A`
