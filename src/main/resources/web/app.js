// Skill Atlas web view: calls GET /api/scan and renders the result (spec section 5.4).
// Repository content is inserted with textContent. The one exception is content_html,
// which the server renders from Markdown with raw HTML escaped and unsafe links removed.
"use strict";

const $ = (id) => document.getElementById(id);

const MIN_PANE = 240;
const DEFAULT_LEFT_FRACTION = 0.38;
const WIDTH_KEY = "skill-atlas.left-fraction";

let current = null; // { result, selected }
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

// ---- URL state: ?url=<repository>&skill=<path> ----

function writeLocation(url, skillPath) {
  const params = new URLSearchParams();
  params.set("url", url);
  if (skillPath) params.set("skill", skillPath);
  history.replaceState(null, "", "?" + params.toString());
}

// ---- Left pane: the skill list ----

function renderSkillItem(skill, index) {
  const item = element("button", "skill");
  item.type = "button";
  item.setAttribute("role", "option");
  item.setAttribute("aria-selected", "false");
  item.tabIndex = -1;
  item.dataset.path = skill.path;

  const name = element("span", "skill-name", skill.name);
  name.append(...badges(skill));
  item.append(name);

  const description = skill.short_description
    ? element("span", "skill-description", skill.short_description)
    : element("span", "skill-description none", "(no description)");
  if (skill.description && skill.description !== skill.short_description) {
    description.title = skill.description;
  }
  item.append(description, ...paths(skill));
  item.addEventListener("click", () => select(index, { focus: true }));
  return item;
}

function items() {
  return Array.from($("skills").querySelectorAll('[role="option"]'));
}

$("skills").addEventListener("keydown", (event) => {
  if (!current || (event.key !== "ArrowDown" && event.key !== "ArrowUp")) return;
  event.preventDefault();
  const step = event.key === "ArrowDown" ? 1 : -1;
  const next = Math.min(Math.max(current.selected + step, 0), current.result.skills.length - 1);
  select(next, { focus: true });
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

// Lists the skills most like this one (spec section 5.6); the server computes the scores.
function renderSimilar(skill) {
  const rows = skill.similar.map((similar) => {
    const row = element("button", "similar-row");
    row.type = "button";
    row.dataset.path = similar.path;
    row.title = "Similarity " + similar.score + " %";
    const bar = element("span", "similar-bar");
    bar.setAttribute("aria-hidden", "true");
    const fill = element("span", "similar-fill");
    fill.style.width = similar.score + "%";
    bar.append(fill);
    row.append(
      element("span", "similar-name", similar.name),
      element("span", "similar-path", similar.path),
      bar,
      element("span", "similar-score", similar.score + " %"),
    );
    row.addEventListener("click", () => selectPath(similar.path));
    return row;
  });
  $("similar-list").replaceChildren(...rows);
  show($("no-similar"), rows.length === 0);
}

// Clears the filter (spec section 5.5), if there is one, so that every skill is listed again.
function clearFilter() {
  const field = $("filter");
  if (!field || field.value === "") return;
  field.value = "";
  field.dispatchEvent(new Event("input", { bubbles: true }));
}

// Selects the skill at a path, clearing the filter first if it hides that skill.
function selectPath(path) {
  if (!current) return;
  const index = current.result.skills.findIndex((skill) => skill.path === path);
  if (index < 0) return;
  const item = items().find((option) => option.dataset.path === path);
  if (!item || item.getClientRects().length === 0) clearFilter();
  select(index, { focus: true });
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
  renderSimilar(skill);

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

function select(index, { focus = false } = {}) {
  if (!current || index < 0 || index >= current.result.skills.length) return;
  current.selected = index;
  const all = items();
  all.forEach((item, i) => {
    const selected = i === index;
    item.setAttribute("aria-selected", String(selected));
    item.tabIndex = selected ? 0 : -1;
  });
  all[index].scrollIntoView({ block: "nearest" });
  if (focus) all[index].focus({ preventScroll: true });

  const skill = current.result.skills[index];
  renderDetail(skill, current.result.repository);
  writeLocation($("url").value.trim(), skill.path);
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
  const width = $("skills").getBoundingClientRect().width;
  setLeftWidth(width + (event.key === "ArrowRight" ? 24 : -24));
});

window.addEventListener("resize", () => {
  if (current && current.result.skills.length > 0) applyStoredWidth();
});

// ---- Result ----

function render(result, preferredPath) {
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
  current = { result, selected: -1 };
  if (count > 0) {
    applyStoredWidth();
    const preferred = result.skills.findIndex((skill) => skill.path === preferredPath);
    select(preferred >= 0 ? preferred : 0);
  }
}

async function scan(url, preferredPath) {
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
    render(body, preferredPath);
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
  scan(url, null);
});

// A link like /?url=https://github.com/owner/repo&skill=skills/pdf starts a scan right away.
const initial = new URLSearchParams(location.search);
if (initial.get("url")) {
  $("url").value = initial.get("url");
  scan(initial.get("url"), initial.get("skill"));
} else {
  document.body.dataset.state = "idle";
}
