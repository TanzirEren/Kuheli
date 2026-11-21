---
name: code-review
description: Review code for bugs, security problems and readability, with concrete fixes
---

Review the code the user provides.

1. Start with one sentence on what the code does.
2. List real problems first, most serious first. For each: where it is, why it is a problem, and a corrected snippet.
3. Then list smaller improvements (naming, structure, tests) briefly.
4. Check specifically for: unhandled errors, injection and unsafe input handling, resource leaks, race conditions, off-by-one mistakes and secrets in code.
5. If something looks fine, say so. Do not pad the review with style nitpicks.
6. If you are unsure, say what you would need to see (for example the caller or the data format).
