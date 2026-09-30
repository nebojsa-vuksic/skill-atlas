// Skill Atlas web view: calls GET /api/scan and renders the result (spec section 5.4).
// Repository content is inserted with textContent. The one exception is content_html,
// which the server renders from Markdown with raw HTML escaped and unsafe links removed.
"use strict";

const $ = (id) => document.getElementById(id);

const MIN_PANE = 240;
const DEFAULT_LEFT_FRACTION = 0.38;
const WIDTH_KEY = "skill-atlas.left-fraction";

const SNIPPET_CONTEXT = 40;

let current = null; // { result, selected, shown, visible }
let activeTab = "rendered";

function show(element, visible) {
  element.hidden = !visible;
}

let busyTimer = null;

// Shows the status line with a running seconds counter, so a long clone never looks stuck.
function setBusy(busy, message) {
  $("scan-button").disabled = busy;
  $("url").disabled = busy;
  clearInterval(busyTimer);
  show($("status"), busy);
  if (!busy) return;
  const started = Date.now();
  const update = () => {
    const seconds = Math.floor((Date.now() - started) / 1000);
    $("status-text").textContent = message + " " + seconds + "s" +
      (seconds >= 10 ? " (large repositories can take a minute)" : "");
  };
  update();
  busyTimer = setInterval(update, 1000);
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function badges(skill) {
  const nodes = [];
  if (skill.shipped) nodes.push(element("span", "shipped", "◆ shipped in product"));
  for (const warning of skill.warnings) nodes.push(element("span", "warning", "⚠ " + warning));
  return nodes;
}

function paths(skill) {
  return [
    element("div", "skill-path", skill.path),
    ...skill.also_at.map((copy) => element("div", "skill-path also", "also in " + copy)),
  ];
}

function githubUrl(repository, path) {
  return "https://github.com/" + repository.name + "/blob/" + repository.commit + "/" +
    (path === "." ? "" : path + "/");
}

// ---- URL state: ?url=<repository>&skill=<path>&q=<filter> ----

function writeLocation(url, skillPath, query = "") {
  const params = new URLSearchParams();
  params.set("url", url);
  if (skillPath) params.set("skill", skillPath);
  if (query.trim()) params.set("q", query);
  history.replaceState(null, "", "?" + params.toString());
}

function updateLocation() {
  const shown = current && current.shown >= 0 ? current.result.skills[current.shown].path : null;
  writeLocation($("url").value.trim(), shown, $("filter").value);
}

// ---- Left pane: the skill list ----

// Small icons at the end of an item's name line; the full labels are in the right pane.
function icons(skill) {
  const nodes = [];
  const icon = (className, text, title) => {
    const node = element("span", "icon " + className, text);
    node.title = title;
    nodes.push(node);
  };
  if (skill.shipped) icon("shipped-icon", "◆", "shipped in product");
  if (skill.warnings.length > 0) icon("warning-icon", "⚠", skill.warnings.join("\n"));
  if (skill.also_at.length > 0) {
    icon("copies-icon", "⧉ " + skill.also_at.length, skill.also_at.map((copy) => "also in " + copy).join("\n"));
  }
  return nodes;
}

function renderSkillItem(skill, index) {
  const item = element("button", "skill");
  item.type = "button";
  item.setAttribute("role", "option");
  item.setAttribute("aria-selected", "false");
  item.tabIndex = -1;
  item.dataset.path = skill.path;

  const head = element("span", "skill-head");
  head.append(element("span", "skill-name", skill.name), ...icons(skill));

  const description = element("span", "skill-description");
  if (skill.description && skill.description !== skill.short_description) {
    description.title = skill.description;
  }
  item.append(head, description);
  fillItem(item, skill, []);
  item.addEventListener("click", () => select(index, { focus: true }));
  return item;
}

// ---- Filter (spec section 5.5) ----

function queryWords(query) {
  return query.toLowerCase().split(/\s+/).filter(Boolean);
}

function matches(skill, words) {
  const name = skill.name.toLowerCase();
  const description = (skill.description || "").toLowerCase();
  return words.every((word) => name.includes(word) || description.includes(word));
}

// Every [start, end) range where one of the words occurs in text, merged and sorted.
function matchRanges(text, words) {
  const lower = text.toLowerCase();
  const ranges = [];
  for (const word of words) {
    for (let at = lower.indexOf(word); at >= 0; at = lower.indexOf(word, at + 1)) {
      ranges.push([at, at + word.length]);
    }
  }
  ranges.sort((a, b) => a[0] - b[0]);
  const merged = [];
  for (const range of ranges) {
    const last = merged[merged.length - 1];
    if (last && range[0] <= last[1]) last[1] = Math.max(last[1], range[1]);
    else merged.push(range);
  }
  return merged;
}

// Fills node with text, wrapping the matched words in <mark>. Built from text nodes only.
function setHighlighted(node, text, words) {
  const parts = [];
  let at = 0;
  for (const [start, end] of matchRanges(text, words)) {
    if (start > at) parts.push(document.createTextNode(text.slice(at, start)));
    const mark = document.createElement("mark");
    mark.textContent = text.slice(start, end);
    parts.push(mark);
    at = end;
  }
  if (at < text.length) parts.push(document.createTextNode(text.slice(at)));
  node.replaceChildren(...parts);
}

// Up to SNIPPET_CONTEXT characters on each side of text[start, end), cut at word boundaries.
function snippet(text, start, end) {
  let from = Math.max(0, start - SNIPPET_CONTEXT);
  if (from > 0 && text[from - 1] !== " ") {
    const space = text.indexOf(" ", from);
    from = space >= 0 && space < start ? space + 1 : start;
  }
  let to = Math.min(text.length, end + SNIPPET_CONTEXT);
  if (to < text.length && text[to] !== " ") {
    const space = text.lastIndexOf(" ", to);
    to = space >= end ? space : end;
  }
  return (from > 0 ? "…" : "") + text.slice(from, to).trim() + (to < text.length ? "…" : "");
}

// The item's description line: the shortened description, or a snippet around the first
// match when a word only occurs past the part that the shortened description shows.
function descriptionLine(skill, words) {
  if (!skill.short_description) return null;
  const shown = skill.short_description.replace(/…$/, "").toLowerCase();
  const full = skill.description.replace(/\s+/g, " ").trim();
  const lower = full.toLowerCase();
  let first = -1;
  let length = 0;
  for (const word of words) {
    const at = lower.indexOf(word);
    if (at < 0 || shown.includes(word)) continue;
    if (first < 0 || at < first) {
      first = at;
      length = word.length;
    }
  }
  return first < 0 ? skill.short_description : snippet(full, first, first + length);
}

function fillItem(item, skill, words) {
  setHighlighted(item.querySelector(".skill-name"), skill.name, words);
  const description = item.querySelector(".skill-description");
  const line = descriptionLine(skill, words);
  description.classList.toggle("none", line === null);
  if (line === null) description.textContent = "(no description)";
  else setHighlighted(description, line, words);
}

function applyFilter() {
  if (!current) return;
  const query = $("filter").value;
  const words = queryWords(query);
  const skills = current.result.skills;
  current.visible = [];
  items().forEach((item, i) => {
    const visible = matches(skills[i], words);
    item.hidden = !visible;
    if (visible) {
      current.visible.push(i);
      fillItem(item, skills[i], words);
    }
  });

  const count = $("filter-count");
  count.textContent = current.visible.length + " of " + skills.length;
  count.classList.toggle("active", words.length > 0);
  $("no-match").textContent = 'No skills match "' + query.trim() + '".';
  show($("no-match"), current.visible.length === 0);

  if (current.visible.length === 0) {
    showNothing();
  } else if (!current.visible.includes(current.selected)) {
    select(current.visible[0]);
  } else if (current.shown !== current.selected) {
    select(current.selected);
  }
  updateLocation();
}

// Sets the filter, e.g. to clear it before selecting a skill that it hides.
function setFilter(query) {
  $("filter").value = query;
  applyFilter();
}

$("filter").addEventListener("input", applyFilter);

$("filter").addEventListener("keydown", (event) => {
  if (event.key === "Escape") {
    event.preventDefault();
    setFilter("");
  } else if (event.key === "ArrowDown" && current && current.shown >= 0) {
    event.preventDefault();
    items()[current.shown].focus();
  }
});

function isTextField(target) {
  return target instanceof HTMLElement &&
    (target.isContentEditable || target.matches("input, textarea, select"));
}

document.addEventListener("keydown", (event) => {
  if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey) return;
  if (isTextField(event.target) || !current || $("split").hidden) return;
  event.preventDefault();
  $("filter").focus();
});

