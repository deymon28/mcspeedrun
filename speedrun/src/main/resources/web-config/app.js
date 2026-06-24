const token = new URLSearchParams(location.search).get("token") || localStorage.getItem("speedrunWebToken") || "";
if (token) localStorage.setItem("speedrunWebToken", token);

let schema = [];
let values = {};
let changes = {};
let activeSection = "General";

const sections = ["General", "Casual", "Scanner", "Progression", "Rewards", "Diagnostics"];
const editor = document.getElementById("editor");
const tabs = document.getElementById("tabs");
const messages = document.getElementById("messages");
const dirtyPill = document.getElementById("dirty-pill");
const preview = document.getElementById("preview-json");
const changeCount = document.getElementById("change-count");

function request(path, options = {}) {
  return fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Speedrun-Token": token,
      ...(options.headers || {})
    }
  }).then(async response => {
    const text = await response.text();
    const data = text ? JSON.parse(text) : {};
    if (!response.ok) throw new Error(data.error || response.statusText);
    return data;
  });
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function sameValue(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

function fieldByPath(path) {
  return schema.find(field => field.path === path);
}

function isDependencyParent(path) {
  return path === "rewards.enabled" || schema.some(field => field.parentPath === path);
}

function currentValue(path) {
  return Object.prototype.hasOwnProperty.call(changes, path) ? changes[path] : values[path];
}

function setChange(path, value) {
  if (sameValue(value, values[path])) {
    delete changes[path];
  } else {
    changes[path] = value;
  }
  updatePreview();
  renderTabs();
  if (isDependencyParent(path)) {
    renderEditor();
  }
}

function setStructuredChange(path, value) {
  if (sameValue(value, values[path])) {
    delete changes[path];
  } else {
    changes[path] = clone(value);
  }
  updatePreview();
  renderTabs();
}

function impactText(impact) {
  return {
    INSTANT: "applies now",
    RESTART_TASK: "restarts task",
    RUN_SENSITIVE: "requires run reset",
    STARTUP_ONLY: "startup only"
  }[impact] || impact.toLowerCase();
}

function valueMatches(actual, expected) {
  if (expected === null || expected === undefined) return true;
  if (typeof expected === "boolean") return Boolean(actual) === expected;
  return String(actual).toUpperCase() === String(expected).toUpperCase();
}

function dependencyState(field, seen = new Set()) {
  if (!field || !field.parentPath || field.parentPath === null) {
    return { active: true, reasons: [] };
  }
  if (seen.has(field.path)) {
    return { active: true, reasons: [] };
  }
  seen.add(field.path);

  const parent = fieldByPath(field.parentPath);
  const parentState = dependencyState(parent, seen);
  const parentValue = currentValue(field.parentPath);
  const directActive = valueMatches(parentValue, field.parentValue);
  const reasons = [...parentState.reasons];
  if (!directActive) {
    reasons.push(field.inactiveReason || `Requires ${field.parentPath} = ${field.parentValue}`);
  }
  return {
    active: parentState.active && directActive,
    reasons
  };
}

function groupDependencySummary(fields) {
  const inactive = fields
    .map(field => dependencyState(field))
    .filter(state => !state.active);
  if (!inactive.length) return "";
  const unique = [...new Set(inactive.flatMap(state => state.reasons))];
  return unique[0] || "Some settings in this group are inactive now.";
}

function updatePreview() {
  const keys = Object.keys(changes);
  dirtyPill.textContent = keys.length ? "Unsaved" : "Clean";
  dirtyPill.classList.toggle("dirty", keys.length > 0);
  changeCount.textContent = `${keys.length} ${keys.length === 1 ? "change" : "changes"}`;
  preview.textContent = JSON.stringify(changes, null, 2);
  renderImpactPreview(keys);
}

function renderImpactPreview(keys) {
  const impactHost = document.getElementById("impact-preview");
  if (!impactHost) return;
  impactHost.innerHTML = "";

  const buckets = new Map();
  for (const key of keys) {
    const field = fieldByPath(key);
    const impact = field ? field.impact : key === "progression" ? "RUN_SENSITIVE" : "INSTANT";
    const label = field ? field.label : key;
    if (!buckets.has(impact)) buckets.set(impact, []);
    buckets.get(impact).push(label);
  }

  if (!keys.length) {
    const empty = document.createElement("div");
    empty.className = "impact-empty";
    empty.textContent = "No pending changes.";
    impactHost.appendChild(empty);
    return;
  }

  for (const impact of ["INSTANT", "RESTART_TASK", "RUN_SENSITIVE", "STARTUP_ONLY"]) {
    const items = buckets.get(impact);
    if (!items || !items.length) continue;
    const group = document.createElement("div");
    group.className = `impact-bucket impact-${impact.toLowerCase().replaceAll("_", "-")}`;
    group.appendChild(textNode("strong", impactText(impact)));
    const list = document.createElement("ul");
    for (const item of items) {
      const li = document.createElement("li");
      li.textContent = item;
      list.appendChild(li);
    }
    group.appendChild(list);
    impactHost.appendChild(group);
  }
}

function showMessages(kind, items) {
  messages.innerHTML = "";
  appendMessages(kind, items);
}

function appendMessages(kind, items) {
  const list = Array.isArray(items) ? items : [items];
  for (const item of list.filter(Boolean)) {
    const node = document.createElement("div");
    node.className = `message ${kind}`;
    node.textContent = item;
    messages.appendChild(node);
  }
}

function renderTabs() {
  tabs.innerHTML = "";
  for (const section of sections) {
    const fields = schema.filter(field => field.section === section);
    const changed = fields.filter(field => Object.prototype.hasOwnProperty.call(changes, field.path)).length;
    const button = document.createElement("button");
    button.className = `tab ${section === activeSection ? "active" : ""}`;
    button.type = "button";
    button.innerHTML = `<span>${section}</span>${changed ? `<b>${changed}</b>` : ""}`;
    button.onclick = () => {
      activeSection = section;
      renderTabs();
      renderEditor();
    };
    tabs.appendChild(button);
  }
}

function renderEditor() {
  document.getElementById("section-title").textContent = activeSection;
  document.getElementById("section-summary").textContent = sectionSummary(activeSection);
  editor.innerHTML = "";

  const fields = schema.filter(field => field.section === activeSection);
  const grouped = groupFields(fields);
  for (const group of grouped) {
    editor.appendChild(renderGroup(group));
  }

  if (activeSection === "Progression") {
    editor.appendChild(renderProgressionEditor());
  }
  if (activeSection === "Rewards") {
    editor.appendChild(renderRewardsEditor());
  }
}

function groupFields(fields) {
  const groups = new Map();
  for (const field of fields) {
    const key = field.group || "ungrouped";
    if (!groups.has(key)) {
      groups.set(key, {
        key,
        label: field.groupLabel || key,
        fields: []
      });
    }
    groups.get(key).fields.push(field);
  }
  return [...groups.values()];
}

function sectionSummary(section) {
  return {
    General: "Core runtime behavior, local web editor access, display, and logging.",
    Casual: "Mode-gated assistance features. Inactive groups stay editable for future runs.",
    Scanner: "Structure detection, pre-scan profiles, and performance caps with dependency states.",
    Progression: "Task display settings plus nested stage and task editing.",
    Rewards: "Reward switches and command lists with parent enablement state.",
    Diagnostics: "Trace and movement logging controls."
  }[section] || "";
}

function renderGroup(group) {
  const inactiveReason = groupDependencySummary(group.fields);
  const changedCount = group.fields.filter(field => Object.prototype.hasOwnProperty.call(changes, field.path)).length;
  const details = document.createElement("details");
  details.className = `setting-group ${inactiveReason ? "inactive-group" : ""}`;
  details.open = true;

  const summary = document.createElement("summary");
  summary.className = "group-summary";
  const title = document.createElement("div");
  title.className = "group-title";
  title.appendChild(textNode("span", group.label));
  title.appendChild(textNode("code", group.key));
  const meta = document.createElement("div");
  meta.className = "group-meta";
  meta.appendChild(textNode("span", `${group.fields.length} settings`));
  if (changedCount) meta.appendChild(textNode("b", `${changedCount} changed`));
  if (inactiveReason) meta.appendChild(textNode("em", inactiveReason));
  summary.append(title, meta);
  details.appendChild(summary);

  const body = document.createElement("div");
  body.className = "group-body";
  for (const field of group.fields) {
    body.appendChild(renderField(field));
  }
  details.appendChild(body);
  return details;
}

function renderField(field) {
  const state = dependencyState(field);
  const changed = Object.prototype.hasOwnProperty.call(changes, field.path);
  const card = document.createElement("article");
  card.className = `field ${state.active ? "" : "inactive-field"} ${changed ? "changed-field" : ""}`;

  const header = document.createElement("div");
  header.className = "field-header";
  const title = document.createElement("div");
  title.className = "field-title";
  title.appendChild(textNode("label", field.label));
  title.appendChild(textNode("code", field.path));
  const badges = document.createElement("div");
  badges.className = "badges";
  badges.appendChild(badge(impactText(field.impact), impactBadgeClass(field.impact)));
  if (field.danger === "DANGEROUS") badges.appendChild(badge("danger", "danger"));
  if (field.danger === "CAUTION") badges.appendChild(badge("caution", "warn"));
  if (!state.active) badges.appendChild(badge("inactive now", "inactive"));
  if (changed) badges.appendChild(badge("changed", "changed"));
  header.append(title, badges);

  const input = inputForField(field);
  const hint = document.createElement("div");
  hint.className = "hint";
  hint.textContent = field.description;
  card.append(header, input, hint);

  if (!state.active) {
    const dependency = document.createElement("div");
    dependency.className = "dependency-note";
    dependency.textContent = state.reasons.join(" ");
    card.appendChild(dependency);
  }
  return card;
}

function textNode(tag, text) {
  const node = document.createElement(tag);
  node.textContent = text;
  return node;
}

function impactBadgeClass(impact) {
  return {
    INSTANT: "ok",
    RESTART_TASK: "warn",
    RUN_SENSITIVE: "warn",
    STARTUP_ONLY: "inactive"
  }[impact] || "";
}

function badge(text, kind) {
  const node = document.createElement("span");
  node.className = `badge ${kind || ""}`;
  node.textContent = text;
  return node;
}

function inputForField(field) {
  const value = currentValue(field.path);
  let input;
  if (field.type === "BOOLEAN") {
    input = document.createElement("input");
    input.type = "checkbox";
    input.checked = Boolean(value);
    input.onchange = () => setChange(field.path, input.checked);
    return input;
  }
  if (field.type === "ENUM") {
    input = document.createElement("select");
    for (const option of field.options || []) {
      const opt = document.createElement("option");
      opt.value = option;
      opt.textContent = option;
      input.appendChild(opt);
    }
    input.value = value;
    input.onchange = () => setChange(field.path, input.value);
    return input;
  }
  if (field.type === "STRING_LIST") {
    input = document.createElement("textarea");
    input.value = Array.isArray(value) ? value.join("\n") : "";
    input.oninput = () => setChange(field.path, input.value.split("\n").map(line => line.trim()).filter(Boolean));
    return input;
  }
  input = document.createElement("input");
  input.type = field.type === "STRING" ? "text" : "number";
  if (field.min !== undefined) input.min = field.min;
  if (field.max !== undefined) input.max = field.max;
  if (field.type === "DECIMAL") input.step = "0.1";
  input.value = value;
  input.oninput = () => {
    const next = field.type === "STRING" ? input.value : Number(input.value);
    setChange(field.path, next);
  };
  return input;
}

function renderProgressionEditor() {
  const progression = clone(currentValue("progression") || {});
  const wrap = document.createElement("section");
  wrap.className = "structured";

  const intro = document.createElement("div");
  intro.className = "structured-intro";
  intro.textContent = "Stages own their world and task rows. Progression changes are saved immediately, but active run progress is kept until reset.";
  wrap.appendChild(intro);

  for (const stageKey of Object.keys(progression).filter(key => key !== "settings")) {
    const stage = progression[stageKey] || {};
    const details = document.createElement("details");
    details.className = "structured-card stage-card";
    details.open = true;

    const summary = document.createElement("summary");
    summary.className = "group-summary";
    const title = document.createElement("div");
    title.className = "group-title";
    title.appendChild(textNode("span", stage["display-name"] || stageKey));
    title.appendChild(textNode("code", stageKey));
    const meta = document.createElement("div");
    meta.className = "group-meta";
    meta.appendChild(textNode("span", `${Object.keys(stage.tasks || {}).length} tasks`));
    meta.appendChild(textNode("span", stage.world || "NORMAL"));
    summary.append(title, meta);
    details.appendChild(summary);

    const body = document.createElement("div");
    body.className = "stage-body";

    const name = document.createElement("input");
    name.value = stage["display-name"] || stageKey;
    name.placeholder = "Display name";
    name.oninput = () => {
      stage["display-name"] = name.value;
      progression[stageKey] = stage;
      setStructuredChange("progression", progression);
    };

    const world = document.createElement("select");
    for (const option of ["NORMAL", "NETHER", "THE_END"]) {
      const opt = document.createElement("option");
      opt.value = option;
      opt.textContent = option;
      world.appendChild(opt);
    }
    world.value = stage.world || "NORMAL";
    world.onchange = () => {
      stage.world = world.value;
      progression[stageKey] = stage;
      setStructuredChange("progression", progression);
    };

    const stageFields = document.createElement("div");
    stageFields.className = "stage-fields";
    stageFields.append(labelWrap("Display name", name), labelWrap("World", world));
    body.appendChild(stageFields);

    const table = document.createElement("div");
    table.className = "task-table";
    table.appendChild(taskHeader());
    const tasks = stage.tasks || {};
    for (const taskKey of Object.keys(tasks)) {
      table.appendChild(renderTaskRow(progression, stageKey, taskKey));
    }
    body.appendChild(table);

    const add = document.createElement("button");
    add.className = "small-button";
    add.type = "button";
    add.textContent = "Add task";
    add.onclick = () => {
      const key = prompt("Task key, for example IRON_INGOT or STRUCTURE_FORTRESS");
      if (!key) return;
      stage.tasks = stage.tasks || {};
      stage.tasks[key.toUpperCase().replaceAll(" ", "_")] = { amount: 1, "display-name": key, srbp: false };
      progression[stageKey] = stage;
      setStructuredChange("progression", progression);
      renderEditor();
    };
    body.appendChild(add);
    details.appendChild(body);
    wrap.appendChild(details);
  }

  return wrap;
}

function labelWrap(label, input) {
  const wrap = document.createElement("label");
  wrap.className = "label-wrap";
  wrap.appendChild(textNode("span", label));
  wrap.appendChild(input);
  return wrap;
}

function taskHeader() {
  const row = document.createElement("div");
  row.className = "task-row task-head";
  for (const label of ["Task key", "Display name", "Amount", "Scale", ""]) {
    row.appendChild(textNode("span", label));
  }
  return row;
}

function renderTaskRow(progression, stageKey, taskKey) {
  const stage = progression[stageKey];
  const task = stage.tasks[taskKey];
  const row = document.createElement("div");
  row.className = "task-row";

  const key = document.createElement("input");
  key.value = taskKey;
  key.onchange = () => {
    const nextKey = key.value.toUpperCase().replaceAll(" ", "_");
    delete stage.tasks[taskKey];
    stage.tasks[nextKey] = task;
    setStructuredChange("progression", progression);
    renderEditor();
  };

  const name = document.createElement("input");
  name.value = task["display-name"] || taskKey;
  name.oninput = () => {
    task["display-name"] = name.value;
    setStructuredChange("progression", progression);
  };

  const amount = document.createElement("input");
  amount.type = "number";
  amount.min = "0";
  amount.value = task.amount || 0;
  amount.oninput = () => {
    task.amount = Number(amount.value);
    setStructuredChange("progression", progression);
  };

  const srbp = document.createElement("input");
  srbp.type = "checkbox";
  srbp.checked = Boolean(task.srbp);
  srbp.onchange = () => {
    task.srbp = srbp.checked;
    setStructuredChange("progression", progression);
  };

  const remove = document.createElement("button");
  remove.type = "button";
  remove.textContent = "Remove";
  remove.onclick = () => {
    delete stage.tasks[taskKey];
    setStructuredChange("progression", progression);
    renderEditor();
  };

  row.append(key, name, amount, srbp, remove);
  return row;
}

function renderRewardsEditor() {
  const rewards = clone(currentValue("rewards") || {});
  const enabled = Boolean(currentValue("rewards.enabled"));
  const wrap = document.createElement("section");
  wrap.className = "structured";
  if (!enabled) {
    const note = document.createElement("div");
    note.className = "structured-intro inactive-copy";
    note.textContent = "Reward command lists are editable, but they are inactive until rewards.enabled is true.";
    wrap.appendChild(note);
  }
  wrap.appendChild(renderRewardList(rewards, "on-task-complete", "On Task Complete", enabled));
  wrap.appendChild(renderRewardList(rewards, "on-stage-complete", "On Stage Complete", enabled));
  return wrap;
}

function renderRewardList(rewards, key, title, active) {
  const card = document.createElement("article");
  card.className = `structured-card ${active ? "" : "inactive-field"}`;
  card.appendChild(textNode("h3", title));
  const area = document.createElement("textarea");
  area.value = (rewards[key] || []).join("\n");
  area.oninput = () => {
    rewards[key] = area.value.split("\n").map(line => line.trim()).filter(Boolean);
    setStructuredChange("rewards", rewards);
  };
  card.appendChild(area);
  return card;
}

async function mutate(mode) {
  if (!Object.keys(changes).length && mode !== "validate") {
    showMessages("warn", "No changes to send.");
    return;
  }
  try {
    const response = await request(`/api/config/${mode === "saveApply" ? "save-apply" : mode}`, {
      method: "POST",
      body: JSON.stringify({ changes })
    });
    messages.innerHTML = "";
    const validation = response.validation || {};
    appendMessages(validation.valid ? "ok" : "error", validation.valid ? "Validation passed." : validation.errors);
    appendMessages("warn", validation.warnings || []);
    if (response.apply) {
      appendMessages("ok", response.apply.applied || []);
      appendMessages("warn", response.apply.pendingReset || []);
      appendMessages("warn", response.apply.warnings || []);
    }
    if (response.ok && response.values) {
      values = response.values;
      changes = {};
      updatePreview();
      renderTabs();
      renderEditor();
    }
  } catch (error) {
    showMessages("error", error.message);
  }
}

async function load() {
  if (!token) {
    showMessages("error", "Missing token. Open the URL printed in the server console.");
    return;
  }
  try {
    const [config, status] = await Promise.all([
      request("/api/config"),
      request("/api/status")
    ]);
    schema = config.schema;
    values = config.values;
    document.getElementById("server-status").textContent =
      `${status.gamemode} ${status.running ? "running" : "idle"}${status.casualActive ? ", casual active" : ""}`;
    renderTabs();
    renderEditor();
    updatePreview();
  } catch (error) {
    showMessages("error", error.message);
  }
}

document.getElementById("validate-btn").onclick = () => mutate("validate");
document.getElementById("save-btn").onclick = () => mutate("save");
document.getElementById("apply-btn").onclick = () => mutate("apply");
document.getElementById("save-apply-btn").onclick = () => mutate("saveApply");

load();
