/* Speedrun web editor. Talks only to this server's /api endpoints with the per-start access key. */
(function () {
  "use strict";

  const { EditorState, copy, equal } = window.SpeedrunEditorState;
  const LANGUAGES = ["en", "uk", "de", "es", "fr", "pt_BR", "pl", "zh_CN"];
  const SECTIONS = ["Overview", "General", "Casual", "Scanner", "Progression", "Rewards", "Diagnostics", "Guide"];
  const IMPACT_ORDER = ["INSTANT", "RESTART_TASK", "RUN_SENSITIVE", "STARTUP_ONLY"];
  const STRUCTURE_KEYS = ["STRUCTURE_LAVA_POOL", "STRUCTURE_VILLAGE", "STRUCTURE_NETHER_PORTAL", "STRUCTURE_FORTRESS",
    "STRUCTURE_BASTION", "STRUCTURE_END_PORTAL"];
  const COMMON_ITEMS = ["OAK_LOG", "COBBLESTONE", "IRON_INGOT", "BREAD", "COAL", "FLINT", "GOLD_INGOT", "OBSIDIAN",
    "ENDER_PEARL", "BLAZE_ROD", "BLAZE_POWDER", "ENDER_EYE", "STRING", "WHITE_BED", "WATER_BUCKET", "LAVA_BUCKET",
    "DIAMOND", "ARROW", "CRAFTING_TABLE", "FURNACE", "SHIELD", "IRON_PICKAXE", "STONE_PICKAXE"];
  const TOKEN_KEY = "speedrunToken";

  const el = id => document.getElementById(id);
  const editor = el("editor");
  const state = new EditorState();
  let token = "";
  let schema = [];
  let fieldIndex = new Map();
  let metadata = {};
  let migration = {};
  let pendingChanges = [];
  let reloadRequired = false;
  let activeSection = "Overview";
  let activeGuide = "start";
  let searchTerm = "";
  let hideInactive = false;
  let bundle = {};
  let fallback = {};
  let lastFieldStates = {};
  let busy = false;
  /** Paths of number inputs that currently hold an invalid value; saving is blocked meanwhile. */
  const invalidFields = new Set();

  /* ---------- storage helpers (storage can be unavailable) ---------- */
  function store(kind, key, value) {
    try { value === null ? window[kind].removeItem(key) : window[kind].setItem(key, value); } catch (ignored) { /* unavailable */ }
  }
  function recall(kind, key) {
    try { return window[kind].getItem(key); } catch (ignored) { return null; }
  }

  /* ---------- i18n ---------- */
  function lookup(source, key) {
    return key.split(".").reduce((node, part) => (node && Object.hasOwn(node, part) ? node[part] : undefined), source);
  }
  function t(key, vars) {
    let text = lookup(bundle, key);
    if (text === undefined) text = lookup(fallback, key);
    if (text === undefined) return key;
    if (typeof text === "string" && vars) {
      text = text.replace(/\{(\w+)\}/g, (match, name) => (vars[name] !== undefined ? String(vars[name]) : match));
    }
    return text;
  }
  function tOr(key, alternative, vars) {
    const value = t(key, vars);
    return value === key ? alternative : value;
  }
  /** Entries whose keys contain dots (setting paths, group ids) cannot use dotted lookup. */
  function entry(table, key) {
    for (const source of [bundle, fallback]) {
      const value = source && source[table] && source[table][key];
      if (value !== undefined) return value;
    }
    return undefined;
  }
  function fieldText(field, kind) {
    const direct = entry("fields", field.path);
    if (direct && direct[kind]) return direct[kind];
    const profile = /^casual\.start-pre-scan\.profiles\.([A-Z]+)\.(.+)$/.exec(field.path);
    if (profile) {
      const template = entry("profileFields", profile[2]);
      if (template && template[kind]) return template[kind].replace("{profile}", profile[1]);
    }
    return kind === "label" ? field.label : field.description;
  }
  function optionLabel(path, value) {
    const options = entry("options", path);
    return options && options[value] !== undefined ? options[value] : String(value);
  }
  function groupLabel(group, fallbackLabel) {
    return entry("groups", group) || fallbackLabel || group;
  }
  function sectionLabel(section) {
    return tOr(`sections.${section}.label`, section);
  }
  async function loadLanguage(code) {
    const language = LANGUAGES.includes(code) ? code : "en";
    try {
      if (!Object.keys(fallback).length) fallback = await fetchJson("/i18n/en.json");
      bundle = language === "en" ? fallback : await fetchJson(`/i18n/${language}.json`);
    } catch (error) {
      bundle = fallback;
    }
    document.documentElement.lang = language.replace("_", "-");
    store("localStorage", "speedrunUiLanguage", language);
    el("ui-language").value = language;
    applyStaticText();
  }
  async function fetchJson(path) {
    const response = await fetch(path, { cache: "no-store" });
    if (!response.ok) throw new Error(response.statusText);
    return response.json();
  }
  function applyStaticText() {
    document.querySelectorAll("[data-i18n]").forEach(node => {
      const value = t(node.dataset.i18n);
      if (value !== node.dataset.i18n) node.textContent = value;
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach(node => {
      node.placeholder = t(node.dataset.i18nPlaceholder);
    });
    document.querySelectorAll("[data-i18n-aria-label]").forEach(node => {
      node.setAttribute("aria-label", t(node.dataset.i18nAriaLabel));
    });
  }
  function initialLanguage() {
    const saved = recall("localStorage", "speedrunUiLanguage");
    if (saved && LANGUAGES.includes(saved)) return saved;
    const browser = (navigator.language || "en").replace("-", "_");
    if (LANGUAGES.includes(browser)) return browser;
    const short = browser.split("_")[0];
    if (short === "pt") return "pt_BR";
    if (short === "zh") return "zh_CN";
    return LANGUAGES.includes(short) ? short : "en";
  }

  /* ---------- theme ---------- */
  function applyTheme(theme) {
    if (theme === "light" || theme === "dark") document.documentElement.dataset.theme = theme;
    else delete document.documentElement.dataset.theme;
    store("localStorage", "speedrunTheme", theme);
    el("ui-theme").value = theme;
  }

  /* ---------- small DOM helpers ---------- */
  function node(tag, attributes, ...children) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(attributes || {})) {
      if (value === undefined || value === null || value === false) continue;
      if (key === "class") element.className = value;
      else if (key === "text") element.textContent = value;
      else if (key.startsWith("on")) element.addEventListener(key.slice(2), value);
      else if (value === true) element.setAttribute(key, "");
      else element.setAttribute(key, value);
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      element.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return element;
  }
  function badge(text, kind) {
    return node("span", { class: `badge ${kind || ""}`, text });
  }
  function displayValue(value) {
    if (value === undefined || value === null || value === "") return t("ui.notSet");
    if (typeof value === "boolean") return value ? t("ui.yes") : t("ui.no");
    if (Array.isArray(value)) return value.length ? value.join(", ") : "[]";
    if (typeof value === "object") return "{…}";
    return String(value);
  }

  /* ---------- messages ---------- */
  function clearMessages() { el("messages").replaceChildren(); }
  function showMessage(kind, text, action) {
    if (!text) return;
    const item = node("div", { class: `message ${kind}`, role: kind === "error" ? "alert" : null }, node("span", { text }));
    if (action) item.append(node("button", { type: "button", class: "small", text: action.label, onclick: action.run }));
    el("messages").append(item);
  }
  function showMessages(kind, list) { (list || []).forEach(text => showMessage(kind, text)); }

  /* ---------- API ---------- */
  async function api(path, options = {}) {
    let response;
    try {
      response = await fetch(path, {
        ...options,
        cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Speedrun-Token": token, ...(options.headers || {}) }
      });
    } catch (error) {
      setConnection(false);
      throw Object.assign(new Error(t("ui.networkError")), { code: "network" });
    }
    const text = await response.text();
    let data = {};
    try { data = text ? JSON.parse(text) : {}; } catch (ignored) { data = { error: response.statusText }; }
    data.httpStatus = response.status;
    if (response.status === 403) {
      showAuth(t("ui.invalidToken"));
      throw Object.assign(new Error(t("ui.invalidToken")), { code: "token" });
    }
    if (response.status === 429) {
      showAuth(t("ui.throttled"));
      throw Object.assign(new Error(t("ui.throttled")), { code: "throttled" });
    }
    setConnection(true);
    return data;
  }

  function setConnection(online) {
    const status = el("server-status");
    status.classList.toggle("online", online);
    status.classList.toggle("offline", !online);
    el("server-status-text").textContent = online ? t("ui.connected") : t("ui.disconnected");
  }

  function readToken() {
    const hash = new URLSearchParams(location.hash.slice(1));
    const fromHash = hash.get("token");
    if (fromHash) {
      store("sessionStorage", TOKEN_KEY, fromHash);
      history.replaceState(null, "", location.pathname);
      return fromHash;
    }
    return recall("sessionStorage", TOKEN_KEY) || "";
  }

  function showAuth(message) {
    el("auth").hidden = false;
    el("workspace").hidden = true;
    clearMessages();
    if (message) showMessage("error", message);
    el("access-token").focus();
  }

  /* ---------- loading ---------- */
  function takeDocument(response, keepChanges) {
    if (response.schema) {
      schema = response.schema;
      fieldIndex = new Map(schema.map(field => [field.path, field]));
    }
    if (response.metadata) metadata = response.metadata;
    if (response.migration) migration = response.migration;
    pendingChanges = response.pendingChanges || [];
    reloadRequired = Boolean(response.reloadRequired);
    state.accept(response, { keepChanges });
  }

  async function load(keepChanges = false) {
    if (!token) { showAuth(); return; }
    try {
      const response = await api("/api/config");
      if (!response.ok) { showMessage("error", t("ui.requestFailed", { message: response.error || response.httpStatus })); return; }
      el("auth").hidden = true;
      el("workspace").hidden = false;
      takeDocument(response, keepChanges);
      render();
    } catch (error) {
      if (error.code !== "token" && error.code !== "throttled") showMessage("error", error.message);
    }
  }

  async function pollStatus() {
    if (!token || document.hidden || el("workspace").hidden) return;
    try {
      const response = await api("/api/status");
      if (response.ok && response.metadata) {
        metadata = response.metadata;
        if (activeSection === "Overview" && !searchTerm) renderEditor();
      }
    } catch (ignored) { /* connection state already updated */ }
  }

  /* ---------- dependencies ---------- */
  function valueMatches(actual, expected) {
    if (expected === null || expected === undefined) return true;
    if (typeof expected === "boolean") return Boolean(actual) === expected;
    return String(actual).toUpperCase() === String(expected).toUpperCase();
  }
  function dependencyState(field, seen = new Set()) {
    if (!field || !field.parentPath || seen.has(field.path)) return { active: true, reasons: [] };
    seen.add(field.path);
    const parentState = dependencyState(fieldIndex.get(field.parentPath), seen);
    const direct = valueMatches(state.value(field.parentPath), field.parentValue);
    const reasons = [...parentState.reasons];
    if (!direct) reasons.push(entry("dependency", field.parentPath) || field.inactiveReason || field.parentPath);
    return { active: parentState.active && direct, reasons };
  }

  /* ---------- rendering ---------- */
  function render() {
    renderTabs();
    renderEditor();
    renderReview();
  }

  function sectionChangeCount(section) {
    if (section === "Progression") return schema.filter(f => f.section === section && state.hasChange(f.path)).length + (state.hasChange("progression") ? 1 : 0);
    if (section === "Rewards") return state.hasChange("rewards") ? 1 : 0;
    return schema.filter(field => field.section === section && state.hasChange(field.path)).length;
  }

  function renderTabs() {
    const tabs = el("tabs");
    tabs.replaceChildren(...SECTIONS.map(section => {
      const count = sectionChangeCount(section);
      return node("button", {
        type: "button",
        class: `tab ${section === activeSection && !searchTerm ? "active" : ""}`,
        "aria-current": section === activeSection && !searchTerm ? "page" : null,
        onclick: () => {
          activeSection = section;
          searchTerm = "";
          clearMessages();
          el("config-search").value = "";
          el("sidebar").classList.remove("open");
          render();
          el("editor").focus({ preventScroll: true });
          window.scrollTo({ top: 0 });
        }
      }, node("span", { text: sectionLabel(section) }), count ? node("b", { text: count }) : null);
    }));
  }

  function renderEditor() {
    invalidFields.clear(); // inputs are rebuilt from valid state values
    const title = el("section-title");
    const summary = el("section-summary");
    const filterBar = el("filter-bar");
    if (searchTerm) {
      title.textContent = t("ui.searchResults");
      summary.textContent = t("ui.searchResultsFor", { query: searchTerm });
      filterBar.hidden = false;
      renderSearch();
      return;
    }
    title.textContent = sectionLabel(activeSection);
    summary.textContent = tOr(`sections.${activeSection}.summary`, "");
    filterBar.hidden = activeSection === "Overview" || activeSection === "Guide";
    if (activeSection === "Overview") { editor.replaceChildren(renderOverview()); return; }
    if (activeSection === "Guide") { editor.replaceChildren(renderGuide()); return; }

    const sectionFields = schema.filter(field => field.section === activeSection);
    const visible = sectionFields.filter(field => !hideInactive || dependencyState(field).active);
    const content = groupFields(visible).map(renderGroup);
    if (activeSection === "Progression") content.push(renderProgressionEditor());
    if (activeSection === "Rewards") content.push(renderRewardsEditor());
    if (!content.length) content.push(node("div", { class: "empty-state", text: t("ui.noMatches") }));
    editor.replaceChildren(...content);
    el("filter-status").textContent = hideInactive
      ? t("ui.showingFiltered", { visible: visible.length, total: sectionFields.length })
      : t("ui.showingAll");
  }

  function matchesSearch(field, term) {
    const haystack = [fieldText(field, "label"), fieldText(field, "help"), field.path, field.label, field.description,
      groupLabel(field.group, field.groupLabel), sectionLabel(field.section)]
      .concat((field.options || []).map(option => optionLabel(field.path, option)))
      .join(" ").toLowerCase();
    return term.split(/\s+/).every(part => haystack.includes(part));
  }

  function renderSearch() {
    const term = searchTerm.toLowerCase();
    const matches = schema.filter(field => matchesSearch(field, term) && (!hideInactive || dependencyState(field).active));
    const children = [];
    for (const section of SECTIONS) {
      const inSection = matches.filter(field => field.section === section);
      if (!inSection.length) continue;
      children.push(node("p", { class: "result-section", text: sectionLabel(section) }));
      children.push(node("div", { class: "setting-group" }, node("div", { class: "group-body" }, inSection.map(renderField))));
    }
    if (!children.length) children.push(node("div", { class: "empty-state", text: t("ui.noMatches") }));
    editor.replaceChildren(...children);
    el("filter-status").textContent = t("ui.showingFiltered", { visible: matches.length, total: schema.length });
  }

  function groupFields(fields) {
    const groups = new Map();
    for (const field of fields) {
      const key = field.group || "other";
      if (!groups.has(key)) groups.set(key, { key, label: groupLabel(key, field.groupLabel), fields: [] });
      groups.get(key).fields.push(field);
    }
    return [...groups.values()];
  }

  function renderGroup(group) {
    const changed = group.fields.filter(field => state.hasChange(field.path)).length;
    const inactive = group.fields.map(field => dependencyState(field)).filter(result => !result.active);
    const details = node("details", { class: "setting-group" });
    const openKey = `speedrunGroup:${group.key}`;
    details.open = recall("localStorage", openKey) !== "closed";
    details.addEventListener("toggle", () => store("localStorage", openKey, details.open ? null : "closed"));
    details.append(
      node("summary", {},
        node("span", { class: "group-title", text: group.label }),
        node("span", { class: "group-meta" },
          changed ? node("b", { text: `${changed} · ${t("ui.changed")}` }) : null,
          inactive.length === group.fields.length && inactive.length ? node("em", { text: inactive[0].reasons[0] }) : null)),
      node("div", { class: "group-body" }, group.fields.map(renderField)));
    return details;
  }

  function renderField(field) {
    const dependency = dependencyState(field);
    const changed = state.hasChange(field.path);
    const pending = !changed && state.isPending(field.path);
    const value = state.value(field.path);
    const defaultValue = state.defaults[field.path] !== undefined ? state.defaults[field.path] : field.defaultValue;
    const id = `field-${field.path.replace(/[^a-zA-Z0-9_-]/g, "-")}`;
    const errorNode = node("div", { class: "field-error", id: `${id}-error`, hidden: true });

    const meta = node("div", { class: "field-meta" },
      badge(t(`ui.impact.${field.impact}`), field.impact === "INSTANT" ? "ok" : field.impact === "STARTUP_ONLY" ? "info" : "warn"),
      field.danger === "DANGEROUS" ? badge(t("ui.danger"), "danger") : null,
      field.danger === "CAUTION" ? badge(t("ui.caution"), "warn") : null,
      !dependency.active ? badge(t("ui.inactive")) : null,
      changed ? badge(t("ui.changed"), "changed") : null,
      pending ? badge(t("ui.pendingSaved"), "info") : null,
      lastFieldStates[field.path] ? badge(t(`ui.fieldState.${lastFieldStates[field.path]}`), "info") : null,
      node("span", { class: "field-default", text: t("ui.defaultValue", { value: displayOption(field, defaultValue) }) }),
      !equal(value, defaultValue) ? node("button", { type: "button", class: "link", text: t("ui.resetDefault"),
        onclick: () => updateField(field, copy(defaultValue), true) }) : null,
      changed ? node("button", { type: "button", class: "link", text: t("ui.revert"),
        onclick: () => { state.revert(field.path); render(); } }) : null);

    const article = node("article", { class: `field ${dependency.active ? "" : "inactive-field"} ${changed ? "changed-field" : ""}`, id: `${id}-card` },
      node("div", { class: "field-text" },
        node("label", { class: "field-label", for: id, text: fieldText(field, "label") }),
        node("p", { class: "field-help", id: `${id}-help`, text: fieldText(field, "help") }),
        node("span", { class: "field-path", text: field.path })),
      node("div", { class: "field-control" }, inputFor(field, value, id, errorNode)),
      meta,
      dependency.active ? null : node("div", { class: "dependency-note", text: dependency.reasons.join(" ") }),
      errorNode);
    return article;
  }

  function displayOption(field, value) {
    return field.type === "ENUM" ? optionLabel(field.path, value) : displayValue(value);
  }

  function updateField(field, value, rerender) {
    state.set(field.path, value);
    const affectsOthers = schema.some(other => other.parentPath === field.path);
    if (rerender || affectsOthers) renderEditor();
    renderTabs();
    renderReview();
  }

  function inputFor(field, value, id, errorNode) {
    const described = `${id}-help ${id}-error`;
    if (field.type === "BOOLEAN") {
      const input = node("input", { type: "checkbox", id, "aria-describedby": described });
      input.checked = Boolean(value);
      input.addEventListener("change", () => updateField(field, input.checked, true));
      return node("label", { class: "switch" }, input, node("span", { class: "track", "aria-hidden": "true" }),
        node("span", { text: input.checked ? t("ui.yes") : t("ui.no") }));
    }
    if (field.type === "ENUM") {
      const select = node("select", { id, "aria-describedby": described });
      for (const option of field.options || []) {
        select.append(node("option", { value: option, text: optionLabel(field.path, option) }));
      }
      select.value = value;
      select.addEventListener("change", () => updateField(field, select.value, true));
      return select;
    }
    if (field.type === "STRING_LIST") {
      const area = node("textarea", { id, "aria-describedby": described, rows: 3, spellcheck: "false" });
      area.value = Array.isArray(value) ? value.join("\n") : "";
      area.addEventListener("input", () => updateField(field, area.value.split("\n").map(line => line.trim()).filter(Boolean), false));
      return node("div", {}, area, node("span", { class: "field-default", text: t("ui.listHint") }));
    }
    const numeric = field.type === "INTEGER" || field.type === "DECIMAL";
    const input = node("input", {
      id, "aria-describedby": described, type: numeric ? "number" : "text", spellcheck: "false",
      min: field.min, max: field.max, step: field.type === "DECIMAL" ? "0.05" : numeric ? "1" : null,
      inputmode: field.type === "INTEGER" ? "numeric" : field.type === "DECIMAL" ? "decimal" : null
    });
    input.value = value === undefined || value === null ? "" : value;
    input.addEventListener("input", () => {
      if (!numeric) { updateField(field, input.value, false); return; }
      const number = Number(input.value);
      const problem = input.value === "" || !Number.isFinite(number) ? t("ui.invalidNumber")
        : field.type === "INTEGER" && !Number.isInteger(number) ? `${fieldText(field, "label")}: 1, 2, 3…`
        : field.min !== undefined && number < field.min ? `≥ ${field.min}`
        : field.max !== undefined && number > field.max ? `≤ ${field.max}` : "";
      errorNode.hidden = !problem;
      errorNode.textContent = problem;
      input.setAttribute("aria-invalid", problem ? "true" : "false");
      if (problem) {
        invalidFields.add(field.path);
        renderReview();
      } else {
        invalidFields.delete(field.path);
        updateField(field, number, false);
      }
    });
    return input;
  }

  /* ---------- overview ---------- */
  function renderOverview() {
    const platform = metadata.platform || {};
    const capabilities = platform.capabilities || {};
    const run = metadata.run || {};
    const wrap = node("div", { class: "overview-grid" });

    if (pendingChanges.length) {
      wrap.append(node("section", { class: "card" },
        node("h3", { text: t("ui.pendingTitle") }),
        node("p", { text: t("ui.pendingHelp") }),
        node("ul", {}, pendingChanges.map(path => node("li", { text: labelForPath(path) }))),
        node("button", { type: "button", class: "primary", text: t("ui.applySaved"), onclick: () => mutate("apply") })));
    }
    if (reloadRequired) wrap.append(node("section", { class: "card" }, node("p", { text: t("ui.reloadRequired") })));

    wrap.append(node("section", { class: "card" },
      node("h3", { text: t("ui.overview.run") }),
      node("dl", { class: "kv" },
        node("dt", { text: t("ui.overview.run") }), node("dd", { text: run.finished ? t("ui.overview.finished") : run.running ? (run.paused ? t("ui.overview.paused") : t("ui.overview.running")) : t("ui.overview.idle") }),
        node("dt", { text: t("ui.overview.time") }), node("dd", { text: run.time || "00:00:00" }),
        node("dt", { text: t("ui.overview.stage") }), node("dd", { text: run.stage || "—" }),
        node("dt", { text: t("ui.overview.players") }), node("dd", { text: run.players ?? 0 }),
        node("dt", { text: t("ui.overview.gamemode") }), node("dd", { text: optionLabel("settings.gamemode", metadata.gamemode || "NORMAL") }))));

    wrap.append(node("section", { class: "card" },
      node("h3", { text: t("ui.overview.server") }),
      node("dl", { class: "kv" },
        node("dt", { text: t("ui.overview.software") }), node("dd", { text: platform.software || "?" }),
        node("dt", { text: t("ui.overview.minecraft") }), node("dd", { text: platform.minecraftVersion || "?" }),
        node("dt", { text: t("ui.overview.plugin") }), node("dd", { text: metadata.pluginVersion || "?" }),
        node("dt", { text: t("ui.overview.language") }), node("dd", { text: optionLabel("settings.language", state.active["settings.language"] || "en") })),
      node("h3", { text: t("ui.overview.capabilities"), class: "spaced-top" }),
      capability(t("ui.overview.asyncChunks"), capabilities.asyncChunkLoading),
      capability(t("ui.overview.nativeText"), capabilities.nativeAdventure),
      capability(t("ui.overview.actionBar"), capabilities.actionBar),
      capability(t("ui.overview.hiddenNumbers"), capabilities.hiddenScoreNumbers),
      node("div", { class: "capability" }, node("span", { text: t("ui.overview.workers") }), node("span", { text: capabilities.snapshotWorkers ?? "?" }))));

    wrap.append(renderMigration());

    const steps = t("ui.overview.quickStartSteps");
    wrap.append(node("section", { class: "card" },
      node("h3", { text: t("ui.overview.quickStart") }),
      node("ol", { class: "steps" }, (Array.isArray(steps) ? steps : []).map(step => node("li", { text: step }))),
      node("button", { type: "button", class: "secondary", text: t("ui.overview.openGuide"), onclick: () => { activeSection = "Guide"; render(); } })));
    return wrap;
  }

  function capability(label, available) {
    return node("div", { class: "capability" }, node("span", { text: label }),
      node("span", { class: available ? "yes" : "no", text: available ? t("ui.overview.available") : t("ui.overview.unavailable") }));
  }

  function renderMigration() {
    const card = node("section", { class: "card" }, node("h3", { text: t("ui.overview.migrationTitle") }));
    const status = migration.status || "CURRENT";
    const text = {
      CREATED: t("ui.overview.migrationCreated"),
      MIGRATED: t("ui.overview.migrationMigrated", { from: migration.fromPluginVersion, to: migration.toPluginVersion, transferred: migration.transferred ?? 0 }),
      FAILED: t("ui.overview.migrationFailed")
    }[status] || t("ui.overview.migrationCurrent");
    card.append(node("p", { text }));
    if (migration.backupFile) card.append(node("p", { class: "field-help", text: t("ui.overview.migrationBackup", { file: migration.backupFile }) }));
    const changes = migration.changes || [];
    if (changes.length) {
      const table = node("table", { class: "change-table" }, node("tbody", {}, changes.map(change => node("tr", {},
        node("td", {}, badge(tOr(`ui.action.${change.action}`, change.action), change.action === "RESET" || change.action === "INVALID" || change.action === "FAILED" ? "warn" : "info")),
        node("td", {}, node("code", { text: change.path }), node("div", { class: "field-help", text: change.reason }))))));
      card.append(node("details", {}, node("summary", { text: `${t("ui.overview.migrationDetails")} (${changes.length})` }), table));
    }
    return card;
  }

  function labelForPath(path) {
    const field = fieldIndex.get(path);
    if (field) return fieldText(field, "label");
    if (path === "progression") return sectionLabel("Progression");
    if (path === "rewards") return sectionLabel("Rewards");
    return path;
  }

  /* ---------- progression ---------- */
  function renderProgressionEditor() {
    const progression = copy(state.value("progression") || {});
    const commit = () => {
      // Settings under progression.settings are edited as separate fields after this copy was
      // taken; keep their current values instead of the stale copy.
      const current = state.value("progression");
      if (current && typeof current === "object" && current.settings !== undefined) {
        progression.settings = copy(current.settings);
      }
      state.set("progression", progression);
      renderTabs();
      renderReview();
    };
    const wrap = node("section", { class: "structured" }, node("p", { class: "structured-intro", text: t("ui.progression.intro") }));
    const stageKeys = Object.keys(progression).filter(key => key !== "settings").sort();

    const datalist = node("datalist", { id: "task-key-options" }, [...STRUCTURE_KEYS, ...COMMON_ITEMS].map(key => node("option", { value: key })));
    wrap.append(datalist);

    for (const stageKey of stageKeys) {
      const stage = progression[stageKey] && typeof progression[stageKey] === "object" ? progression[stageKey] : {};
      progression[stageKey] = stage;
      stage.tasks = stage.tasks && typeof stage.tasks === "object" ? stage.tasks : {};
      const details = node("details", { class: "structured-card" });
      details.open = true;
      const name = node("input", { type: "text", value: stage["display-name"] || stageKey, spellcheck: "false" });
      name.addEventListener("input", () => { stage["display-name"] = name.value; commit(); });
      const world = node("select", {}, ["NORMAL", "NETHER", "THE_END"].map(option => node("option", { value: option, text: t(`ui.progression.worlds.${option}`) })));
      world.value = (stage.world || "NORMAL").toUpperCase();
      world.addEventListener("change", () => { stage.world = world.value; commit(); });

      const rows = Object.keys(stage.tasks).map(taskKey => renderTaskRow(stage, taskKey, commit));
      details.append(
        node("summary", {}, node("span", { class: "group-title", text: stage["display-name"] || stageKey }),
          node("span", { class: "group-meta" }, node("code", { text: stageKey }), node("span", { text: `${Object.keys(stage.tasks).length} · ${t("ui.progression.tasks")}` }),
            node("span", { text: t(`ui.progression.worlds.${world.value}`) }))),
        node("div", { class: "stage-body" },
          node("div", { class: "stage-fields" },
            node("label", { class: "label-wrap" }, node("span", { text: t("ui.progression.displayName") }), name),
            node("label", { class: "label-wrap" }, node("span", { text: t("ui.progression.world") }), world)),
          node("div", { class: "task-table" },
            node("div", { class: "task-row task-head" },
              node("span", { text: t("ui.progression.taskKey") }), node("span", { text: t("ui.progression.displayName") }),
              node("span", { text: t("ui.progression.amount") }), node("span", { text: t("ui.progression.scale") }), node("span", {})),
            rows.length ? rows : node("p", { class: "field-help", text: t("ui.progression.emptyTasks") })),
          node("p", { class: "field-help", text: t("ui.progression.taskKeyHelp") }),
          node("div", { class: "stage-actions" },
            node("button", { type: "button", class: "small", text: t("ui.progression.addTask"), onclick: () => {
              let key = "NEW_TASK";
              for (let index = 2; Object.hasOwn(stage.tasks, key); index++) key = `NEW_TASK_${index}`;
              stage.tasks[key] = { amount: 1, "display-name": "", srbp: false };
              commit(); renderEditor();
            } }),
            node("button", { type: "button", class: "small ghost", text: t("ui.progression.removeStage"), onclick: () => {
              delete progression[stageKey]; commit(); renderEditor();
            } }))));
      wrap.append(details);
    }

    wrap.append(node("button", { type: "button", class: "secondary", text: t("ui.progression.addStage"), onclick: () => {
      const key = (prompt(t("ui.progression.newStagePrompt")) || "").trim().toUpperCase();
      if (!key) return;
      if (!/^[A-Z0-9_]+$/.test(key)) { showMessage("warn", t("ui.progression.invalidKey")); return; }
      if (Object.hasOwn(progression, key)) { showMessage("warn", t("ui.progression.duplicateKey")); return; }
      progression[key] = { "display-name": key, world: "NORMAL", tasks: {} };
      commit(); renderEditor();
    } }));
    return wrap;
  }

  function renderTaskRow(stage, taskKey, commit) {
    const task = stage.tasks[taskKey] && typeof stage.tasks[taskKey] === "object" ? stage.tasks[taskKey] : {};
    const key = node("input", { type: "text", value: taskKey, list: "task-key-options", spellcheck: "false", pattern: "[A-Za-z0-9_]+", "aria-label": t("ui.progression.taskKey") });
    key.addEventListener("change", () => {
      const next = key.value.trim().toUpperCase().replace(/[^A-Z0-9_]/g, "_");
      if (!next || next === taskKey || Object.hasOwn(stage.tasks, next)) { key.value = taskKey; return; }
      const entries = Object.entries(stage.tasks).map(([name, value]) => (name === taskKey ? [next, value] : [name, value]));
      stage.tasks = Object.fromEntries(entries);
      commit(); renderEditor();
    });
    const name = node("input", { type: "text", value: task["display-name"] || "", placeholder: taskKey, "aria-label": t("ui.progression.displayName") });
    name.addEventListener("input", () => { task["display-name"] = name.value; stage.tasks[taskKey] = task; commit(); });
    const structure = taskKey.startsWith("STRUCTURE_");
    const amount = node("input", { type: "number", min: 0, step: 1, value: structure ? 1 : (task.amount ?? 1), disabled: structure, "aria-label": t("ui.progression.amount") });
    amount.addEventListener("input", () => {
      const number = Number(amount.value);
      if (Number.isInteger(number) && number >= 0) { task.amount = number; stage.tasks[taskKey] = task; commit(); }
    });
    const scaleInput = node("input", { type: "checkbox", disabled: structure, "aria-label": t("ui.progression.scale") });
    scaleInput.checked = Boolean(task.srbp);
    scaleInput.addEventListener("change", () => { task.srbp = scaleInput.checked; stage.tasks[taskKey] = task; commit(); });
    const scale = node("label", { class: "switch" }, scaleInput, node("span", { class: "track", "aria-hidden": "true" }));
    const remove = node("button", { type: "button", class: "small ghost", text: t("ui.remove"), onclick: () => {
      delete stage.tasks[taskKey]; commit(); renderEditor();
    } });
    return node("div", { class: "task-row" }, key, name, amount, scale, remove);
  }

  /* ---------- rewards ---------- */
  function renderRewardsEditor() {
    const rewards = copy(state.value("rewards") || {});
    const enabled = Boolean(state.value("rewards.enabled"));
    const wrap = node("section", { class: "structured" }, node("p", { class: "structured-intro", text: t("ui.rewards.intro") }));
    if (!enabled) wrap.append(node("div", { class: "message warn" }, node("span", { text: t("ui.rewards.inactive") })));
    wrap.append(rewardList(rewards, "on-task-complete", t("ui.rewards.taskList")));
    wrap.append(rewardList(rewards, "on-stage-complete", t("ui.rewards.stageList")));
    return wrap;
  }

  function rewardList(rewards, key, title) {
    const lines = Array.isArray(rewards[key]) ? rewards[key] : [];
    rewards[key] = lines;
    const commit = () => {
      rewards[key] = lines.map(line => line.trim()).filter(Boolean);
      state.set("rewards", { ...copy(state.value("rewards") || {}), [key]: rewards[key] });
      renderTabs(); renderReview();
    };
    const body = node("div", { class: "reward-body" }, node("p", { class: "syntax", text: t("ui.rewards.syntax") }));
    lines.forEach((line, index) => {
      const consoleCommand = !line.trim().startsWith("api:");
      const input = node("input", { type: "text", value: line, spellcheck: "false", "aria-label": title });
      input.addEventListener("input", () => { lines[index] = input.value; commit(); });
      body.append(node("div", { class: `reward-line ${consoleCommand ? "console" : ""}` }, input,
        node("button", { type: "button", class: "small ghost", text: t("ui.remove"), onclick: () => { lines.splice(index, 1); commit(); renderEditor(); } })));
      if (consoleCommand) body.append(node("p", { class: "field-help", text: `⚠ ${t("ui.rewards.consoleWarning")}` }));
    });
    body.append(node("button", { type: "button", text: t("ui.addItem"), class: "small spaced-top", onclick: () => {
      lines.push("api:sound minecraft:entity.experience_orb.pickup 1.0 1.0"); commit(); renderEditor();
    } }));
    const details = node("details", { class: "structured-card" }, node("summary", {}, node("span", { class: "group-title", text: title }), node("span", { class: "group-meta", text: String(lines.length) })), body);
    details.open = true;
    return details;
  }

  /* ---------- guide ---------- */
  function renderGuide() {
    const articles = Array.isArray(bundle.guide) ? bundle.guide : fallback.guide || [];
    const article = articles.find(item => item.id === activeGuide) || articles[0];
    const toc = node("nav", { class: "guide-toc", "aria-label": sectionLabel("Guide") }, articles.map(item => node("button", {
      type: "button", class: item === article ? "active" : "", text: item.title,
      onclick: () => { activeGuide = item.id; renderEditor(); }
    })));
    const body = node("article", { class: "card guide-article" });
    if (article) {
      body.append(node("h3", { text: article.title }));
      for (const block of article.blocks || []) {
        if (block.h) body.append(node("h4", { text: block.h }));
        if (block.p) body.append(node("p", { text: block.p }));
        if (block.ul) body.append(node("ul", {}, block.ul.map(item => node("li", { text: item }))));
        if (block.ol) body.append(node("ol", {}, block.ol.map(item => node("li", { text: item }))));
      }
    }
    return node("div", { class: "guide" }, toc, body);
  }

  /* ---------- review panel ---------- */
  function impactFor(path) {
    if (path === "progression") return "RUN_SENSITIVE";
    const field = fieldIndex.get(path);
    return field ? field.impact : "INSTANT";
  }

  function renderReview() {
    const patch = state.patch();
    const keys = Object.keys(patch).sort((a, b) => IMPACT_ORDER.indexOf(impactFor(a)) - IMPACT_ORDER.indexOf(impactFor(b)));
    const dirty = keys.length > 0;
    const pill = el("dirty-pill");
    pill.textContent = dirty ? t("ui.statusDirty") : t("ui.statusSaved");
    pill.classList.toggle("dirty", dirty);
    el("change-count").textContent = dirty ? t("ui.changesCount", { count: keys.length }) : t("ui.noChanges");
    el("change-list").replaceChildren(...keys.map(path => {
      const field = fieldIndex.get(path);
      const before = state.saved[path];
      const after = patch[path];
      const value = field ? t("ui.beforeAfter", { before: displayOption(field, before), after: displayOption(field, after) }) : "";
      return node("li", {},
        node("span", { class: "change-name", text: labelForPath(path) }),
        value ? node("span", { class: "change-value", text: value }) : null,
        impactFor(path) !== "INSTANT" ? node("span", { class: "change-impact", text: t(`ui.impact.${impactFor(path)}`) }) : null,
        node("button", { type: "button", class: "small ghost", text: t("ui.revert"), onclick: () => { state.revert(path); render(); } }));
    }));
    el("preview-json").textContent = JSON.stringify(patch, null, 2);
    const blocked = invalidFields.size > 0;
    el("save-btn").disabled = !dirty || busy || blocked;
    el("save-apply-btn").disabled = !dirty || busy || blocked;
    el("apply-btn").disabled = (!dirty && !pendingChanges.length) || busy || blocked;
    el("discard-btn").disabled = !dirty || busy;
    el("validate-btn").disabled = busy;
  }

  /* ---------- mutations ---------- */
  async function mutate(mode) {
    const changes = state.patch();
    if (!Object.keys(changes).length && mode !== "validate" && !(mode === "apply" && pendingChanges.length)) {
      clearMessages();
      showMessage("warn", t("ui.nothingToSend"));
      return;
    }
    busy = true;
    renderReview();
    try {
      const endpoint = mode === "saveApply" ? "save-apply" : mode;
      const response = await api(`/api/config/${endpoint}`, { method: "POST", body: JSON.stringify({ changes, revision: state.revision }) });
      clearMessages();
      if (response.httpStatus === 409 || response.errorCode === "conflict") {
        showMessage("warn", t("ui.conflict"), { label: t("ui.reloadKeepEdits"), run: () => load(true) });
        return;
      }
      const validation = response.validation || {};
      if (validation.errors && validation.errors.length) {
        showMessages("error", validation.errors);
      } else if (!response.ok) {
        showMessage("error", response.error || t("ui.requestFailed", { message: response.httpStatus }));
      }
      showMessages("warn", validation.warnings);
      if (!response.ok) {
        if (response.schema) { takeDocument(response, true); render(); }
        return;
      }
      if (mode === "validate") {
        showMessage("ok", t("ui.validationPassed"));
        return;
      }
      lastFieldStates = response.fieldStates || {};
      takeDocument(response, mode === "apply");
      showMessage("ok", mode === "save" ? t("ui.saved") : mode === "apply" ? t("ui.appliedNow") : t("ui.savedAndApplied"));
      if (response.apply) {
        showMessages("warn", response.apply.pendingReset);
        showMessages("warn", response.apply.warnings);
      }
      render();
    } catch (error) {
      if (error.code !== "token" && error.code !== "throttled") showMessage("error", error.message);
    } finally {
      busy = false;
      renderReview();
    }
  }

  /* ---------- wiring ---------- */
  function wire() {
    el("save-apply-btn").addEventListener("click", () => mutate("saveApply"));
    el("save-btn").addEventListener("click", () => mutate("save"));
    el("apply-btn").addEventListener("click", () => mutate("apply"));
    el("validate-btn").addEventListener("click", () => mutate("validate"));
    el("discard-btn").addEventListener("click", () => { state.discard(); lastFieldStates = {}; render(); });
    el("refresh-btn").addEventListener("click", () => load(true));
    el("hide-inactive").addEventListener("change", event => { hideInactive = event.target.checked; renderEditor(); });
    let searchTimer;
    el("config-search").addEventListener("input", event => {
      clearTimeout(searchTimer);
      searchTimer = setTimeout(() => { searchTerm = event.target.value.trim(); render(); }, 120);
    });
    el("ui-language").addEventListener("change", async event => { await loadLanguage(event.target.value); render(); setConnection(!el("workspace").hidden); });
    el("ui-theme").addEventListener("change", event => applyTheme(event.target.value));
    el("menu-toggle").addEventListener("click", () => {
      const open = el("sidebar").classList.toggle("open");
      el("menu-toggle").setAttribute("aria-expanded", String(open));
    });
    el("auth-form").addEventListener("submit", event => {
      event.preventDefault();
      const raw = el("access-token").value.trim();
      const match = /token=([0-9a-fA-F]+)/.exec(raw);
      token = match ? match[1] : raw;
      el("access-token").value = "";
      store("sessionStorage", TOKEN_KEY, token);
      clearMessages();
      load();
    });
    document.addEventListener("keydown", event => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
        event.preventDefault();
        if (Object.keys(state.patch()).length && !busy) mutate("saveApply");
      }
    });
    window.addEventListener("beforeunload", event => {
      if (Object.keys(state.patch()).length) { event.preventDefault(); event.returnValue = t("ui.unsavedLeave"); }
    });
    // Opening a new access link in an already open tab only changes the fragment.
    window.addEventListener("hashchange", () => {
      if (new URLSearchParams(location.hash.slice(1)).get("token")) {
        token = readToken();
        clearMessages();
        load(true);
      }
    });
    setInterval(pollStatus, 15000);
  }

  async function start() {
    const language = initialLanguage();
    el("ui-language").replaceChildren(...LANGUAGES.map(code => node("option", { value: code, text: optionLabel("settings.language", code) })));
    applyTheme(recall("localStorage", "speedrunTheme") || "auto");
    await loadLanguage(language);
    el("ui-language").replaceChildren(...LANGUAGES.map(code => node("option", { value: code, text: optionLabel("settings.language", code) })));
    el("ui-language").value = language;
    wire();
    token = readToken();
    if (!token) showAuth();
    else load();
  }

  start();
})();
