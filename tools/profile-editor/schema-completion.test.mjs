import { CompletionContext } from "@codemirror/autocomplete";
import { yaml } from "@codemirror/lang-yaml";
import { EditorState } from "@codemirror/state";
import assert from "node:assert/strict";
import test from "node:test";
import { completeProfileSchemaValue, profileFieldPathAt } from "./src/schema-completion.js";

const mechanism = {
  path: "hardware.led.mechanism",
  enum_values: ["none", "autodetect", "rk3576-ioctl", "rk3576-ioctl-daemon", "sysfs-daemon"],
  description: "Built-in LED route.",
};

function state(source) {
  return EditorState.create({ doc: source, extensions: [yaml()] });
}

function complete(source, fields = [mechanism], explicit = false, marker = "|") {
  const position = source.indexOf(marker);
  assert.notEqual(position, -1, "fixture must contain a cursor marker");
  const document = source.slice(0, position) + source.slice(position + marker.length);
  const editorState = state(document);
  const context = new CompletionContext(editorState, position, explicit);
  return { result: completeProfileSchemaValue(context, fields), state: editorState, position };
}

test("completes the advertised values for a partially typed nested enum", () => {
  const { result } = complete("hardware:\n  led:\n    mechanism: rk|");
  assert.equal(result.from, 32);
  assert.deepEqual(result.options.map((option) => option.label), mechanism.enum_values);
  assert.equal(result.options[0].detail, mechanism.path);
  assert.equal(result.options[0].info, mechanism.description);
});

test("explicit completion works at an empty value and implicit completion stays quiet", () => {
  assert.equal(complete("hardware:\n  led:\n    mechanism: |", [mechanism], false).result, null);
  const { result } = complete("hardware:\n  led:\n    mechanism:   |", [mechanism], true);
  assert.equal(result.from, 34);
  assert.equal(result.options.length, mechanism.enum_values.length);
});

test("resolves descriptor paths through nested YAML sequences", () => {
  const desiredState = {
    path: "provisioning.packages[].desired_state",
    enum_values: ["disabled"],
  };
  const fixture = "provisioning:\n  packages:\n    - package: com.example\n      desired_state: dis|";
  const { result, state: editorState, position } = complete(fixture, [desiredState]);
  assert.equal(profileFieldPathAt(editorState, position), desiredState.path);
  assert.deepEqual(result.options.map((option) => option.label), ["disabled"]);
});

test("supports quoted scalar values without replacing the quote", () => {
  const { result } = complete('hardware:\n  led:\n    mechanism: "rk|"');
  assert.equal(result.from, 33);
  assert.deepEqual(result.options.map((option) => option.label), mechanism.enum_values);
});

test("does not offer enum values in a key or unrelated field", () => {
  assert.equal(complete("hardware:\n  led:\n    mech|anism: rk", [mechanism], true).result, null);
  assert.equal(complete("hardware:\n  screen_off: rk|", [mechanism], true).result, null);
});
