import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
import {
  bracketMatching,
  defaultHighlightStyle,
  foldGutter,
  indentOnInput,
  syntaxHighlighting,
} from "@codemirror/language";
import { yaml } from "@codemirror/lang-yaml";
import { lintGutter, setDiagnostics as applyDiagnostics } from "@codemirror/lint";
import { highlightSelectionMatches, searchKeymap } from "@codemirror/search";
import { Compartment, EditorState } from "@codemirror/state";
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
      lintGutter(),
      readOnly.of([
        EditorState.readOnly.of(!!options.readOnly),
        EditorView.editable.of(!options.readOnly),
      ]),
      listener,
    ],
  });
  const view = new EditorView({ state, parent });

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
      view.destroy();
    },
  };
}
