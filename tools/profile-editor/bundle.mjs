import { build } from "esbuild";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const toolDirectory = dirname(fileURLToPath(import.meta.url));
export const bundlePath = resolve(toolDirectory, "../../app/src/main/assets/vendor/profile-editor/codemirror.js");
export const licensePath = resolve(toolDirectory, "../../app/src/main/assets/vendor/profile-editor/LICENSE.txt");
export const noticePath = resolve(toolDirectory, "../../app/src/main/assets/vendor/profile-editor/NOTICE.txt");

export function buildProfileEditor({ write = true } = {}) {
  return build({
    entryPoints: [resolve(toolDirectory, "src/index.js")],
    outfile: bundlePath,
    bundle: true,
    format: "iife",
    globalName: "ProfileCodeEditor",
    minify: true,
    legalComments: "eof",
    banner: { js: "/*! @license CodeMirror 6 and bundled dependencies: MIT; see adjacent LICENSE.txt and NOTICE.txt */" },
    target: ["chrome83"],
    write,
  });
}