function items() {
  return Array.from($("skills").querySelectorAll('[role="option"]'));
}

$("skills").addEventListener("keydown", (event) => {
  if (!current || (event.key !== "ArrowDown" && event.key !== "ArrowUp")) return;
  event.preventDefault();
  const visible = current.visible;
  if (visible.length === 0) return;
  const step = event.key === "ArrowDown" ? 1 : -1;
  const at = visible.indexOf(current.selected);
  const next = at < 0 ? 0 : Math.min(Math.max(at + step, 0), visible.length - 1);
  select(visible[next], { focus: true });
});

// ---- Right pane: the selected skill ----

// Points relative links in rendered Markdown at the skill's directory on GitHub, and
// turns images, which the Content-Security-Policy won't load from other hosts, into links.
function fixLinks(container, base) {
  for (const link of container.querySelectorAll("a[href]")) {
    const href = link.getAttribute("href");
    if (href.startsWith("#")) continue;
    if (!/^(https?:|mailto:)/i.test(href)) link.href = new URL(href, base).toString();
    link.target = "_blank";
    link.rel = "noopener noreferrer";
  }
  for (const image of container.querySelectorAll("img")) {
    const link = element("a", "image-link", "🖼 " + (image.getAttribute("alt") || "image"));
    link.href = new URL(image.getAttribute("src") || "", base).toString();
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    image.replaceWith(link);
  }
}

