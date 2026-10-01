// Skill Atlas web view: calls GET /api/scans and renders the result (spec sections 5.4 and 5.10),
// and POST /api/star to star skills (spec section 5.11).
// Repository content is inserted with textContent. The one exception is content_html,
// which the server renders from Markdown with raw HTML escaped and unsafe links removed.
"use strict";

const $ = (id) => document.getElementById(id);

const MIN_PANE = 240;
const DEFAULT_LEFT_FRACTION = 0.38;
const WIDTH_KEY = "skill-atlas.left-fraction";

const SNIPPET_CONTEXT = 40;

// The loaded repositories, as the URLs the user gave, in the order they were added.
let repositoryUrls = [];
// { repositories, skills, ignored, multi, selected, shown, visible }; skills[i] matches items()[i].
let current = null;
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

function repositoryOf(skill) {
  return current.repositories.find((repository) => repository.name === skill.repository);
}

function githubUrl(repository, path) {
  return "https://github.com/" + repository.name + "/blob/" + repository.commit + "/" +
    (path === "." ? "" : path + "/");
}

// "owner/repo", or "owner:<login>" for an organization or user (spec section 5.12), from any accepted URL
// form, lowercased, to tell whether two URLs name the same thing.
function repositoryKey(url) {
  const trimmed = url.trim().replace(/\.git$/i, "").replace(/\/+$/, "");
  const owner = trimmed.match(/github\.com\/orgs\/([^/\s]+)(?:\/repositories)?$/i) ||
    trimmed.match(/^(?:https?:\/\/)?(?:www\.)?github\.com\/([^/\s]+)$/i);
  if (owner) return "owner:" + owner[1].toLowerCase();
  const match = trimmed.match(/github\.com[/:]([^/\s]+)\/([^/\s]+)$/i);
  return match ? (match[1] + "/" + match[2]).toLowerCase() : url.trim().toLowerCase();
}

// ---- URL state: ?url=<a>&url=<b>&skill=<id or path>&q=<filter> ----

function writeLocation(skillKey, query = "") {
  const params = new URLSearchParams();
  for (const url of repositoryUrls) params.append("url", url);
  if (skillKey) params.set("skill", skillKey);
  if (query.trim()) params.set("q", query);
  history.replaceState(null, "", "?" + params.toString());
}

function updateLocation() {
  let key = null;
  if (current && current.shown >= 0) {
    const skill = current.skills[current.shown];
    // One repository keeps the plain path, as before; several need the full id.
    key = current.multi ? skill.id : skill.path;
  }
  writeLocation(key, $("filter").value);
}

// ---- Repository chips ----

function removeButton(name, url) {
  const remove = element("button", "chip-remove", "×");
  remove.type = "button";
  remove.title = "Remove " + name;
  remove.setAttribute("aria-label", remove.title);
  remove.addEventListener("click", () => removeRepository(url));
  return remove;
}

function repositoryChip(repository) {
  const chip = element("span", "chip" + (repository.error ? " failed" : ""));
  chip.dataset.url = repository.url;
  chip.append(element("span", "chip-name", repository.name || repository.url));
  chip.append(element("span", "chip-count", repository.error ? "failed" : String(repository.skill_count)));
  chip.append(removeButton(repository.name || repository.url, repository.url));
  return chip;
}

// One chip for a whole organization or user: its repositories with skills, and their skill count (spec section 5.12).
function ownerChip(owner) {
  const chip = element("span", "chip owner" + (owner.error ? " failed" : ""));
  chip.dataset.url = owner.url;
  chip.append(element("span", "chip-name", owner.name));
  if (owner.error) {
    chip.append(element("span", "chip-count", "failed"));
  } else {
    const repositories = owner.repositories.length;
    const skills = current.repositories
      .filter((repository) => owner.repositories.includes(repository.name))
      .reduce((total, repository) => total + repository.skill_count, 0);
    chip.append(element("span", "chip-repos", repositories + (repositories === 1 ? " repo" : " repos")));
    chip.append(element("span", "chip-count", String(skills)));
    chip.title = owner.summary;
  }
  chip.append(removeButton(owner.name, owner.url));
  return chip;
}

