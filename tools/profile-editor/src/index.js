import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import {
  bracketMatching,
  defaultHighlightStyle,
  HighlightStyle,
  foldGutter,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { yaml } from "@codemirror/lang-yaml";
import { lintGutter, setDiagnostics as applyDiagnostics } from "@codemirror/lint";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { Compartment, EditorState } from "@codemirror/state";
import { tags } from "@lezer/highlight";
import {
  crosshairCursor,
  drawSelection,
  dropCursor,
  EditorView,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from "@codemirror/view";

/**
 * Small browser-neutral wrapper around the pinned CodeMirror bundle. The rest of the Profile tab
 * deliberately depends only on this interface so the editor can fall back to a textarea when an old
 * WebView cannot initialize CodeMirror.
 */
export function create(parent, options = {}) {
  const readOnly = new Compartment();
  const theme = new Compartment();
  const darkTheme = EditorView.theme({
    "&": { backgroundColor: "#1f2329", color: "#e6edf3" },
    ".cm-content": { caretColor: "#e6edf3" },
    ".cm-cursor, .cm-dropCursor": { borderLeftColor: "#e6edf3" },
    ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: "#264f78" },
    ".cm-gutters": { backgroundColor: "#161b22", color: "#8b949e", borderRightColor: "#30363d" },
    ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#2a313c" },
  }, { dark: true });
  const lightTheme = EditorView.theme({
    "&": { backgroundColor: "#ffffff", color: "#24292f" },
    ".cm-content": { caretColor: "#24292f" },
    ".cm-cursor, .cm-dropCursor": { borderLeftColor: "#24292f" },
    ".cm-selectionBackground, &.cm-focused .cm-selectionBackground": { backgroundColor: "#b6d7ff" },
    ".cm-gutters": { backgroundColor: "#f6f8fa", color: "#57606a", borderRightColor: "#d0d7de" },
    ".cm-activeLine, .cm-activeLineGutter": { backgroundColor: "#f2f5f8" },
  });
  const darkHighlight = HighlightStyle.define([
    { tag: [tags.keyword, tags.bool, tags.null], color: "#ff7b72" },
    { tag: [tags.string, tags.regexp], color: "#a5d6ff" },
    { tag: [tags.number, tags.atom], color: "#79c0ff" },
    { tag: [tags.comment], color: "#8b949e", fontStyle: "italic" },
    { tag: [tags.propertyName, tags.variableName], color: "#d2a8ff" },
    { tag: [tags.operator, tags.punctuation], color: "#c9d1d9" },
  ]);
  const lightHighlight = HighlightStyle.define([
    { tag: [tags.keyword, tags.bool, tags.null], color: "#cf222e" },
    { tag: [tags.string, tags.regexp], color: "#0a3069" },
    { tag: [tags.number, tags.atom], color: "#0550ae" },
    { tag: [tags.comment], color: "#6e7781", fontStyle: "italic" },
    { tag: [tags.propertyName, tags.variableName], color: "#8250df" },
    { tag: [tags.operator, tags.punctuation], color: "#24292f" },
  ]);
  const media = typeof window !== "undefined" && window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;
  const currentTheme = () => {
    const forced = typeof document !== "undefined" ? document.documentElement.getAttribute("data-theme") : null;
    const dark = forced === "dark" || (forced !== "light" && media && media.matches);
    return dark ? [darkTheme, syntaxHighlighting(darkHighlight)] : [lightTheme, syntaxHighlighting(lightHighlight)];
  };
  const listener = EditorView.updateListener.of((update) => {
    if (update.docChanged && options.onChange) options.onChange(update.state.doc.toString());
  });
  const state = EditorState.create({
    doc: options.value || "",
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      history(),
      foldGutter(),
      drawSelection(),
      dropCursor(),
      indentOnInput(),
      bracketMatching(),
      rectangularSelection(),
      crosshairCursor(),
      highlightActiveLine(),
      highlightSelectionMatches(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      keymap.of([...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab]),
      yaml(),
      theme.of(currentTheme()),
      lintGutter(),
      readOnly.of([
        EditorState.readOnly.of(!!options.readOnly),
        EditorView.editable.of(!options.readOnly),
      ]),
      listener,
    ],
  });
  const view = new EditorView({ state, parent });
  const onThemeChange = () => view.dispatch({ effects: theme.reconfigure(currentTheme()) });
  if (media) media.addEventListener ? media.addEventListener("change", onThemeChange) : media.addListener(onThemeChange);

  return {
    getValue() {
      return view.state.doc.toString();
    },
    setValue(value) {
      const next = String(value == null ? "" : value);
      if (next === view.state.doc.toString()) return;
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: next } });
    },
    setReadOnly(value) {
      view.dispatch({
        effects: readOnly.reconfigure([
          EditorState.readOnly.of(!!value),
          EditorView.editable.of(!value),
        ]),
      });
    },
    setDiagnostics(diagnostics) {
      const normalized = (diagnostics || []).map((item) => ({
        from: Math.max(0, Math.min(view.state.doc.length, Number(item.from) || 0)),
        to: Math.max(0, Math.min(view.state.doc.length, Number(item.to) || Number(item.from) || 0)),
        severity: item.severity === "warning" || item.severity === "info" ? item.severity : "error",
        message: String(item.message || "Invalid profile"),
      }));
      view.dispatch(applyDiagnostics(view.state, normalized));
    },
    focus() {
      view.focus();
    },
    destroy() {
      if (media) media.removeEventListener ? media.removeEventListener("change", onThemeChange) : media.removeListener(onThemeChange);
      view.destroy();
    },
  };
}
