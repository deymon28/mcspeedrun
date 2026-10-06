/* Pure editor state shared by the browser and regression tests. */
(function (root) {
  const copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));
  const stable = value => Array.isArray(value) ? value.map(stable) : value && typeof value === "object"
    ? Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])])) : value;
  const equal = (a, b) => JSON.stringify(stable(a)) === JSON.stringify(stable(b));
  const structured = ["progression", "rewards"];
  const nested = (value, parts) => parts.reduce((item, key) => item?.[key], value);
  function assign(value, parts, next) {
    let target = value;
    for (const key of parts.slice(0, -1)) target = target[key] ||= {};
    target[parts.at(-1)] = copy(next);
  }
  class EditorState {
    constructor() { this.active = {}; this.saved = {}; this.defaults = {}; this.changes = {}; this.revision = ""; }
    /**
     * Takes server state. Saving clears the draft; loading and applying keep it, so edits that
     * were applied without saving (or survived a reload) remain visible for a later save.
     */
    accept(response, { keepChanges = false } = {}) {
      if (response.values) this.active = copy(response.values);
      if (response.savedValues) this.saved = copy(response.savedValues);
      else if (response.values) this.saved = copy(response.values);
      if (response.defaults) this.defaults = copy(response.defaults);
      if (response.revision) this.revision = response.revision;
      if (!keepChanges) this.changes = {};
      else this.changes = this.diff(this.saved, this.desired());
    }
    value(path) {
      const parent = structured.find(key => path === key || path.startsWith(key + "."));
      if (!parent) return copy(Object.hasOwn(this.changes, path) ? this.changes[path] : this.saved[path]);
      const value = copy(this.changes[parent] ?? this.saved[parent] ?? {});
      for (const [key, next] of Object.entries(this.changes)) {
        if (key.startsWith(parent + ".")) assign(value, key.slice(parent.length + 1).split("."), next);
      }
      return path === parent ? value : nested(value, path.slice(parent.length + 1).split("."));
    }
    set(path, value) {
      const parent = structured.find(key => path === key || path.startsWith(key + "."));
      if (parent && (path === parent || Object.hasOwn(this.changes, parent))) {
        const next = path === parent ? copy(value) : this.value(parent);
        if (path !== parent) assign(next, path.slice(parent.length + 1).split("."), value);
        for (const key of Object.keys(this.changes)) if (key.startsWith(parent + ".")) delete this.changes[key];
        if (equal(next, this.saved[parent])) delete this.changes[parent]; else this.changes[parent] = next;
      } else if (equal(value, this.saved[path])) delete this.changes[path];
      else this.changes[path] = copy(value);
    }
    diff(before, after) {
      const result = {};
      for (const key of Object.keys(after)) if (!equal(before[key], after[key])) result[key] = copy(after[key]);
      for (const parent of structured) {
        if (Object.hasOwn(result, parent)) for (const key of Object.keys(result)) if (key.startsWith(parent + ".")) delete result[key];
      }
      return result;
    }
    desired() {
      const result = copy(this.saved);
      for (const key of new Set([...Object.keys(this.saved), ...Object.keys(this.changes)])) result[key] = this.value(key);
      return result;
    }
    pending() { return this.diff(this.active, this.desired()); }
    discard() { this.changes = {}; }
    revert(path) {
      delete this.changes[path];
      for (const key of Object.keys(this.changes)) if (key.startsWith(path + ".")) delete this.changes[key];
    }
    hasChange(path) {
      if (Object.hasOwn(this.changes, path)) return true;
      const parent = structured.find(key => path.startsWith(key + "."));
      return Boolean(parent && Object.hasOwn(this.changes, parent) && !equal(this.value(path), nested(this.saved[parent] ?? {}, path.slice(parent.length + 1).split("."))));
    }
    isPending(path) { return !equal(this.saved[path], this.active[path]); }
    patch() { return copy(this.changes); }
  }
  root.SpeedrunEditorState = { EditorState, copy, equal };
  if (typeof module !== "undefined") module.exports = root.SpeedrunEditorState;
})(globalThis);
