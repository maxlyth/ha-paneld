// Keep the first card in each CSS-masonry column on one top edge. Safari can retain a stale
// fragment origin after asynchronous card hydration, so every page schedules the same correction
// after its known layout changes. One animation frame plus one delayed pass covers WebKit's late
// fragmentation without observing unrelated subtree churn.
(function (global) {
  "use strict";
  var instances = Object.create(null);
  var nextFrame = global.requestAnimationFrame || function (callback) {
    return global.setTimeout(callback, 0);
  };

  function normalize(root) {
    var cards = Array.prototype.filter.call(root.children, function (node) {
      return node.classList && typeof node.classList.contains === "function" &&
        node.classList.contains("card") && node.style.display !== "none";
    });
    cards.forEach(function (card) {
      card.style.position = "";
      card.style.top = "";
    });
    if (cards.length < 2) return;

    var columns = {};
    cards.forEach(function (card) {
      var rect = card.getBoundingClientRect();
      var key = String(Math.round(rect.left));
      if (!columns[key]) columns[key] = { top: rect.top, cards: [] };
      columns[key].top = Math.min(columns[key].top, rect.top);
      columns[key].cards.push(card);
    });
    var keys = Object.keys(columns);
    if (keys.length < 2) return;

    var targetTop = Math.min.apply(null, keys.map(function (key) {
      return columns[key].top;
    }));
    keys.forEach(function (key) {
      var delta = columns[key].top - targetTop;
      if (delta <= 0.5) return;
      columns[key].cards.forEach(function (card) {
        card.style.position = "relative";
        card.style.top = (-delta) + "px";
      });
    });
  }

  function attach(rootId) {
    if (instances[rootId]) return instances[rootId];
    var root = global.document && global.document.getElementById(rootId);
    if (!root) return null;

    var settleTimer = null;
    var framePending = false;
    function schedule() {
      if (settleTimer !== null) {
        global.clearTimeout(settleTimer);
        settleTimer = null;
      }
      if (framePending) return;
      framePending = true;
      nextFrame(function () {
        framePending = false;
        normalize(root);
        settleTimer = global.setTimeout(function () {
          settleTimer = null;
          normalize(root);
        }, 120);
      });
    }

    instances[rootId] = schedule;
    if (global.addEventListener) global.addEventListener("resize", schedule);
    if (global.visualViewport && global.visualViewport.addEventListener) {
      global.visualViewport.addEventListener("resize", schedule);
    }
    var fonts = global.document && global.document.fonts;
    if (fonts && fonts.ready && typeof fonts.ready.then === "function") {
      fonts.ready.then(schedule);
    }
    return schedule;
  }

  global.CardColumnAlignment = { attach: attach };
})(window);
