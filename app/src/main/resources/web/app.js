// Front-end renderer: a thin view over the server's MarketSnapshot (already aggregated with
// cumulative depth). It formats, builds the DOM, diffs for the flash highlight, and wires the order
// ticket + SSE stream. All non-trivial book logic lives, and is tested, on the JVM.

"use strict";

// Embedded as a trading-desk tab (?embed=1): the desk supplies the outer chrome, so hide this
// app's own topbar/status bar (see app.css .embedded). Standalone, the class is never added.
if (new URLSearchParams(location.search).has("embed")) {
  document.body.classList.add("embedded");
}

(() => {
  const $ = (id) => document.getElementById(id);
  const DASH = "—";
  const DEFAULT_SYMBOL = "AAPL";

  const num = (s) => parseFloat(s);
  const price2 = (s) => num(s).toFixed(2);
  const qty = (n) => n.toLocaleString("en-US");
  const time = (ms) => {
    const d = new Date(ms);
    const p = (n) => String(n).padStart(2, "0");
    return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  };

  // --- Order-book ladder --------------------------------------------------
  // Previous sizes per side, keyed by price, so a level that changes can flash.
  let prevSize = { bid: {}, ask: {} };

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
          `<span class="sz">${qty(level.size)}</span>` +
          `<span class="tot">${qty(level.cumulative)}</span>` +
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
      // No trades yet: show the instrument's real last price (from the quote that seeded the
      // book) rather than a dash, unstyled since it isn't one of our fills.
      lastEl.textContent = quoteLast !== null ? quoteLast.toFixed(2) : DASH;
      lastEl.className = "v";
    }
    return bestBid;
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
          `<span class="tz">${qty(t.size)}</span>` +
          `</div>`
        );
      })
      .join("");
  }

  // First snapshot after a symbol switch seeds the order ticket's price to something sane for
  // the new instrument, instead of leaving whatever the previous symbol's price was.
  let primeTicketPrice = false;

  function render(snapshot) {
    const maxDepth = Math.max(
      totalDepth(snapshot.bids),
      totalDepth(snapshot.asks),
    );
    $("asks").innerHTML = ladder(snapshot.asks, "ask", maxDepth);
    $("bids").innerHTML = ladder(snapshot.bids, "bid", maxDepth);
    const bestBid = renderStats(snapshot);
    renderTape(snapshot);

    if (primeTicketPrice && bestBid !== null) {
      $("price").value = bestBid.toFixed(2);
      updateLabel();
      primeTicketPrice = false;
    }
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

  // --- Symbol + quote -------------------------------------------------------
  let currentSymbol = DEFAULT_SYMBOL;
  let stream = null;
  // The instrument's real last price, used by the LAST stat until the first local fill.
  let quoteLast = null;

  function formatQuote(q) {
    const sign = num(q.change) >= 0 ? "▲" : "▼";
    const cls = num(q.change) >= 0 ? "pos" : "neg";
    const status = q.marketOpen ? "open" : "closed";
    return (
      `${q.name} · ${q.exchange} · ${q.currency} ` +
      `<span class="${cls}">${sign} ${q.last} (${q.changePercent}%)</span> ` +
      `· market ${status} · as of ${time(q.asOfMillis)}`
    );
  }

  async function loadQuote(symbol) {
    try {
      const response = await fetch(`api/${symbol}/quote`);
      if (!response.ok) return;
      const quote = await response.json();
      quoteLast = num(quote.last);
      if (!lastTradeTime) {
        $("st-last").textContent = quoteLast.toFixed(2);
        $("st-last").className = "v";
      }
      $("eyebrow").innerHTML = formatQuote(quote);
    } catch {
      // The book itself already streams fine without a label; leave the eyebrow as-is.
    }
  }

  let lastEventAt = Date.now();
  let events = 0;

  function connectStream(symbol) {
    if (stream) stream.close();
    prevSize = { bid: {}, ask: {} };
    lastTradeTime = 0;
    lastTs = 0;
    quoteLast = null;
    primeTicketPrice = true;
    events = 0;
    stream = new EventSource(`api/${symbol}/stream`);
    stream.onmessage = (e) => {
      lastEventAt = Date.now();
      events += 1;
      $("msgs").textContent = events;
      renderIfFresh(JSON.parse(e.data));
    };
    stream.onopen = () => setConnected(true);
    stream.onerror = () => setConnected(false);
  }

  async function switchSymbol(rawSymbol) {
    const symbol = rawSymbol.trim().toUpperCase();
    if (!symbol || symbol === currentSymbol) return;
    const probe = await fetch(`api/${symbol}/state`);
    if (!probe.ok) {
      const body = await probe.json().catch(() => ({}));
      $("result").textContent = `✕ ${body.error || "symbol not found"}`;
      $("result").className = "result err";
      return;
    }
    currentSymbol = symbol;
    $("symbol-input").value = symbol;
    const url = new URL(location.href);
    url.searchParams.set("symbol", symbol);
    history.replaceState(null, "", url);
    connectStream(symbol);
    loadQuote(symbol);
  }

  async function loadSymbolList() {
    try {
      const response = await fetch("api/symbols");
      const symbols = await response.json();
      $("symbol-list").innerHTML = symbols
        .map((s) => `<option value="${s.symbol}">${s.name}</option>`)
        .join("");
    } catch {
      // The picker still accepts free-text entry without the suggestion list.
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

  async function submitOrder() {
    const query = `side=${side}&price=${encodeURIComponent($("price").value)}&size=${encodeURIComponent($("size").value)}`;
    const result = $("result");
    try {
      const response = await fetch(`api/${currentSymbol}/order?${query}`, {
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

  // Enter switches immediately; "change" catches a datalist pick (and a blur after editing).
  $("symbol-input").addEventListener("keydown", (e) => {
    if (e.key === "Enter") switchSymbol($("symbol-input").value);
  });
  $("symbol-input").addEventListener("change", () =>
    switchSymbol($("symbol-input").value),
  );

  const initialSymbol =
    new URLSearchParams(location.search).get("symbol") || DEFAULT_SYMBOL;
  currentSymbol = initialSymbol.trim().toUpperCase();
  $("symbol-input").value = currentSymbol;
  loadSymbolList();
  connectStream(currentSymbol);
  loadQuote(currentSymbol);

  setInterval(() => {
    const age = (Date.now() - lastEventAt) / 1000;
    $("age").textContent = age < 1.5 ? "just now" : `${Math.round(age)}s ago`;
  }, 1000);
})();