function renderDetail(skill, repository) {
  const name = $("detail-name");
  name.textContent = skill.name;
  name.append(...badges(skill));

  const description = $("detail-description");
  description.textContent = skill.description || "(no description)";
  description.classList.toggle("none", !skill.description);

  $("detail-paths").replaceChildren(...paths(skill));
  const base = githubUrl(repository, skill.path);
  $("detail-github").href = base + "SKILL.md";

  const hasContent = skill.content !== null;
  const rendered = $("rendered");
  // Server-rendered and sanitized (see SkillMarkdown.kt); the CSP also blocks inline scripts.
  rendered.innerHTML = skill.content_html || "";
  fixLinks(rendered, base);
  $("raw-code").textContent = skill.content || "";
  show($("no-content"), !hasContent);
  document.querySelector(".tabs").hidden = !hasContent;
  showTab(activeTab, hasContent);
  $("detail").scrollTop = 0;
}

function showTab(tab, hasContent = true) {
  activeTab = tab;
  $("tab-rendered").setAttribute("aria-selected", String(tab === "rendered"));
  $("tab-raw").setAttribute("aria-selected", String(tab === "raw"));
  show($("rendered"), hasContent && tab === "rendered");
  show($("raw"), hasContent && tab === "raw");
}

$("tab-rendered").addEventListener("click", () => showTab("rendered"));
$("tab-raw").addEventListener("click", () => showTab("raw"));

function markSelected(index) {
  items().forEach((item, i) => {
    const selected = i === index;
    item.setAttribute("aria-selected", String(selected));
    item.tabIndex = selected ? 0 : -1;
  });
}

// Nothing matches the filter: no item is selected and the right pane is empty. The
// selection is remembered, so clearing the filter brings it back.
function showNothing() {
  current.shown = -1;
  markSelected(-1);
  show($("detail"), false);
}

function select(index, { focus = false } = {}) {
  if (!current || index < 0 || index >= current.result.skills.length) return;
  current.selected = index;
  current.shown = index;
  markSelected(index);
  const all = items();
  all[index].scrollIntoView({ block: "nearest" });
  if (focus) all[index].focus({ preventScroll: true });

  show($("detail"), true);
  renderDetail(current.result.skills[index], current.result.repository);
  updateLocation();
  if (focus && window.matchMedia("(max-width: 759px)").matches) {
    $("detail").scrollIntoView({ block: "start", behavior: "smooth" });
  }
}

// ---- Divider ----

function readFraction() {
  try {
    const stored = parseFloat(localStorage.getItem(WIDTH_KEY));
    return stored > 0 && stored < 1 ? stored : DEFAULT_LEFT_FRACTION;
  } catch (_) {
    return DEFAULT_LEFT_FRACTION;
  }
}