// Chips follow the order the URLs were added in. A repository found through an owner has no chip of its own.
function renderChips() {
  const chips = !current ? [] : repositoryUrls.map((url) => {
    const owner = current.owners.find((entry) => entry.url === url);
    if (owner) return ownerChip(owner);
    const repository = current.repositories.find((entry) => !entry.from && entry.url === url) ||
      current.repositories.find((entry) => entry.name && entry.name.toLowerCase() === repositoryKey(url));
    return repository ? repositoryChip({ ...repository, url }) : null;
  }).filter(Boolean);
  $("repo-chips").replaceChildren(...chips);
  show($("repo-chips"), chips.length > 0);
}

function removeRepository(url) {
  repositoryUrls = repositoryUrls.filter((existing) => repositoryKey(existing) !== repositoryKey(url));
  if (repositoryUrls.length === 0) {
    current = null;
    show($("result"), false);
    renderChips();
    history.replaceState(null, "", "?");
    return;
  }
  scan(null, $("filter").value);
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
  if (skill.starred) icon("star-icon", "★", "starred");
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
  item.dataset.id = skill.id;
  item.dataset.repository = skill.repository;

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

function renderGroupHeader(repository) {
  const header = element("div", "group-header");
  header.setAttribute("role", "presentation");
  header.dataset.repository = repository.name;
  header.append(element("span", "group-name", repository.name), element("span", "group-count", ""));
  return header;
}

// ---- Filter (spec sections 5.5, 5.10 and 5.11) ----

const STARRED = "is:starred";

// The query's words, its repo:<text> qualifiers, and whether is:starred is in it, lowercased.
function parseQuery(query) {
  const tokens = query.toLowerCase().split(/\s+/).filter(Boolean);
  const rest = tokens.filter((token) => token !== STARRED);
  return {
    words: rest.filter((token) => !token.startsWith("repo:")),
    repositories: rest.filter((token) => token.startsWith("repo:")).map((token) => token.slice(5)).filter(Boolean),
    starred: tokens.includes(STARRED),
  };
}

function queryWords(query) {
  return parseQuery(query).words;
}

function matchesRepository(name, repositories) {
  return repositories.length === 0 || repositories.some((text) => name.toLowerCase().includes(text));
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
  const { words, repositories, starred } = parseQuery(query);
  const skills = current.skills;
  current.visible = [];
  const visibleByRepository = new Map();
  items().forEach((item, i) => {
    const skill = skills[i];
    const visible = matchesRepository(skill.repository, repositories) && (!starred || skill.starred) && matches(skill, words);
    item.hidden = !visible;
    if (visible) {
      current.visible.push(i);
      visibleByRepository.set(skill.repository, (visibleByRepository.get(skill.repository) || 0) + 1);
      fillItem(item, skill, words);
    }
  });
  for (const header of $("skills").querySelectorAll(".group-header")) {
    const count = visibleByRepository.get(header.dataset.repository) || 0;
    header.hidden = count === 0;
    header.querySelector(".group-count").textContent = String(count);
  }

  const count = $("filter-count");
  count.textContent = current.visible.length + " of " + skills.length;
  count.classList.toggle("active", words.length + repositories.length > 0 || starred);
  $("starred-only").setAttribute("aria-pressed", String(starred));
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

// Adds is:starred to the query, or removes it (spec section 5.11).
$("starred-only").addEventListener("click", () => {
  const tokens = $("filter").value.split(/\s+/).filter(Boolean);
  const kept = tokens.filter((token) => token.toLowerCase() !== STARRED);
  setFilter((kept.length < tokens.length ? kept : [...kept, STARRED]).join(" "));
});

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
  if ((event.key !== "/" && event.key !== "s") || event.ctrlKey || event.metaKey || event.altKey) return;
  if (isTextField(event.target) || !current || $("split").hidden) return;
  event.preventDefault();
  if (event.key === "/") $("filter").focus();
  else toggleStar();
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

// Lists the skills most like this one (spec sections 5.6 and 5.10); the server computes the scores.
function renderSimilar(skill) {
  const rows = skill.similar.map((similar) => {
    const row = element("button", "similar-row");
    row.type = "button";
    row.dataset.path = similar.path;
    row.dataset.id = similar.id;
    row.title = "Similarity " + similar.score + " %";
    const bar = element("span", "similar-bar");
    bar.setAttribute("aria-hidden", "true");
    const fill = element("span", "similar-fill");
    fill.style.width = similar.score + "%";
    bar.append(fill);
    const where = element("span", "similar-path", similar.path);
    if (similar.repository !== skill.repository) {
      where.prepend(element("span", "similar-repo", similar.repository + " · "));
    }
    row.append(element("span", "similar-name", similar.name), where, bar, element("span", "similar-score", similar.score + " %"));
    row.addEventListener("click", () => selectId(similar.id));
    return row;
  });
  $("similar-list").replaceChildren(...rows);
  show($("no-similar"), rows.length === 0);
}

// Clears the filter (spec section 5.5), if there is one, so that every skill is listed again.
function clearFilter() {
  if ($("filter").value !== "") setFilter("");
}

// Selects the skill with an id, clearing the filter first if it hides that skill.
function selectId(id) {
  if (!current) return;
  const index = current.skills.findIndex((skill) => skill.id === id);
  if (index < 0) return;
  const item = items()[index];
  if (!item || item.getClientRects().length === 0) clearFilter();
  select(index, { focus: true });
}

// ---- Stars (spec section 5.11) ----

function renderStarButton(skill) {
  const button = $("star-button");
  button.textContent = skill.starred ? "★ Starred" : "☆ Star";
  button.setAttribute("aria-pressed", String(skill.starred));
}

// Stars or unstars the selected skill. The page changes only once the server has saved it.
async function toggleStar() {
  if (!current || current.shown < 0) return;
  const index = current.shown;
  const skill = current.skills[index];
  show($("error"), false);
  try {
    const response = await fetch("/api/star", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        repository: skill.repository, path: skill.path, also_at: skill.also_at, name: skill.name, starred: !skill.starred,
      }),
    });
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
    skill.starred = body.starred;
  } catch (error) {
    $("error").textContent = "error: " + error.message;
    show($("error"), true);
    return;
  }
  const head = items()[index].querySelector(".skill-head");
  head.replaceChildren(head.querySelector(".skill-name"), ...icons(skill));
  if (current.shown === index) renderStarButton(skill);
  // With is:starred in the filter, an unstarred skill leaves the list.
  applyFilter();
}

