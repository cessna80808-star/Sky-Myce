"use strict";
const $ = (id) => document.getElementById(id),
  content = $("content");
let csrf = "",
  state = null,
  page = "Overview",
  busy = false,
  stopped = false,
  replay = null,
  progress = 0,
  playing = false,
  frame = 0,
  lastFrame = 0;
let alertAudio = null,
  lastAlertSerial = 0;
try {
  lastAlertSerial = Number(sessionStorage.getItem("skyMyceBazaarAlertSerial")) || 0;
} catch {}
const compactFormatter = new Intl.NumberFormat("en-US", { notation: "compact", maximumFractionDigits: 2 }),
  openFlipDetails = new Set();
const recordDrafts = new Map(),
  openRepairs = new Set(),
  openPendingDetails = new Set(),
  openRepairAdvanced = new Set(),
  openPositions = new Set();
let reviewOpen = false,
  staleOpen = false;
// Positions are grouped by what the player must do, mirroring Manage Orders; stale ones sit apart at the bottom.
const groupMeta = {
  NEEDS_YOU: { title: "Needs you", hint: "Claim, list or decide", cls: "warning" },
  FILLING: { title: "Filling", hint: "Open orders waiting on the market", cls: "accent" },
  HELD: { title: "Held", hint: "Items you own with no offer", cls: "muted" },
  STALE: { title: "Possibly stale", hint: "Not in your last sync", cls: "muted" },
};
const groupOrder = ["NEEDS_YOU", "FILLING", "HELD", "STALE"];
const isLive = (order) => order.status === "OPEN" || order.status === "FILLED";
const age = (at) => {
  const seconds = Math.max(0, Math.floor((Date.now() - at) / 1000));
  return seconds < 90 ? seconds + "s ago" : seconds < 5400 ? Math.floor(seconds / 60) + "m ago" : Math.floor(seconds / 3600) + "h ago";
};
// Manage Orders was read after this live order was last seen there, so it may no longer exist.
const orderUnseen = (order) =>
  Boolean(state.ordersSyncedAt) && isLive(order) && (state.orderSeenAt?.[order.id] || 0) < state.ordersSyncedAt;
function primaryOrder(position) {
  const own = (state.orders || []).filter((o) => o.positionId === position.id),
    buying = position.stage === "BUYING" ? true : position.stage === "SELLING" ? false : null,
    latest = (list) => list.reduce((best, o) => (!best || o.lastAt > best.lastAt ? o : best), null);
  return (
    latest(own.filter((o) => isLive(o) && (buying === null || o.buy === buying))) ||
    latest(own.filter((o) => isLive(o) || o.filled > o.collected))
  );
}
function positionGroup(position) {
  const live = (state.orders || []).filter((o) => o.positionId === position.id && isLive(o));
  if (live.length && live.every(orderUnseen)) return "STALE";
  if (state.nextSteps?.[position.id]?.actionable) return "NEEDS_YOU";
  if (live.length || position.stage === "BUYING" || position.stage === "SELLING") return "FILLING";
  return "HELD";
}
const syncLine = () =>
  state.ordersSyncedAt
    ? "Synced " + age(state.ordersSyncedAt) + (state.ordersSyncNote ? " · " + state.ordersSyncNote : "")
    : "Never synced · press Sync orders to read your Manage Orders";
const guidedPosition = () =>
  state.guidance
    ? state.positions.find((p) => state.guidance === itemName(p.product) + " · " + state.nextSteps?.[p.id]?.text)
    : null;
const stageLabels = {
  BUYING: "Buy order open",
  COLLECT: "Collect bought items",
  READY: "Ready to sell",
  SELLING: "Sell offer open",
  CLOSED: "Closed",
  WAITING: "Waiting for a fill",
};
const freshnessLabels = { FRESH: "Live", AGING: "Aging", STALE: "Stale", UNAVAILABLE: "Unavailable" };
const titleCase = (id) =>
  String(id)
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
const itemName = (product) => state?.names?.[product] || titleCase(product);
const repairKinds = {
  BUY_FILL: "Bought items",
  SELL_FILL: "Sold items",
  BUY_ORDER: "Buy order",
  SELL_ORDER: "Sell offer",
};
const compactCoins = (value) =>
  typeof value === "number" && Number.isFinite(value) ? compactFormatter.format(value) : "Unknown";
const coins = (value) =>
  typeof value === "number" && Number.isFinite(value)
    ? value.toLocaleString(undefined, { maximumFractionDigits: 2 })
    : "Unknown";
const pct = (value) => (typeof value === "number" ? (value * 100).toFixed(1) + "%" : "Unknown");
const date = (value) => (value ? new Date(value).toLocaleString() : "Ongoing");
const duration = (value) =>
  typeof value === "number"
    ? value < 60
      ? Math.round(value) + "s"
      : value < 3600
        ? Math.round(value / 60) + "m"
        : (value / 3600).toFixed(1) + "h"
    : "unknown";