function paneWidth() {
  return $("split").clientWidth - $("divider").offsetWidth;
}

function setLeftWidth(pixels, { save = true } = {}) {
  const total = paneWidth();
  if (total <= 0) return;
  const clamped = Math.min(Math.max(pixels, MIN_PANE), Math.max(MIN_PANE, total - MIN_PANE));
  $("split").style.setProperty("--left", clamped + "px");
  $("divider").setAttribute("aria-valuenow", String(Math.round((clamped / total) * 100)));
  if (!save) return;
  try {
    localStorage.setItem(WIDTH_KEY, String(clamped / total));
  } catch (_) {
    // Storage may be unavailable; the width just won't be remembered.
  }
}

function applyStoredWidth() {
  setLeftWidth(paneWidth() * readFraction(), { save: false });
}

$("divider").addEventListener("pointerdown", (event) => {
  const divider = $("divider");
  divider.setPointerCapture(event.pointerId);
  divider.classList.add("dragging");
  const left = $("split").getBoundingClientRect().left;
  const move = (e) => setLeftWidth(e.clientX - left);
  const up = () => {
    divider.classList.remove("dragging");
    divider.removeEventListener("pointermove", move);
    divider.removeEventListener("pointerup", up);
  };
  divider.addEventListener("pointermove", move);
  divider.addEventListener("pointerup", up);
});

$("divider").addEventListener("keydown", (event) => {
  if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
  event.preventDefault();
  const width = $("list-pane").getBoundingClientRect().width;
  setLeftWidth(width + (event.key === "ArrowRight" ? 24 : -24));
});

window.addEventListener("resize", () => {
  if (current && current.result.skills.length > 0) applyStoredWidth();
});

// ---- Result ----

function render(result, preferredPath, query) {
  const repository = result.repository;
  const name = $("repo-name");
  name.textContent = repository.name;
  name.href = "https://github.com/" + repository.name;

  const description = $("repo-description");
  description.textContent = repository.description || "(none)";
  description.classList.toggle("none", !repository.description);

  $("commit").textContent = repository.commit;
  $("branch").textContent = repository.branch;

  const count = result.skills.length;
  const heading = $("skill-count");
  heading.textContent = count === 0 ? "No skills found." : count + (count === 1 ? " skill found" : " skills found");
  heading.classList.toggle("empty", count === 0);

  $("skills").replaceChildren(...result.skills.map(renderSkillItem));
  show($("split"), count > 0);

  const ignored = result.ignored;
  $("ignored-heading").textContent = "Ignored " + ignored.length + " test " +
    (ignored.length === 1 ? "fixture" : "fixtures") + " (not skills)";
  $("ignored-list").replaceChildren(...ignored.map((entry) => element("li", "skill-path", entry.path)));
  show($("ignored"), ignored.length > 0);

  show($("result"), true);
  const preferred = result.skills.findIndex((skill) => skill.path === preferredPath);
  current = { result, selected: Math.max(preferred, 0), shown: -1, visible: [] };
  $("filter").value = query || "";
  if (count > 0) {
    applyStoredWidth();
    applyFilter();
  }
}

async function scan(url, preferredPath, query) {
  document.body.dataset.state = "scanning";
  show($("error"), false);
  show($("result"), false);
  current = null;
  setBusy(true, "Scanning " + url + "…");
  try {
    const response = await fetch("/api/scan?url=" + encodeURIComponent(url));
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
    setBusy(false);
    render(body, preferredPath, query);
  } catch (error) {
    $("error").textContent = "error: " + error.message;
    show($("error"), true);
  } finally {
    setBusy(false);
    document.body.dataset.state = "idle";
  }
}

$("scan-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const url = $("url").value.trim();
  if (!url) return;
  writeLocation(url, null);
  scan(url, null, "");
});

// A link like /?url=https://github.com/owner/repo&skill=skills/pdf&q=test starts a scan right away.
const initial = new URLSearchParams(location.search);
if (initial.get("url")) {
  $("url").value = initial.get("url");
  scan(initial.get("url"), initial.get("skill"), initial.get("q"));
} else {
  document.body.dataset.state = "idle";
}