$("star-button").addEventListener("click", toggleStar);

function renderDetail(skill) {
  const repository = repositoryOf(skill);
  const repo = $("detail-repo");
  repo.textContent = skill.repository;
  show(repo, current.multi);

  const name = $("detail-name");
  name.textContent = skill.name;
  name.append(...badges(skill));
  renderStarButton(skill);

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
  if (!current || index < 0 || index >= current.skills.length) return;
  current.selected = index;
  current.shown = index;
  markSelected(index);
  const all = items();
  all[index].scrollIntoView({ block: "nearest" });
  if (focus) all[index].focus({ preventScroll: true });

  show($("detail"), true);
  renderDetail(current.skills[index]);
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
  if (current && current.skills.length > 0) applyStoredWidth();
});

// ---- Result ----

function renderSingleSummary(repository) {
  const name = $("repo-name");
  name.textContent = repository.name;
  name.href = "https://github.com/" + repository.name;

  const description = $("repo-description");
  description.textContent = repository.description || "(none)";
  description.classList.toggle("none", !repository.description);

  $("commit").textContent = repository.commit;
  $("branch").textContent = repository.branch;
}

function renderSummaryTable(repositories) {
  const rows = repositories.map((repository) => {
    const row = element("tr", repository.error ? "failed" : "");
    row.dataset.url = repository.url;
    const nameCell = element("td");
    if (repository.error) {
      nameCell.append(element("span", "repo-name", repository.name || repository.url));
      const error = element("td", "error-cell", "error: " + repository.error);
      error.colSpan = 3;
      row.append(nameCell, error);
      return row;
    }
    const link = element("a", "repo-name", repository.name);
    link.href = "https://github.com/" + repository.name;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    nameCell.append(link);
    const description = element("td", "repo-description" + (repository.description ? "" : " none"), repository.description || "(none)");
    const commit = element("td");
    commit.append(element("code", "commit", repository.commit.slice(0, 12)), " ", element("span", "branch", repository.branch));
    row.append(nameCell, description, commit, element("td", "count", String(repository.skill_count)));
    return row;
  });
  $("repo-rows").replaceChildren(...rows);
}