function el(tag, text, cls) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (cls) node.className = cls;
  return node;
}
function button(text, run) {
  const node = el("button", text);
  node.type = "button";
  node.addEventListener("click", run);
  return node;
}
function para(parent, text, cls = "muted") {
  parent.append(el("p", text, cls));
}
function card(title) {
  const node = el("article", undefined, "card");
  if (title) node.append(el("h3", title));
  return node;
}
function notice(text) {
  $("notice").textContent = text;
  $("pub-status").textContent = text;
}
async function request(path, body) {
  const options = { credentials: "same-origin", cache: "no-store", headers: { "X-SkyMyce-CSRF": csrf } };
  if (body !== undefined) {
    options.method = "POST";
    options.headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  const response = await fetch(path, options);
  const value = await response.json();
  if (!response.ok) throw new Error(value.message || "Local request rejected");
  return value;
}
async function action(name, fields = {}) {
  if (busy) return;
  busy = true;
  try {
    const result = await request("/api/action", { id: crypto.randomUUID(), action: name, fields });
    notice(result.message);
    if (result.download) {
      const blob = new Blob([result.download], { type: "application/json" }),
        url = URL.createObjectURL(blob),
        link = el("a");
      link.href = url;
      link.download = (result.fileName || "sky-myce-bazaar.json").replace(/[^a-zA-Z0-9_.-]/g, "_");
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }
    await load(true);
    return result;
  } catch (error) {
    notice(error.message);
  } finally {
    busy = false;
  }
}
async function load(force = false) {
  if (stopped || document.hidden) return;
  try {
    state = await request("/api/state");
    if (replay && !state.sessions.some((session) => session.id === replay.id)) $("replay").close();
    for (const [key, value] of Object.entries(state.theme || {})) {
      if (
        ["bg", "panel", "card", "line", "text", "muted", "accent", "warning", "negative"].includes(key) &&
        Number.isInteger(value) &&
        value >= 0 &&
        value <= 0xffffff
      )
        document.documentElement.style.setProperty("--" + key, "#" + value.toString(16).padStart(6, "0"));
    }
    $("status").textContent = state.status;
    $("market").textContent = marketSummary();
    $("toggle").textContent = state.active ? "End flipping session" : "Start flipping session";
    $("connection").textContent = state.connectionStatus;
    deliverDashboardAlert();
    if (force || (page !== "Preferences" && !document.activeElement?.closest(".record-repair"))) render();
  } catch (error) {
    notice(error.message + " · Reopen the dashboard from Minecraft if its session expired.");
  }
}
function unlockAlertAudio(event) {
  if (!event.isTrusted || state?.settings.dashboardAlertSound !== "true") return;
  try {
    const Context = window.AudioContext || window.webkitAudioContext;
    if (!Context) return;
    alertAudio ??= new Context();
    if (alertAudio.state === "suspended") alertAudio.resume().catch(() => {});
  } catch {}
}
function deliverDashboardAlert() {
  const serial = Number(state.alertSerial);
  if (
    !state.active ||
    state.freshness !== "FRESH" ||
    state.settings.dashboardAlerts !== "true" ||
    !state.alert ||
    !Number.isSafeInteger(serial) ||
    serial <= 0 ||
    serial === lastAlertSerial
  )
    return;
  lastAlertSerial = serial;
  try {
    sessionStorage.setItem("skyMyceBazaarAlertSerial", String(serial));
  } catch {}
  notice(state.alert);
  if (state.settings.dashboardAlertSound !== "true" || !alertAudio || alertAudio.state !== "running" || document.hidden)
    return;
  try {
    const oscillator = alertAudio.createOscillator(),
      gain = alertAudio.createGain(),
      at = alertAudio.currentTime;
    oscillator.type = "sine";
    oscillator.frequency.setValueAtTime(520, at);
    gain.gain.setValueAtTime(0, at);
    gain.gain.linearRampToValueAtTime(0.025, at + 0.012);
    gain.gain.linearRampToValueAtTime(0, at + 0.14);
    oscillator.connect(gain);
    gain.connect(alertAudio.destination);
    oscillator.start(at);
    oscillator.stop(at + 0.15);
    oscillator.onended = () => {
      oscillator.disconnect();
      gain.disconnect();
    };
  } catch {}
}
document.addEventListener("pointerdown", unlockAlertAudio);
document.addEventListener("keydown", unlockAlertAudio);
function marketSummary() {
  const age = state.observedAt
    ? Math.max(0, Math.floor((Date.now() - state.observedAt) / 1000)) + "s old"
    : "No observation";
  const freshness = freshnessLabels[state.freshness] || state.freshness;
  return page === "Finder"
    ? [freshness, age].join(" · ")
    : [freshness, state.marketStatus, age, "Retrieved " + date(state.retrievedAt)].join(" · ");
}
function metric(parent, title, value, accent = false) {
  const node = el("div", undefined, "metric");
  node.append(el("span", title), el("strong", coins(value), accent ? "accent" : undefined));
  parent.append(node);
}
function metrics() {
  const node = el("div", undefined, "metrics");
  metric(node, "AVAILABLE PURSE", state.purse, true);
  metric(node, "COMMITTED BUY ORDERS", state.committed);
  metric(node, "INVENTORY COST BASIS", state.inventoryCost);
  metric(node, "UNREALIZED INVENTORY ESTIMATE", state.inventoryValue);
  metric(node, "REALIZED NET PROFIT", state.stats?.realizedProfit, true);
  metric(node, "COINS AWAITING COLLECTION", state.pendingCollection);
  return node;
}
function chart(points, target, fraction = 1) {
  target.replaceChildren();
  const clean = points.filter((point) => Number.isFinite(point.value)).slice(-1024);
  if (clean.length < 2) {
    target.append(el("p", "Not enough supported observations for a chart.", "muted"));
    return;
  }
  const ns = "http://www.w3.org/2000/svg",
    svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 800 160");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "Observed realized profit or historical price progression");
  const lo = Math.min(...clean.map((p) => p.value)),
    hi = Math.max(...clean.map((p) => p.value)),
    span = Math.max(hi - lo, 1),
    start = clean[0].at,
    time = Math.max(clean[clean.length - 1].at - start, 1);
  const cutoff = start + time * Math.max(0, Math.min(1, fraction)),
    pointsVisible = [];
  for (const point of clean) {
    if (point.at <= cutoff) pointsVisible.push(point);
    else {
      const previous = pointsVisible[pointsVisible.length - 1];
      if (previous && cutoff > previous.at)
        pointsVisible.push({
          at: cutoff,
          value:
            previous.value +
            ((point.value - previous.value) * (cutoff - previous.at)) / Math.max(1, point.at - previous.at),
        });
      break;
    }
  }
  const line = document.createElementNS(ns, "line");
  line.setAttribute("x1", "0");
  line.setAttribute("x2", "800");
  line.setAttribute("y1", "159");
  line.setAttribute("y2", "159");
  const path = document.createElementNS(ns, "path");
  path.setAttribute(
    "d",
    pointsVisible
      .map(
        (point, index) =>
          (index ? "L" : "M") +
          (5 + ((point.at - start) / time) * 790).toFixed(2) +
          "," +
          (150 - ((point.value - lo) / span) * 140).toFixed(2),
      )
      .join(" "),
  );
  svg.append(line, path);
  target.append(svg);
}
function realizedPoints() {
  let total = 0;
  return [...state.history]
    .filter((t) => t.netProfit !== null && t.netProfit !== undefined)
    .sort((a, b) => a.at - b.at)
    .map((t) => ({ at: t.at, value: (total += t.netProfit) }));
}
function matches(value) {
  return String(value).toLowerCase().includes($("search").value.toLowerCase());
}
function empty(text) {
  content.append(el("div", text, "empty"));
}
function render() {
  if (!state) return;
  $("market").textContent = marketSummary();
  $("page-title").textContent = page;
  content.replaceChildren();
  document.querySelectorAll("[data-page]").forEach((n) => n.classList.toggle("selected", n.dataset.page === page));
  if (state.guidance) {
    const guided = guidedPosition(),
      group = guided ? positionGroup(guided) : null,
      next = card("Next step" + (group ? " · " + groupMeta[group].title : ""));
    next.classList.add("guidance");
    para(next, state.guidance, "accent");
    const order = guided && primaryOrder(guided);
    if (order) next.append(fillBar(order, group), el("p", fillText(order, guided), "muted"));
    content.append(next);
  }
  if (!state.wizardDone) {
    const welcome = card("Your Bazaar workspace");
    para(
      welcome,
      "Available purse is your budget. Orders reserve capital separately; inventory and forecasts are not cash. Balanced finder defaults can be changed below.",
    );
    para(
      welcome,
      "Exceptional alerts require active flipping and fresh permitted data. The dashboard starts only on request. Personal history and private replays stay local.",
    );
    para(
      welcome,
      "Bazaar leaderboard sharing: " +
        (state.publicSharing ? "enabled by the existing category sharing policy" : "disabled") +
        ". Replay publication and account settings sync are separate choices.",
      "warning",
    );
    welcome.append(
      button("Review preferences", () => {
        page = "Preferences";
        render();
      }),
      button("Finish local setup", () => action("setup")),
    );
    content.append(welcome);
  }
  if (page === "Overview" || page === "Planner") {
    content.append(metrics());
    const intro = card(page === "Planner" ? "Spendable capital, without double counting" : "Your realized result");
    para(
      intro,
      "Purse already excludes reserved order coins. Inventory valuation and expected sale proceeds are shown separately; neither can fund a new order.",
    );
    if (page === "Overview") {
      para(
        intro,
        "Realized ROI " +
          pct(state.stats?.roi) +
          " · Profit per supported hour " +
          coins(state.stats?.profitPerHour) +
          " · " +
          (state.stats?.uncertainRecords || 0) +
          " uncertain records",
      );
      para(
        intro,
        "Recent display: this chart uses recent realized movements. Export retains the full private history.",
      );
      const plot = el("div", undefined, "chart");
      chart(realizedPoints(), plot);
      intro.append(plot);
      intro.append(button("Community leaderboard", () => action("leaderboard")));
    }
    content.append(intro);
    for (const warning of state.portfolioWarnings || []) para(content, warning, "warning");
    positions();
  } else if (page === "Finder") finder();
  else if (page === "Orders") orders();
  else if (page === "History") historyPage();
  else if (page === "Sessions") sessions();
  else preferences();
  if (["Orders", "History"].includes(page)) pendingRecords();
}
function fillBar(order, group) {
  const bar = el("div", undefined, "fill-bar " + (group === "STALE" ? "stale" : order.buy ? "buy" : "sell")),
    fraction = order.quantity > 0 ? Math.min(1, Math.max(0, order.filled / order.quantity)) : 0,
    fill = el("span");
  bar.setAttribute("role", "progressbar");
  bar.setAttribute("aria-valuemin", "0");
  bar.setAttribute("aria-valuemax", String(order.quantity));
  bar.setAttribute("aria-valuenow", String(order.filled));
  fill.style.width = (fraction * 100).toFixed(1) + "%";
  bar.append(fill);
  return bar;
}
const fillText = (order, position) =>
  order.filled +
  "/" +
  order.quantity +
  (order.buy ? " bought" : " sold") +
  (position.pendingItems > 0 ? " · " + position.pendingItems + " to claim" : "");
function syncTools() {
  const tools = el("div", undefined, "actions sync-row"),
    open = button("Sync orders", () => action("open-orders"));
  open.dataset.action = "open-orders";
  tools.append(open, el("p", syncLine(), "muted sync-status"));
  return tools;
}
function groupHead(group, count) {
  const node = el("div", undefined, "group-head");
  node.append(el("span", groupMeta[group].title + " · " + count, groupMeta[group].cls), el("span", groupMeta[group].hint));
  return node;
}
/** One row per position: badge, name, fill bar, key numbers and one primary action; the rest sits under Details. */
function positionRow(position, group) {
  const step = state.nextSteps?.[position.id],
    order = primaryOrder(position),
    stale = group === "STALE",
    kind = !order ? "HELD" : order.buy ? "BUY" : "SELL",
    node = el("article", undefined, "card position-row" + (stale ? " stale" : "")),
    head = el("div", undefined, "position-head"),
    title = el("div", undefined, "position-title"),
    figure = el("div", undefined, "position-figure");
  node.dataset.position = position.id;
  title.append(el("span", kind, "pill pill-" + kind.toLowerCase()), el("strong", itemName(position.product)));
  if (stale) title.append(el("span", "Not in last sync", "pill pill-outline warning"));
  else if (position.uncertain || order?.uncertain) title.append(el("span", "Reconstructed", "pill pill-outline"));
  const [big, small] =
    position.pendingItems > 0
      ? [position.pendingItems, "to claim"]
      : order
        ? [order.filled + "/" + order.quantity, order.buy ? "bought" : "sold"]
        : [position.remaining, "held"];
  figure.append(el("strong", String(big)), el("span", small));
  head.append(title, figure);
  node.append(head);
  para(
    node,
    [
      stageLabels[position.stage] || position.stage,
      order && order.price !== null && order.price !== undefined ? coins(order.price) + " each" : null,
      "cost " + coins(position.costBasis),
      position.relistCount ? "relisted " + position.relistCount + "×" : null,
      position.realizedProfit ? coins(position.realizedProfit) + " realized" : null,
    ]
      .filter(Boolean)
      .join(" · "),
  );
  if (order) node.append(fillBar(order, group));
  if (step) para(node, "→ " + step.text, stale ? "muted" : step.actionable ? "warning" : "step");
  const advice = state.relistAdvice?.[position.id];
  if (advice?.warn) para(node, "Relist check: " + advice.recommendation, "warning");
  const claim = position.pendingItems > 0 || (step?.actionable && step.text.startsWith("Claim")),
    follow = button("Follow this flip", () => action("guide", { position: position.id })),
    sync = button(claim ? "Open Manage Orders" : "Sync orders", () => action("open-orders")),
    primary = stale || claim ? sync : follow,
    tools = el("div", undefined, "actions");
  primary.classList.add("primary");
  sync.dataset.action = "open-orders";
  tools.append(primary);
  node.append(tools);
  const details = el("details");
  details.open = openPositions.has(position.id);
  details.append(el("summary", "Details"));
  details.addEventListener("toggle", () => {
    if (!details.isConnected) return;
    if (details.open) openPositions.add(position.id);
    else openPositions.delete(position.id);
  });
  para(
    details,
    [
      position.acquired + " bought",
      position.sold + " sold",
      position.remaining + " held",
      position.listed ? position.listed + " listed" : null,
      position.pendingItems ? position.pendingItems + " to claim" : null,
      "opened " + date(position.openedAt),
    ]
      .filter(Boolean)
      .join(" · "),
    "step",
  );
  for (const own of (state.orders || []).filter((o) => o.positionId === position.id && (isLive(o) || o.filled > o.collected))) {
    const seen = state.orderSeenAt?.[own.id];
    para(
      details,
      (own.buy ? "Buy " : "Sell ") +
        own.quantity +
        " at " +
        coins(own.price) +
        " · " +
        own.filled +
        "/" +
        own.quantity +
        " filled · " +
        own.collected +
        " claimed · " +
        (orderUnseen(own)
          ? "not in your last sync"
          : seen
            ? "seen in Manage Orders " + age(seen)
            : own.uncertain
              ? "unconfirmed"
              : "confirmed"),
    );
  }
  if (advice?.warn) {
    para(
      details,
      "Waiting would return ~" +
        coins(advice.waitProceeds) +
        "; selling instantly ~" +
        coins(advice.instantProceeds) +
        " (" +
        coins(advice.instantProfit) +
        " vs cost). You decide.",
    );
    for (const warning of advice.warnings || []) para(details, warning, "warning");
  }
  if (position.uncertain)
    para(details, "Some of this position was reconstructed from receipts rather than observed, so it stays out of public rankings.");
  details.append(primary === follow ? sync : follow);
  node.append(details);
  return node;
}
function positions() {
  const steps = state.nextSteps || {};
  const entries = state.positions
    .filter((p) => p.remaining > 0 || p.pendingItems > 0 || p.stage === "BUYING" || steps[p.id]?.actionable)
    .filter((p) => matches(p.product + " " + itemName(p.product)))
    .sort((a, b) => Number(!!steps[b.id]?.actionable) - Number(!!steps[a.id]?.actionable) || b.lastAt - a.lastAt)
    .map((p) => [positionGroup(p), p])
    .sort((a, b) => groupOrder.indexOf(a[0]) - groupOrder.indexOf(b[0]));
  content.append(syncTools());
  if (state.awaiting)
    para(
      content,
      state.awaiting + " filled sale(s) are waiting for their coins to be claimed; profit is counted once claimed.",
    );
  if (!entries.length) {
    empty("No open positions. Place a buy order and Sky-Myce will follow it from fill to sale.");
    return;
  }
  let shown = null;
  const confirmed = entries.filter(([group]) => group !== "STALE");
  for (const [group, position] of confirmed.slice(0, 100)) {
    if (group !== shown) {
      shown = group;
      content.append(groupHead(group, confirmed.filter(([g]) => g === group).length));
    }
    content.append(positionRow(position, group));
  }
  const stale = entries.filter(([group]) => group === "STALE");
  if (!stale.length) return;
  const fold = el("details", undefined, "stale-group");
  fold.open = staleOpen;
  fold.append(el("summary", "Possibly stale · " + stale.length + (stale.length === 1 ? " position" : " positions") + " not in your last sync"));
  fold.addEventListener("toggle", () => {
    if (fold.isConnected) staleOpen = fold.open;
  });
  para(
    fold,
    "Manage Orders did not show these orders when it was last read (" +
      age(state.ordersSyncedAt) +
      "). They may have been cancelled, claimed or finished elsewhere; sync again to confirm before acting on them.",
  );
  for (const [group, position] of stale.slice(0, 100)) fold.append(positionRow(position, group));
  content.append(fold);
}
function finder() {
  const tools = el("div", undefined, "actions");
  for (const risk of ["CONSERVATIVE", "BALANCED", "AGGRESSIVE"]) {
    const choice = button(risk, () => action("preference", { key: "risk", value: risk }));
    choice.classList.toggle("selected", state.settings.risk === risk);
    tools.append(choice);
  }
  content.append(tools);
  para(content, "Estimated returns · Prices and fill times can change.");
  const entries = state.opportunities.filter((item) => matches(item.product + " " + itemName(item.product)));
  if (!entries.length) {
    empty(state.freshness === "FRESH" ? "No flips match your capital and filters." : state.marketStatus);
    return;
  }
  const grid = el("div", undefined, "card-grid");
  for (const item of entries.slice(0, 100)) {
    const node = card(itemName(item.product));
    node.classList.add("flip-card");
    para(
      node,
      (item.netProfit >= 0 ? "+" : "") + compactCoins(item.netProfit) + " est. net · " + pct(item.roi) + " ROI",
      "estimate flip-summary",
    );
    para(
      node,
      "Buy " +
        compactCoins(item.buyPrice) +
        " → Sell " +
        compactCoins(item.sellPrice) +
        " · ×" +
        compactCoins(item.quantity),
      "flip-prices",
    );
    para(
      node,
      compactCoins(item.capital) +
        " capital · " +
        duration(item.fillSecondsLow) +
        "–" +
        duration(item.fillSecondsHigh) +
        " fill",
      "muted flip-meta",
    );
    const guide = button("Guide this flip", () => action("guide", { product: item.product }));
    guide.dataset.action = "guide";
    guide.title = "Open Bazaar search for this item and copy the suggested quantity";
    node.append(guide);
    const details = el("details");
    details.dataset.product = item.product;
    details.open = openFlipDetails.has(item.product);
    details.append(el("summary", "Details"));
    para(
      details,
      "Buy order " +
        coins(item.buyPrice) +
        " → Sell offer " +
        coins(item.sellPrice) +
        " · " +
        coins(item.quantity) +
        " items",
    );
    para(
      details,
      "Gross margin " +
        coins(item.grossMargin) +
        " − estimated fees " +
        coins(item.fees) +
        " = " +
        coins(item.netProfit) +
        " net",
    );
    para(
      details,
      "ROI " + pct(item.roi) + " · Capital " + coins(item.capital) + " · Confidence " + pct(item.confidence),
    );
    para(
      details,
      "Fill estimate " +
        duration(item.fillSecondsLow) +
        "–" +
        duration(item.fillSecondsHigh) +
        " · Weekly liquidity " +
        coins(item.liquidity),
    );
    para(
      details,
      "Source " + item.source + " · Observed " + date(item.observedAt) + " · Retrieved " + date(state.retrievedAt),
    );
    para(details, "Trend " + pct(item.trend) + " · Volatility " + pct(item.volatility));
    for (const warning of item.warnings) para(details, warning, "warning");
    para(
      details,
      "Public flow and depth estimate fill time; your queue position is unknown. Mayor effects remain unknown without supported time-aligned history.",
    );
    const history = state.priceHistory[item.product],
      plot = history?.length > 1 ? el("div", undefined, "chart") : null;
    if (plot) details.append(plot);
    details.addEventListener("toggle", () => {
      if (!details.isConnected) return;
      if (details.open) {
        openFlipDetails.add(item.product);
        if (plot) chart(history, plot);
      } else openFlipDetails.delete(item.product);
    });
    node.append(details);
    grid.append(node);
  }
  content.append(grid);
}
function orders() {
  content.append(syncTools());
  para(content, "Sync orders opens Manage Orders in game so Sky-Myce can read your current fills.");
  const entries = state.orders.filter((o) => matches(o.product + " " + itemName(o.product) + " " + o.id));
  if (!entries.length) {
    empty(state.orders.length ? "No orders match this search." : "No matched orders yet.");
    return;
  }
  const wrap = el("div", undefined, "table-wrap"),
    table = el("table"),
    head = el("tr");
  for (const name of ["Order", "Status", "Filled / quantity", "Collected", "Unit price", "Last sync", "Last observation"])
    head.append(el("th", name));
  table.append(head);
  for (const order of entries.slice(0, 200)) {
    const row = el("tr"),
      unseen = orderUnseen(order),
      seen = state.orderSeenAt?.[order.id];
    if (unseen) row.classList.add("stale");
    for (const text of [
      (order.buy ? "Buy · " : "Sell · ") + itemName(order.product),
      order.status + (order.uncertain ? " / unconfirmed" : ""),
      order.filled + " / " + order.quantity,
      order.collected,
      coins(order.price),
      unseen ? "Not in your last sync" : seen ? "Seen " + age(seen) : isLive(order) ? "Not synced yet" : "—",
      date(order.lastAt),
    ])
      row.append(el("td", text));
    table.append(row);
  }
  wrap.append(table);
  content.append(wrap);
}
function historyPage() {
  para(
    content,
    "Recent display; export retains the full private history. Unknown historical cost is never treated as zero.",
  );
  const entries = [...state.history].reverse().filter((t) => matches(t.product + " " + itemName(t.product)));
  if (!entries.length) {
    empty("No matching completed sales with local accounting records.");
    return;
  }
  for (const trade of entries.slice(0, 100)) {
    const node = card(itemName(trade.product) + " ×" + trade.quantity);
    para(node, "Realized net " + coins(trade.netProfit), "estimate");
    para(node, "Matched cost " + coins(trade.cost) + " · Fees " + coins(trade.fees) + " · " + date(trade.at));
    para(node, trade.evidence + " · " + (trade.eligible ? "observed / leaderboard eligible" : "personal only"));
    const details = el("details");
    details.open = openRepairs.has(trade.id);
    details.append(el("summary", "Correct a personal record"));
    details.addEventListener("toggle", () => {
      if (!details.isConnected) return;
      if (details.open) openRepairs.add(trade.id);
      else openRepairs.delete(trade.id);
    });
    manualInputs(details, { id: trade.id, kind: "SELL_FILL", product: trade.product }, "correct");
    node.append(details);
    content.append(node);
  }
}
/** One summary row; the receipts and their repair forms unfold beneath it so real records stay on top. */
function pendingRecords() {
  const records = state.pending || [];
  if (!records.length) return;
  const fold = el("details", undefined, "review-group");
  fold.open = reviewOpen || records.some((record) => openRepairs.has(record.id));
  fold.append(el("summary", records.length === 1 ? "1 receipt needs review" : records.length + " receipts need review"));
  fold.addEventListener("toggle", () => {
    if (fold.isConnected) reviewOpen = fold.open;
  });
  para(
    fold,
    "These Bazaar receipts couldn't be matched to an order with certainty. Syncing orders usually fixes them; fixed records stay personal and never count toward leaderboards.",
  );
  content.append(fold);
  for (const record of records) {
    const node = card("Needs your review"),
      tools = el("div", undefined, "actions");
    node.classList.add("pending-card");
    node.dataset.record = record.id;
    if (record.product) para(node, itemName(record.product), "accent");
    para(node, "This receipt couldn't be matched to an order with certainty. Syncing orders usually fixes it.");
    const open = button("Sync orders", () => action("open-orders"));
    open.dataset.action = "open-orders";
    const dismiss = button("Dismiss", () => action("confirm", { id: record.id, ignore: "true" }));
    dismiss.dataset.action = "dismiss";
    const fix = button(openRepairs.has(record.id) ? "Close" : "Fix record", () => {
      if (!openRepairs.delete(record.id)) openRepairs.add(record.id);
      render();
    });
    fix.dataset.action = "fix-record";
    fix.setAttribute("aria-expanded", String(openRepairs.has(record.id)));
    const details = button(openPendingDetails.has(record.id) ? "Hide why" : "Why?", () => {
      if (!openPendingDetails.delete(record.id)) openPendingDetails.add(record.id);
      render();
    });
    details.dataset.action = "pending-details";
    details.setAttribute("aria-expanded", String(openPendingDetails.has(record.id)));
    tools.append(open, dismiss, fix, details);
    node.append(tools);
    if (openPendingDetails.has(record.id)) para(node, record.message, "muted pending-reason");
    if (openRepairs.has(record.id)) manualInputs(node, record, "confirm");
    fold.append(node);
  }
}
function manualInputs(parent, record, name) {
  const id = record.id,
    form = el("div", undefined, "record-repair");
  form.dataset.record = id;
  parent.append(form);
  if (!recordDrafts.has(id))
    recordDrafts.set(
      id,
      Object.fromEntries(
        ["quantity", "unitPrice", "coins", "fee"]
          .filter((key) => record[key] !== null && record[key] !== undefined)
          .map((key) => [key, String(record[key])]),
      ),
    );
  const draft = recordDrafts.get(id);
  function draw() {
    form.replaceChildren();
    const kind = record.kind || draft.kind || "",
      isOrder = kind.endsWith("ORDER");
    para(form, "Personal correction only · Excluded from public rankings.");
    if (!record.kind) {
      const classification = el("select"),
        label = el("label", "What happened?");
      classification.name = "kind";
      for (const [value, title] of Object.entries({ "": "Choose what happened", ...repairKinds })) {
        const option = el("option", title);
        option.value = value;
        classification.append(option);
      }
      classification.value = kind;
      classification.addEventListener("change", () => {
        draft.kind = classification.value;
        draw();
      });
      label.append(classification);
      form.append(label);
    } else para(form, repairKinds[kind] || kind.replaceAll("_", " "), "accent");
    if (!kind) return;
    if (!Object.hasOwn(repairKinds, kind)) {
      para(form, "This record needs a matched Bazaar order. Sync Manage Orders, or dismiss it.");
      return;
    }
    if (record.product) para(form, "Item: " + itemName(record.product));
    const grid = el("div", undefined, "repair-fields"),
      required = ["quantity", isOrder ? "unitPrice" : "coins", ...(!record.product ? ["product"] : [])];
    let save;
    const ready = () => {
      if (save) save.disabled = name !== "correct" && required.some((key) => !draft[key]?.trim());
    };
    function field(key, title) {
      const label = el("label", title),
        input = el("input");
      input.name = key;
      input.dataset.field = key;
      input.value = draft[key] || "";
      input.maxLength = key === "note" ? 160 : key === "product" ? 100 : 32;
      input.addEventListener("input", () => {
        draft[key] = input.value;
        ready();
      });
      label.append(input);
      grid.append(label);
    }
    if (!record.product) field("product", "Item name or ID");
    field("quantity", "Quantity");
    field(
      isOrder ? "unitPrice" : "coins",
      isOrder ? "Price per item" : kind === "BUY_FILL" ? "Total paid (coins)" : "Net coins received (after fees)",
    );
    form.append(grid);
    if (name === "correct") para(form, "Leave blank to keep saved amounts.");
    const advanced = button(openRepairAdvanced.has(id) ? "Hide advanced" : "Advanced", () => {
      if (!openRepairAdvanced.delete(id)) openRepairAdvanced.add(id);
      draw();
    });
    advanced.dataset.action = "repair-advanced";
    advanced.setAttribute("aria-expanded", String(openRepairAdvanced.has(id)));
    form.append(advanced);
    if (openRepairAdvanced.has(id)) {
      if (!isOrder) field("unitPrice", "Unit price (optional)");
      field("fee", "Known fee (optional)");
      field("note", "Note (optional)");
    }
    const feedback = el("p", draft._message || "", "warning repair-feedback");
    feedback.dataset.repairFeedback = id;
    feedback.setAttribute("role", "status");
    form.append(feedback);
    save = button("Save personal record", async () => {
      const allowed = [
        "quantity",
        "unitPrice",
        "fee",
        "note",
        ...(!isOrder ? ["coins"] : []),
        ...(!record.product ? ["product"] : []),
      ];
      const fields = {
        id,
        ...Object.fromEntries(allowed.filter((key) => draft[key]?.trim()).map((key) => [key, draft[key]])),
        ...(!record.kind ? { kind } : {}),
      };
      const result = await action(name, fields);
      draft._message = result?.message || "This record could not be saved";
      document.querySelectorAll("[data-repair-feedback]").forEach((node) => {
        if (node.dataset.repairFeedback === id) node.textContent = draft._message;
      });
    });
    save.dataset.action = "save-record";
    ready();
    form.append(save);
  }
  draw();
}
function sessions() {
  para(content, "Private local recordings. Publishing and deleting private history are separate actions.");
  const entries = [...state.sessions].reverse().filter((s) => matches(s.title));
  if (!entries.length) empty("No private session recordings. Existing published replays can still be managed below.");
  const grid = el("div", undefined, "card-grid");
  for (const session of entries) {
    const node = card(session.title),
      report = button("Send webhook report", () => {
        if (
          confirm(
            "Send only this session duration, realized net profit and completed-flip count to your configured personal webhook? Delivery uses your local webhook toggle.",
          )
        )
          action("webhook-summary", { session: session.id, confirm: "true" });
      });
    report.disabled =
      !session.endedAt || state.settings.webhookEnabled !== "true" || state.settings.webhookConfigured !== "true";
    report.title = "Requires a completed session and your locally configured, enabled personal webhook";
    node.classList.add("replay-card");
    para(node, coins(session.profit) + " realized net", "estimate");
    para(node, date(session.startedAt) + " → " + date(session.endedAt) + " · " + session.flips + " completed flips");
    node.append(
      button("Play private replay", () => openReplay(session)),
      button("Send private summary", () => {
        if (
          confirm(
            "Send only this session duration, realized net profit and completed-flip count to your linked account? This does not publish a replay or its transactions.",
          )
        )
          action("session-summary", { session: session.id, confirm: "true" });
      }),
      report,
      button("Delete local replay", () => {
        if (confirm("Delete this private session recording? Published links remain separate."))
          action("delete-session", { session: session.id });
      }),
    );
    grid.append(node);
  }
  content.append(grid);
  publishedPanel();
}
function preferences() {
  const node = card("Local controls and privacy");
  para(
    node,
    "Only preferences selected for optional sync leave this device. History, replay sources and webhook credentials are excluded.",
  );
  const grid = el("div", undefined, "form-grid");
  for (const [key, title] of Object.entries({
    minimumProfit: "Minimum estimated net profit",
    minimumRoiBps: "Minimum ROI (basis points; 100 = 1%)",
    minimumLiquidity: "Minimum weekly volume",
    maximumFillMinutes: "Maximum fill minutes (0 = any)",
    minimumConfidence: "Minimum confidence (%)",
    maximumCapital: "Maximum capital per flip",
    concentration: "Maximum concentration (%)",
    sellTaxBps: "Estimated sell tax (basis points)",
    relistThreshold: "Relist warning threshold (> value)",
    timezone: "Leaderboard IANA timezone",
    alertProfit: "Exceptional alert minimum profit",
    alertCooldownSeconds: "Alert cooldown seconds",
    marketRetentionDays: "Raw market retention days",
    inGameAlertProfit: "In-game minimum profit (0 = global)",
    inGameAlertCooldown: "In-game cooldown seconds (30–3600)",
    discordAlertProfit: "Discord DM minimum profit (0 = global)",
    discordAlertCooldown: "Discord DM cooldown seconds (30–3600)",
    dashboardAlertProfit: "Dashboard minimum profit (0 = global)",
    dashboardAlertCooldown: "Dashboard cooldown seconds (30–3600)",
    webhookAlertProfit: "Webhook minimum profit (0 = global)",
    webhookAlertCooldown: "Webhook cooldown seconds (30–3600)",
  })) {
    const label = el("label", title),
      input = el("input");
    input.maxLength = 80;
    input.value = state.settings[key] || "";
    label.append(
      input,
      button("Save", () => action("preference", { key, value: input.value })),
    );
    grid.append(label);
  }
  node.append(grid);
  para(
    node,
    "Channel minima of zero use the global minimum; each channel has an independent cooldown. Discord DM and webhook sounds follow your Discord client.",
  );
  const switches = el("div", undefined, "switches");
  for (const [key, title] of Object.entries({
    inGameAlerts: "In-game exceptional alerts",
    dashboardAlerts: "Dashboard alerts",
    discordAlerts: "Private bot DMs",
    webhookEnabled: "Personal webhook delivery",
    inGameAlertSound: "Quiet in-game tone",
    dashboardAlertSound: "Quiet dashboard tone (interact with this page to enable browser audio)",
  })) {
    const label = el("label"),
      input = el("input");
    input.type = "checkbox";
    input.checked = state.settings[key] === "true";
    input.addEventListener("change", () => action("preference", { key, value: String(input.checked) }));
    label.append(input, document.createTextNode(title));
    switches.append(label);
  }
  node.append(switches);
  const webhook = el("input");
  webhook.type = "password";
  webhook.maxLength = 240;
  webhook.placeholder = "Personal Discord webhook URL";
  webhook.autocomplete = "off";
  node.append(
    webhook,
    button("Save local webhook", () => {
      const url = webhook.value;
      webhook.value = "";
      action("webhook", { url });
    }),
  );
  const tools = el("div", undefined, "actions");
  tools.append(
    button("Export private history", () => action("export")),
    button("Delete private history", () => {
      if (
        confirm(
          "Permanently delete local trading history and private recordings? Published links and leaderboard sharing are separate.",
        )
      )
        action("delete-history", { confirm: "DELETE" });
    }),
    button("Sharing settings in Minecraft", () => action("sharing")),
    button("Change account settings sync", () => {
      if (
        confirm(
          "Change optional linked-account preference sync? No private history, replay sources or webhook URL are synced.",
        )
      )
        action("settings-sync", { enabled: String(state.settings.settingsSync !== "true") });
    }),
  );
  node.append(tools);
  content.append(node);
}
function replayAt(fraction) {
  const points = replay.series || [];
  if (!points.length) return replay.profit * fraction;
  const start = replay.startedAt,
    end = replay.endedAt || points[points.length - 1].at,
    at = start + (end - start) * fraction;
  let previous = { at: start, value: 0 };
  for (const next of points) {
    if (next.at >= at) {
      const ratio = (at - previous.at) / Math.max(1, next.at - previous.at);
      return previous.value + (next.value - previous.value) * Math.max(0, Math.min(1, ratio));
    }
    previous = next;
  }
  return previous.value;
}
function renderReplay() {
  if (!replay) return;
  $("replay-profit").textContent = coins(replayAt(progress));
  $("replay-seek").value = String(Math.round(progress * 1000));
  $("replay-progress").textContent = Math.round(progress * 100) + "%";
  chart(
    [
      { at: replay.startedAt, value: 0 },
      ...(replay.series || []),
      { at: replay.endedAt || Date.now(), value: replay.profit },
    ],
    $("replay-chart"),
    progress,
  );
  const timeline = $("replay-timeline");
  timeline.replaceChildren();
  const until = replay.startedAt + ((replay.endedAt || Date.now()) - replay.startedAt) * progress;
  for (const trade of (replay.timeline || []).filter((t) => t.at <= until).slice(-12)) {
    const row = el("div", undefined, "timeline-row");
    row.append(
      el("span", itemName(trade.product) + " ×" + trade.quantity),
      el("span", coins(trade.netProfit) + " net"),
    );
    timeline.append(row);
  }
}
function openReplay(session) {
  for (const [id, value] of Object.entries({
    "pub-detail": false,
    "pub-items": true,
    "pub-transactions": true,
    "pub-values": false,
    "pub-identity": true,
    "pub-listed": false,
  }))
    $(id).checked = value;
  $("pub-status").textContent = "";
  playing = false;
  cancelAnimationFrame(frame);
  replay = session;
  progress = 0;
  $("replay-title").textContent = session.title;
  $("replay-dates").textContent =
    date(session.startedAt) +
    " → " +
    date(session.endedAt) +
    " · " +
    duration(((session.endedAt || Date.now()) - session.startedAt) / 1000) +
    " · " +
    session.flips +
    " completed " +
    (session.flips === 1 ? "flip" : "flips");
  $("replay-play").textContent = "Play";
  renderReplay();
  showPublications();
  $("replay").showModal();
}
function animate(time) {
  if (!playing || document.hidden || !$("replay").open) return;
  const delta = lastFrame ? time - lastFrame : 0;
  lastFrame = time;
  progress = Math.min(1, progress + (delta / 12000) * Number($("replay-speed").value));
  renderReplay();
  if (progress < 1) frame = requestAnimationFrame(animate);
  else {
    playing = false;
    $("replay-play").textContent = "Play";
  }
}
function publicationRow(publication) {
  const row = el("div", undefined, "card");
  row.append(el("h3", publication.sessionId === "recovered" ? "Account-published replay" : "Published session replay"));
  try {
    const url = new URL(publication.url);
    if (url.protocol === "https:" && !url.username && !url.password) {
      const link = el("a", publication.visibility + " replay");
      link.href = url.href;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      row.append(link);
    }
  } catch {}
  row.append(
    button("Revoke link", async () => {
      if (confirm("Revoke the public link and remove its gallery listing? Your private recording remains.")) {
        await action("revoke", { id: publication.id });
        if (replay) showPublications();
      }
    }),
    button("Delete published replay", async () => {
      if (
        confirm(
          "Permanently delete this published representation and its gallery listing? The private recording and local trading history remain.",
        )
      ) {
        await action("delete-publication", { id: publication.id, confirm: "true" });
        if (replay) showPublications();
      }
    }),
  );
  return row;
}
function publishedPanel() {
  if (!state.publications.length) return;
  const panel = card("Published replays");
  para(
    panel,
    "These publications are independent of private recordings. Revoke or delete them here even after local history is removed.",
  );
  for (const publication of state.publications) panel.append(publicationRow(publication));
  content.append(panel);
}
function showPublications() {
  const node = $("publications");
  node.replaceChildren();
  if (!replay) return;
  for (const publication of state.publications.filter((p) => p.sessionId === replay.id))
    node.append(publicationRow(publication));
}
$("replay-play").addEventListener("click", () => {
  if (state?.reduceMotion || matchMedia("(prefers-reduced-motion: reduce)").matches) {
    progress = 1;
    renderReplay();
    return;
  }
  playing = !playing;
  if (progress >= 1) progress = 0;
  lastFrame = 0;
  $("replay-play").textContent = playing ? "Pause" : "Play";
  if (playing) frame = requestAnimationFrame(animate);
  else cancelAnimationFrame(frame);
});
$("replay-seek").addEventListener("input", () => {
  playing = false;
  cancelAnimationFrame(frame);
  $("replay-play").textContent = "Play";
  progress = Number($("replay-seek").value) / 1000;
  renderReplay();
});
$("replay-close").addEventListener("click", () => {
  $("replay").close();
  playing = false;
  cancelAnimationFrame(frame);
});
$("replay").addEventListener("close", () => {
  playing = false;
  cancelAnimationFrame(frame);
  replay = null;
  $("replay-timeline").replaceChildren();
  $("replay-chart").replaceChildren();
  for (const id of ["replay-title", "replay-dates", "replay-profit"]) $(id).textContent = "";
});
$("publish").addEventListener("click", async () => {
  if (!replay) return;
  const fields = {
    session: replay.id,
    detail: String($("pub-detail").checked),
    hideItems: String($("pub-items").checked),
    hideTransactions: String($("pub-transactions").checked),
    hideValues: String($("pub-values").checked),
    hideIdentity: String($("pub-identity").checked),
    listed: String($("pub-listed").checked),
  };
  if (
    confirm(
      "Upload only this selected session representation? Visibility: " +
        (fields.listed === "true" ? "community gallery" : "unlisted link") +
        ".",
    )
  ) {
    await action("publish", fields);
    showPublications();
  }
});
document.querySelectorAll("[data-page]").forEach((node) =>
  node.addEventListener("click", () => {
    page = node.dataset.page;
    render();
  }),
);
$("search").addEventListener("input", render);
$("toggle").addEventListener("click", () => action("toggle"));
$("refresh").addEventListener("click", () => action("refresh"));
$("close").addEventListener("click", async () => {
  try {
    await request("/api/close", {});
    stopped = true;
    playing = false;
    cancelAnimationFrame(frame);
    if (alertAudio) alertAudio.close().catch(() => {});
    notice("Dashboard stopped. Reopen it from Minecraft when needed.");
    document.querySelectorAll("button").forEach((node) => (node.disabled = true));
  } catch (error) {
    notice(error.message);
  }
});
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    playing = false;
    cancelAnimationFrame(frame);
    $("replay-play").textContent = "Play";
  } else load();
});
(async () => {
  const fragment = location.hash.slice(1),
    secret = fragment.split("&")[0],
    selected = new URLSearchParams(fragment.includes("&") ? fragment.slice(fragment.indexOf("&") + 1) : "").get(
      "session",
    );
  window.history.replaceState(null, "", location.pathname);
  try {
    const session = secret ? await request("/api/bootstrap", { secret }) : await request("/api/session");
    csrf = session.csrf;
    await load(true);
    if (selected && state) {
      const found = state.sessions.find((s) => s.id === selected);
      if (found) openReplay(found);
    }
    setInterval(() => load(), 8000);
  } catch (error) {
    notice("This private dashboard session is unavailable. Reopen it from Minecraft.");
  }
})();
