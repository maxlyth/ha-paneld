import { syntaxTree } from "@codemirror/language";

const VALUE_PREFIX = /[A-Za-z0-9_.-]*/;
const VALID_VALUE = /^[A-Za-z0-9_.-]*$/;

function scalarText(state, node) {
  const value = state.sliceDoc(node.from, node.to).trim();
  if (value.length >= 2) {
    const first = value[0];
    const last = value[value.length - 1];
    if ((first === "\"" && last === "\"") || (first === "'" && last === "'")) {
      return value.slice(1, -1);
    }
  }
  return value;
}

function nearestValuePair(state, position) {
  const tree = syntaxTree(state);
  const line = state.doc.lineAt(position);
  let anchor = Math.max(0, position - 1);
  while (anchor > line.from && /\s/.test(state.sliceDoc(anchor, anchor + 1))) anchor -= 1;
  let node = tree.resolveInner(anchor, -1);
  while (node && node.name !== "Pair") node = node.parent;
  if (!node) return null;

  const key = node.getChild("Key");
  const separator = node.getChild(":");
  if (!key || !separator || position < separator.to) return null;
  return node;
}

/** Resolve the schema descriptor path at a YAML scalar value position. */
export function profileFieldPathAt(state, position) {
  let node = nearestValuePair(state, position);
  if (!node) return null;

  const segments = [];
  let arrayDepth = 0;
  while (node) {
    if (node.name === "Item") {
      arrayDepth += 1;
    } else if (node.name === "Pair") {
      const key = node.getChild("Key");
      if (key) {
        const name = scalarText(state, key);
        if (!name) return null;
        segments.unshift(name + "[]".repeat(arrayDepth));
        arrayDepth = 0;
      }
    }
    node = node.parent;
  }
  return segments.join(".");
}

/** Complete only server-advertised enum values; validation remains authoritative on the server. */
export function completeProfileSchemaValue(context, fields) {
  const prefix = context.matchBefore(VALUE_PREFIX);
  if (!prefix || (!context.explicit && !prefix.text)) return null;

  const path = profileFieldPathAt(context.state, context.pos);
  const descriptor = (Array.isArray(fields) ? fields : []).find((field) =>
    field && field.path === path && Array.isArray(field.enum_values) && field.enum_values.length,
  );
  if (!descriptor) return null;

  return {
    from: prefix.from,
    options: descriptor.enum_values.map((value) => ({
      label: String(value),
      type: "enum",
      detail: descriptor.path,
      info: descriptor.description ? String(descriptor.description) : undefined,
    })),
    validFor: VALID_VALUE,
  };
}
