// Front-end renderer: a thin view over the server's MarketSnapshot (already aggregated with
// cumulative depth). It formats, builds the DOM, diffs for the flash highlight, and wires the order
// ticket + SSE stream. All non-trivial book logic lives, and is tested, on the JVM.

"use strict";

(() => {
  const $ = (id) => document.getElementById(id);
  const DASH = "—";
  // Fixed until the symbol picker lands: the server already routes any symbol under /api/{symbol}.
  const SYMBOL = "SIM";

  const num = (s) => parseFloat(s);
  const price2 = (s) => num(s).toFixed(2);
  const time = (ms) => {
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, "0");
    return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  };

  // --- Order-book ladder --------------------------------------------------
  // Previous sizes per side, keyed by price, so a level that changes can flash.
  const prevSize = { bid: {}, ask: {} };

  const totalDepth = (levels) =>
    levels.length ? levels[levels.length - 1].cumulative : 0;
  const depthWidth = (cumulative, maxDepth) =>
    maxDepth > 0 ? (cumulative / maxDepth) * 100 : 0;

  function ladder(levels, side, maxDepth) {
    // The server sends levels best-first. Asks are displayed worst-to-best so the best ask sits
    // against the spread row, mirroring a real exchange ladder; bids stay best-first below it.
    const ordered = side === "ask" ? [...levels].reverse() : levels;
    const seen = {};
    const html = ordered
      .map((level) => {
        const was = prevSize[side][level.price];
        const flash = was !== undefined && was !== level.size ? " flash" : "";
        seen[level.price] = level.size;
        const width = depthWidth(level.cumulative, maxDepth).toFixed(1);
        return (
          `<div class="row${flash}" style="--w:${width}%">` +
          `<span class="bar"></span>` +
          `<span class="px">${price2(level.price)}</span>` +
          `<span class="sz">${level.size}</span>` +
          `<span class="tot">${level.cumulative}</span>` +
          `</div>`
        );
      })
      .join("");
    prevSize[side] = seen;
    return html || `<div class="empty">${DASH}</div>`;
  }

  // --- Market-stats strip + spread row ------------------------------------
  function renderStats(snapshot) {
    const bestBid = snapshot.bids.length ? num(snapshot.bids[0].price) : null;
    const bestAsk = snapshot.asks.length ? num(snapshot.asks[0].price) : null;
    const mid =
      bestBid !== null && bestAsk !== null ? (bestBid + bestAsk) / 2 : null;
    const spread =
      bestBid !== null && bestAsk !== null ? bestAsk - bestBid : null;
    const spreadText =
      spread !== null
        ? `${spread.toFixed(2)} (${((spread / mid) * 100).toFixed(2)}%)`
        : DASH;

    $("st-bid").textContent = bestBid !== null ? bestBid.toFixed(2) : DASH;
    $("st-ask").textContent = bestAsk !== null ? bestAsk.toFixed(2) : DASH;
    $("st-mid").textContent = mid !== null ? mid.toFixed(2) : DASH;
    $("st-spread").textContent = spreadText;
    $("sp-mid").textContent = mid !== null ? mid.toFixed(2) : DASH;
    $("sp-spread").textContent = spreadText;

    const last = snapshot.tape.length ? snapshot.tape[0] : null;
    const lastEl = $("st-last");
    if (last) {
      lastEl.textContent = price2(last.price);
      lastEl.className = `v ${last.side === "BID" ? "pos up" : "neg down"}`;
    } else {
      lastEl.textContent = DASH;
      lastEl.className = "v";
    }
  }

  // --- Trade tape ---------------------------------------------------------
  let lastTradeTime = 0;

  function renderTape(snapshot) {
    const body = $("tape");
    if (!snapshot.tape.length) {
      body.innerHTML = `<div class="tape-empty">no trades yet</div>`;
      return;
    }
    const fresh = snapshot.tape[0].time !== lastTradeTime;
    lastTradeTime = snapshot.tape[0].time;
    body.innerHTML = snapshot.tape
      .map((t, i) => {
        const buy = t.side === "BID";
        const cls = `trow ${buy ? "buy" : "sell"}${i === 0 && fresh ? " new" : ""}`;
        return (
          `<div class="${cls}">` +
          `<span class="tt">${time(t.time)}</span>` +
          `<span class="ts">${buy ? "BUY" : "SELL"}</span>` +
          `<span class="tp">${price2(t.price)}</span>` +
          `<span class="tz">${t.size}</span>` +
          `</div>`
        );
      })
      .join("");
  }

  function render(snapshot) {
    const maxDepth = Math.max(
      totalDepth(snapshot.bids),
      totalDepth(snapshot.asks),
    );
    $("asks").innerHTML = ladder(snapshot.asks, "ask", maxDepth);
    $("bids").innerHTML = ladder(snapshot.bids, "bid", maxDepth);
    renderStats(snapshot);
    renderTape(snapshot);
  }

  // Snapshots can arrive out of order (a submit response racing an SSE frame); never let an
  // older one overwrite a newer one.
  let lastTs = 0;

  function renderIfFresh(snapshot) {
    if (snapshot.ts < lastTs) return;
    lastTs = snapshot.ts;
    render(snapshot);
  }

  // --- Connection indicator ----------------------------------------------
  function setConnected(on) {
    const label = on ? "live" : "offline";
    ["conn", "conn2"].forEach((id) => {
      $(id).className = `dot ${on ? "live" : "off"}`;
    });
    $("connlbl").textContent = label;
    $("connlbl2").textContent = label;
  }

  // --- Order ticket -------------------------------------------------------
  let side = "BID";

  function updateLabel() {
    const verb = side === "BID" ? "Buy" : "Sell";
    $("submit").textContent =
      `${verb} ${$("size").value || "0"} @ ${$("price").value || "0"}`;
  }

  function setSide(next) {
    side = next;
    const isBid = next === "BID";
    $("buy").classList.toggle("active", isBid);
    $("buy").setAttribute("aria-pressed", String(isBid));
    $("sell").classList.toggle("active", !isBid);
    $("sell").setAttribute("aria-pressed", String(!isBid));
    $("submit").className = `submit ${isBid ? "buy" : "sell"}`;
    updateLabel();
  }

  async function submitOrder() {
    const query = `side=${side}&price=${encodeURIComponent($("price").value)}&size=${encodeURIComponent($("size").value)}`;
    const result = $("result");
    try {
      const response = await fetch(`/api/${SYMBOL}/order?${query}`, {
        method: "POST",
      });
      const data = await response.json();
      if (data.error) {
        result.textContent = `✕ ${data.error}`;
        result.className = "result err";
        return;
      }
      renderIfFresh(data);
      result.textContent =
        data.matched > 0
          ? `✓ ${data.matched} fill${data.matched > 1 ? "s" : ""}`
          : "✓ order rested on the book";
      result.className = "result ok";
    } catch (e) {
      result.textContent = `✕ ${e}`;
      result.className = "result err";
    }
  }

  // --- Wiring -------------------------------------------------------------
  $("buy").onclick = () => setSide("BID");
  $("sell").onclick = () => setSide("OFFER");
  $("submit").onclick = submitOrder;
  ["price", "size"].forEach((id) => {
    const el = $(id);
    el.oninput = updateLabel;
    el.addEventListener("keydown", (e) => {
      if (e.key === "Enter") submitOrder();
    });
  });
  updateLabel();

  let lastEventAt = Date.now();
  let events = 0;
  const stream = new EventSource(`/api/${SYMBOL}/stream`);
  stream.onmessage = (e) => {
    lastEventAt = Date.now();
    events += 1;
    $("msgs").textContent = events;
    renderIfFresh(JSON.parse(e.data));
  };
  stream.onopen = () => setConnected(true);
  stream.onerror = () => setConnected(false);

  setInterval(() => {
    const age = (Date.now() - lastEventAt) / 1000;
    $("age").textContent = age < 1.5 ? "just now" : `${Math.round(age)}s ago`;
  }, 1000);
})();