// The "Searched <owner>: …" line of each owner, or its error, under the summary table (spec section 5.12).
function renderOwnerLines(owners) {
  const lines = owners.map((owner) => owner.error
    ? element("li", "error", "error: " + owner.name + ": " + owner.error)
    : element("li", "", owner.summary));
  $("owner-lines").replaceChildren(...lines);
  show($("owner-lines"), lines.length > 0);
}

function render(body, preferredSkill, query) {
  const scanned = body.repositories.filter((repository) => !repository.error);
  const owners = body.owners || [];
  // An owner always gets the several-repositories layout, which says what was searched.
  const multi = body.repositories.length > 1 || owners.length > 0;
  current = { repositories: body.repositories, owners, skills: body.skills, ignored: body.ignored, multi, selected: 0, shown: -1, visible: [] };
  renderChips();

  // One repository or one owner that failed looks exactly like it did before: an error, no result.
  const failedOwner = owners.length === 1 && owners[0].error && body.repositories.length === 0;
  if (failedOwner || (!multi && scanned.length === 0)) {
    $("error").textContent = "error: " + (failedOwner ? owners[0].error : body.repositories[0].error);
    show($("error"), true);
    show($("result"), false);
    return;
  }

  show($("repository"), !multi);
  show($("repo-table"), multi && body.repositories.length > 0);
  if (multi) renderSummaryTable(body.repositories);
  else renderSingleSummary(scanned[0]);
  renderOwnerLines(owners);

  const count = body.skills.length;
  const heading = $("skill-count");
  heading.textContent = count === 0 ? "No skills found." : count + (count === 1 ? " skill found" : " skills found");
  heading.classList.toggle("empty", count === 0);

  const nodes = [];
  body.skills.forEach((skill, index) => {
    if (multi && (index === 0 || body.skills[index - 1].repository !== skill.repository)) {
      nodes.push(renderGroupHeader(body.repositories.find((repository) => repository.name === skill.repository)));
    }
    nodes.push(renderSkillItem(skill, index));
  });
  $("skills").replaceChildren(...nodes);
  show($("split"), count > 0);

  const ignored = body.ignored;
  $("ignored-heading").textContent = "Ignored " + ignored.length + " test " +
    (ignored.length === 1 ? "fixture" : "fixtures") + " (not skills)";
  $("ignored-list").replaceChildren(...ignored.map((entry) =>
    element("li", "skill-path", multi ? entry.repository + ":" + entry.path : entry.path)));
  show($("ignored"), ignored.length > 0);

  show($("result"), true);
  // The skill from the URL, given as an id or, with one repository, as a path.
  const preferred = body.skills.findIndex((skill) => skill.id === preferredSkill || skill.path === preferredSkill);
  current.selected = Math.max(preferred, 0);
  $("filter").value = query || "";
  if (count > 0) {
    applyStoredWidth();
    applyFilter();
  } else {
    updateLocation();
  }
}

// Scans every loaded repository; the server reuses recent results, so only new ones are cloned.
async function scan(preferredSkill, query) {
  document.body.dataset.state = "scanning";
  show($("error"), false);
  setBusy(true, "Scanning " + repositoryUrls.join(", ") + "…");
  try {
    const params = new URLSearchParams();
    for (const url of repositoryUrls) params.append("url", url);
    const response = await fetch("/api/scans?" + params.toString());
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
    setBusy(false);
    render(body, preferredSkill, query);
  } catch (error) {
    $("error").textContent = "error: " + error.message;
    show($("error"), true);
    show($("result"), false);
  } finally {
    setBusy(false);
    document.body.dataset.state = "idle";
  }
}

$("scan-form").addEventListener("submit", (event) => {
  event.preventDefault();
  const added = $("url").value.split(/[\s,]+/).map((url) => url.trim()).filter(Boolean);
  if (added.length === 0 && repositoryUrls.length === 0) return;
  for (const url of added) {
    if (!repositoryUrls.some((existing) => repositoryKey(existing) === repositoryKey(url))) repositoryUrls.push(url);
  }
  $("url").value = "";
  const keep = current && current.shown >= 0 ? current.skills[current.shown].id : null;
  scan(keep, $("filter").value);
});

// A link like /?url=a&url=b&skill=owner/repo:path&q=test starts a scan right away.
const initial = new URLSearchParams(location.search);
repositoryUrls = initial.getAll("url").filter(Boolean);
if (repositoryUrls.length > 0) {
  scan(initial.get("skill"), initial.get("q"));
} else {
  document.body.dataset.state = "idle";
}
