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
}

function setStructuredChange(path, value) {
  if (sameValue(value, values[path])) {
    delete changes[path];
  } else {
    changes[path] = clone(value);
  }
  updatePreview();
}

function updatePreview() {
  const count = Object.keys(changes).length;
  dirtyPill.textContent = count ? "Unsaved" : "Clean";
  dirtyPill.classList.toggle("dirty", count > 0);
  changeCount.textContent = `${count} ${count === 1 ? "change" : "changes"}`;
  preview.textContent = JSON.stringify(changes, null, 2);
}

function showMessages(kind, items) {
  messages.innerHTML = "";
  const list = Array.isArray(items) ? items : [items];
  for (const item of list.filter(Boolean)) {
    const node = document.createElement("div");
    node.className = `message ${kind}`;
    node.textContent = item;
    messages.appendChild(node);
  }
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
    const button = document.createElement("button");
    button.className = `tab ${section === activeSection ? "active" : ""}`;
    button.type = "button";
    button.textContent = section;
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
  for (const field of fields) {
    editor.appendChild(renderField(field));
  }

  if (activeSection === "Progression") {
    editor.appendChild(renderProgressionEditor());
  }
  if (activeSection === "Rewards") {
    editor.appendChild(renderRewardsEditor());
  }
}

function sectionSummary(section) {
  return {
    General: "Core runtime behavior, language, display, and logging.",
    Casual: "Compass, waypoints, tab coordinates, and Nether assistance.",
    Scanner: "Structure detection, pre-scan profiles, and performance caps.",
    Progression: "Task display settings plus structured stage and task editing.",
    Rewards: "Reward enablement and command lists for task or stage completion.",
    Diagnostics: "Trace and block logging controls."
  }[section] || "";
}

function renderField(field) {
  const card = document.createElement("article");
  card.className = "field";

  const header = document.createElement("div");
  header.className = "field-header";
  const title = document.createElement("div");
  title.innerHTML = `<label>${field.label}</label><br><code>${field.path}</code>`;
  const badges = document.createElement("div");
  badges.className = "badges";
  badges.appendChild(badge(field.impact, field.impact === "RUN_SENSITIVE" || field.impact === "STARTUP_ONLY" ? "warn" : ""));
  if (field.danger === "DANGEROUS") badges.appendChild(badge("danger", "danger"));
  if (field.danger === "CAUTION") badges.appendChild(badge("caution", "warn"));
  header.append(title, badges);

  const input = inputForField(field);
  const hint = document.createElement("div");
  hint.className = "hint";
  hint.textContent = field.description;

  card.append(header, input, hint);
  return card;
}

function badge(text, kind) {
  const node = document.createElement("span");
  node.className = `badge ${kind || ""}`;
  node.textContent = text.toLowerCase().replaceAll("_", " ");
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

  for (const stageKey of Object.keys(progression).filter(key => key !== "settings")) {
    const stage = progression[stageKey] || {};
    const card = document.createElement("article");
    card.className = "structured-card";
    card.innerHTML = `<h3>${stageKey}</h3>`;

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

    card.append(name, world);
    const tasks = stage.tasks || {};
    for (const taskKey of Object.keys(tasks)) {
      card.appendChild(renderTaskRow(progression, stageKey, taskKey));
    }
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
    card.appendChild(add);
    wrap.appendChild(card);
  }

  return wrap;
}

function renderTaskRow(progression, stageKey, taskKey) {
  const stage = progression[stageKey];
  const task = stage.tasks[taskKey];
  const row = document.createElement("div");
  row.className = "row";

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
  const wrap = document.createElement("section");
  wrap.className = "structured";
  wrap.appendChild(renderRewardList(rewards, "on-task-complete", "On Task Complete"));
  wrap.appendChild(renderRewardList(rewards, "on-stage-complete", "On Stage Complete"));
  return wrap;
}

function renderRewardList(rewards, key, title) {
  const card = document.createElement("article");
  card.className = "structured-card";
  card.innerHTML = `<h3>${title}</h3>`;
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
