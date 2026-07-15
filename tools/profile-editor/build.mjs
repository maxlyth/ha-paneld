import { build } from "esbuild";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

await build({
  entryPoints: [resolve(here, "src/index.js")],
  outfile: resolve(here, "../../app/src/main/assets/vendor/profile-editor/codemirror.js"),
  bundle: true,
  format: "iife",
  globalName: "ProfileCodeEditor",
  minify: true,
  legalComments: "eof",
  banner: { js: "/*! @license CodeMirror 6 and bundled dependencies: MIT; see adjacent LICENSE.txt and NOTICE.txt */" },
  target: ["chrome83"],
});
