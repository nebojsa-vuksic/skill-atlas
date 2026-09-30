// Skill Atlas web view: calls GET /api/scan and renders the result (spec section 5.4).
// All repository content is inserted with textContent, never as HTML.
"use strict";

const $ = (id) => document.getElementById(id);

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

function renderSkill(skill) {
  const item = element("li", "skill");
  item.dataset.path = skill.path;

  const name = element("h3", "skill-name", skill.name);
  if (skill.shipped) name.append(element("span", "shipped", "◆ shipped in product"));
  for (const warning of skill.warnings) {
    name.append(element("span", "warning", "⚠ " + warning));
  }
  item.append(name);

  const description = skill.short_description
    ? element("p", "skill-description", skill.short_description)
    : element("p", "skill-description none", "(no description)");
  if (skill.description && skill.description !== skill.short_description) {
    description.title = skill.description;
  }
  item.append(description);
  item.append(element("div", "skill-path", skill.path));
  for (const copy of skill.also_at) {
    item.append(element("div", "skill-path also", "also in " + copy));
  }
  return item;
}

function render(result) {
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

  $("skills").replaceChildren(...result.skills.map(renderSkill));

  const ignored = result.ignored;
  $("ignored-heading").textContent = "Ignored " + ignored.length + " test " +
    (ignored.length === 1 ? "fixture" : "fixtures") + " (not skills)";
  $("ignored-list").replaceChildren(...ignored.map((entry) => element("li", "skill-path", entry.path)));
  show($("ignored"), ignored.length > 0);
  show($("result"), true);
}

async function scan(url) {
  show($("error"), false);
  show($("result"), false);
  setBusy(true, "Scanning " + url + "…");
  try {
    const response = await fetch("/api/scan?url=" + encodeURIComponent(url));
    const body = await response.json();
    if (!response.ok) throw new Error(body.error || "HTTP " + response.status);
    render(body);
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
  history.replaceState(null, "", "?url=" + encodeURIComponent(url));
  scan(url);
});

// A link like /?url=https://github.com/owner/repo starts a scan right away.
const initial = new URLSearchParams(location.search).get("url");
if (initial) {
  $("url").value = initial;
  scan(initial);
} else {
  document.body.dataset.state = "idle";
}
