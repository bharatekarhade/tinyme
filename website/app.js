/*
 * A very small template runtime for this page.
 *
 * The markup carries {{holes}} in text and attributes, plus four hooks:
 *   data-if="key"            shown when the value is truthy
 *   data-for="list" data-as="item"   repeats its <template> once per item
 *   data-ref="{{fn}}"        fn(element) is called once
 *   data-on-click="{{fn}}"   click handler
 *   data-style="..."         a style attribute with holes in it
 * Component (below) computes every value in renderVals(); render() writes them into the page.
 */
(function () {
  'use strict';

  var HOLE = /\{\{\s*([^}]+?)\s*\}\}/g;
  var WHOLE = /^\{\{\s*([^}]+?)\s*\}\}$/;

  function lookup(scope, path) {
    if (path === 'true') return true;
    if (path === 'false') return false;
    return path.split('.').reduce(function (o, k) { return o == null ? undefined : o[k]; }, scope);
  }

  function fill(tpl, scope) {
    var whole = tpl.match(WHOLE);
    if (whole) return lookup(scope, whole[1]);
    return tpl.replace(HOLE, function (_, p) {
      var v = lookup(scope, p);
      return v == null ? '' : String(v);
    });
  }

  function Binder(root) {
    this.items = [];
    this.collect(root);
  }

  Binder.prototype.collect = function (node) {
    var self = this;
    if (node.nodeType === 3) {
      if (node.nodeValue.indexOf('{{') !== -1) self.items.push({ kind: 'text', node: node, tpl: node.nodeValue });
      return;
    }
    if (node.nodeType !== 1 && node.nodeType !== 11) return;
    if (node.nodeType === 1) {
      if (node.tagName === 'TEMPLATE') return;
      if (node.hasAttribute('data-for')) {
        self.items.push({
          kind: 'for', el: node, key: node.getAttribute('data-for'), as: node.getAttribute('data-as'),
          tpl: node.querySelector(':scope > template'), rows: []
        });
        return;
      }
      if (node.hasAttribute('data-if')) self.items.push({ kind: 'if', el: node, key: node.getAttribute('data-if') });
      Array.prototype.slice.call(node.attributes).forEach(function (attr) {
        var name = attr.name, val = attr.value;
        if (val.indexOf('{{') === -1) return;
        if (name === 'data-ref') {
          node.removeAttribute(name);
          self.items.push({ kind: 'ref', el: node, tpl: val, done: false });
        } else if (name === 'data-on-click') {
          node.removeAttribute(name);
          var item = { kind: 'event', el: node, tpl: val, scope: null };
          node.addEventListener('click', function (e) {
            var fn = item.scope && fill(item.tpl, item.scope);
            if (typeof fn === 'function') fn(e);
          });
          self.items.push(item);
        } else if (name === 'data-style') {
          self.items.push({ kind: 'attr', el: node, name: 'style', tpl: val });
        } else {
          self.items.push({ kind: 'attr', el: node, name: name, tpl: val });
        }
      });
    }
    Array.prototype.slice.call(node.childNodes).forEach(function (child) { self.collect(child); });
  };

  Binder.prototype.update = function (scope) {
    this.items.forEach(function (b) {
      switch (b.kind) {
        case 'text': {
          var t = fill(b.tpl, scope);
          t = t == null ? '' : String(t);
          if (b.node.nodeValue !== t) b.node.nodeValue = t;
          break;
        }
        case 'attr': {
          var v = fill(b.tpl, scope);
          v = v == null ? '' : String(v);
          if (b.el.getAttribute(b.name) !== v) b.el.setAttribute(b.name, v);
          break;
        }
        case 'if':
          b.el.style.display = lookup(scope, b.key) ? 'contents' : 'none';
          break;
        case 'ref':
          if (!b.done) {
            b.done = true;
            var fn = fill(b.tpl, scope);
            if (typeof fn === 'function') fn(b.el);
          }
          break;
        case 'event':
          b.scope = scope;
          break;
        case 'for': {
          var list = lookup(scope, b.key) || [];
          // Rows are kept by position, so items added at the end animate in and the rest stay put.
          while (b.rows.length < list.length) {
            var wrap = document.createElement('div');
            wrap.style.display = 'contents';
            wrap.appendChild(b.tpl.content.cloneNode(true));
            b.el.appendChild(wrap);
            b.rows.push({ wrap: wrap, binder: new Binder(wrap) });
          }
          while (b.rows.length > list.length) b.el.removeChild(b.rows.pop().wrap);
          for (var i = 0; i < list.length; i++) {
            var rowScope = Object.create(scope);
            rowScope[b.as] = list[i];
            rowScope.$index = i;
            b.rows[i].binder.update(rowScope);
          }
          break;
        }
      }
    });
  };

  var queued = false;
  var component = null;
  var binder = null;
  var mounted = false;

  function render() {
    queued = false;
    binder.update(component.renderVals());
    if (!mounted) {
      mounted = true;
      if (component.componentDidMount) component.componentDidMount();
    } else if (component.componentDidUpdate) {
      component.componentDidUpdate();
    }
  }

  function schedule() {
    if (queued || !binder) return;
    queued = true;
    Promise.resolve().then(render);
  }

  window.DCLogic = function DCLogic(props) {
    this.props = props || {};
    this.state = {};
  };
  window.DCLogic.prototype.setState = function (patch) {
    Object.assign(this.state, typeof patch === 'function' ? patch(this.state) : patch);
    schedule();
  };
  window.DCLogic.prototype.forceUpdate = schedule;

  window.__mountPage = function (Component, root) {
    component = new Component({});
    binder = new Binder(root);
    render();
  };
})();

// ---------------------------------------------------------------------------
// The page: scroll story, phone screens, the person's scenes and the voice stream.
// ---------------------------------------------------------------------------
class Component extends DCLogic {
  constructor(props) {
    super(props);
    this.state = { step: 0, sub: 0, fit: 1, mobile: false };
    this.markers = [];
    var self = this;
    this.storyRefs = {
      story: function (el) { self.story = el; if (el) self.measure(); }
    };
    this.markerRefs = Component.labels().map(function (_, i) {
      return function (el) { self.markers[i] = el; };
    });
    this.goers = Component.labels().map(function (_, i) {
      return function () { self.jump(i); };
    });
    this.goFirst = function () { self.jump(1); };
    // Scene videos: only the ones on screen (or about to be) play.
    this.vids = [];
    this.vrefs = {};
    for (var vi = 0; vi < 8; vi++) {
      (function (k) {
        self.vrefs['v' + k] = function (el) {
          self.vids[k] = el;
          if (el) { el.muted = true; el.defaultMuted = true; el.playsInline = true; }
        };
      })(vi);
    }
    this.onScroll = function () {
      if (self.raf) return;
      self.raf = requestAnimationFrame(function () { self.raf = 0; self.measure(); });
    };
  }

  // Each step's chat. `at` is how far through the step (0–1) the item appears.
  static script() {
    return [
      { step: 1, at: 0.32, role: 'user', text: 'Had ramen with Aiko after work' },
      { step: 1, at: 0.5, role: 'agent', text: 'Logged dinner, about 640 kcal. You have 410 left today.' },
      { step: 1, at: 0.62, role: 'chip', text: 'Dinner · 640 kcal · with Aiko' },
      { step: 2, at: 0.32, role: 'user', text: 'Make me a calories card for every day' },
      { step: 2, at: 0.5, role: 'agent', text: 'Here it is, built from what you’ve logged. Put it on Home?' },
      { step: 2, at: 0.62, role: 'card', text: '' },
      { step: 4, at: 0.32, role: 'user', text: 'Gym tomorrow at 7:30. Lunch with Aiko Friday at 1.' },
      { step: 4, at: 0.5, role: 'agent', text: 'Both are on your Upcoming. Logging the workout will tick the gym off.' },
      { step: 4, at: 0.58, role: 'chip', text: 'Tomorrow 7:30 · Gym' },
      { step: 4, at: 0.64, role: 'chip', text: 'Fri 13:00 · Lunch with Aiko' },
      { step: 5, at: 0.32, role: 'user', text: 'When did I last see Kenji?' },
      { step: 5, at: 0.5, role: 'agent', text: 'Sunday, Sept 27: ramen in Ebisu. Before that, his birthday dinner in March.' },
      { step: 6, at: 0.32, role: 'user', text: 'How did I sleep this week?' },
      { step: 6, at: 0.5, role: 'agent', text: '6.4 hours a night, 40 minutes less than last week. Thursday was the short one.' },
      { step: 6, at: 0.62, role: 'chip', text: 'From Apple Health · 7 nights' },
      { step: 7, at: 0.32, role: 'user', text: 'Export everything I’ve told you' },
      { step: 7, at: 0.5, role: 'agent', text: 'Done. One file with every entry, plan and memory. It’s yours.' },
      { step: 7, at: 0.62, role: 'chip', text: 'tinyme-export.zip · ready' }
    ];
  }

  // mx, my = where his mouth is, as fractions of each video frame.
  static scenes() {
    return [
      { mx: 0.506, my: 0.21 },
      { mx: 0.571, my: 0.21 },
      { mx: 0.57, my: 0.219 },
      { mx: 0.675, my: 0.274 },
      { mx: 0.506, my: 0.21 },
      { mx: 0.488, my: 0.219 },
      { mx: 0.517, my: 0.269 },
      { mx: 0.592, my: 0.21 }
    ];
  }

  static labels() {
    return ['Intro', 'Talk, and it’s logged', 'Trackers that build themselves', 'Your Home screen',
      'Your week, ahead', 'Ask anything', 'Apple Health', 'Yours to keep'];
  }

  static notes() {
    return ['', 'Numbers picked out, people linked', 'A tracker designed from your logs',
      'Added to your Home screen', 'Plans land on Upcoming', 'Answers counted from your records',
      'Apple Health flows in', 'Your data, on your own server'];
  }

  componentDidMount() {
    document.addEventListener('scroll', this.onScroll, true);
    window.addEventListener('resize', this.onScroll);
    this.measure();
    this.syncVideos();
  }

  componentDidUpdate() {
    this.syncVideos();
  }

  syncVideos() {
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    var f = this.state.step + this.state.sub;
    for (var i = 0; i < this.vids.length; i++) {
      var v = this.vids[i];
      if (!v) continue;
      var near = !reduce && f > i - 0.35 && f < i + 1.35;
      if (near && v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
      if (!near && !v.paused) v.pause();
    }
  }

  componentWillUnmount() {
    document.removeEventListener('scroll', this.onScroll, true);
    window.removeEventListener('resize', this.onScroll);
    if (this.raf) cancelAnimationFrame(this.raf);
  }

  // The story's scroll position is the only clock: step = which slice of the section, sub = how far into it.
  measure() {
    if (!this.story) return;
    var n = Component.labels().length;
    var r = this.story.getBoundingClientRect();
    var vh = window.innerHeight || document.documentElement.clientHeight;
    var dist = Math.max(1, r.height - vh);
    var p = Math.min(0.99999, Math.max(0, -r.top / dist));
    var f = p * n;
    var step = Math.floor(f);
    var sub = Math.round((f - step) * 100) / 100;
    var vw = window.innerWidth || document.documentElement.clientWidth;
    var mobile = vw <= 860;
    // The phone is drawn at 312×660 and scaled to fill the height the stage leaves for it.
    var fit = mobile
      ? Math.min(0.8, Math.max(0.45, (vh * 0.52) / 660))
      : Math.min(1.1, Math.max(0.6, (vh - 190) / 660));
    fit = Math.round(fit * 100) / 100;
    var st = this.state;
    if (step !== st.step || sub !== st.sub || fit !== st.fit || mobile !== st.mobile) {
      this.setState({ step: step, sub: sub, fit: fit, mobile: mobile });
    }
  }

  // Lands on a step with its reply already showing.
  jump(i) {
    var el = this.markers[i];
    if (!el) return;
    var reduce = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
  }

  renderVals() {
    var step = this.state.step;
    var sub = this.state.sub;
    var n = Component.labels().length;
    var all = Component.script();
    var shown = all.filter(function (m) {
      return m.step < step || (m.step === step && sub >= m.at);
    });
    var mine = all.filter(function (m) { return m.step === step; });
    var userAt = mine.length ? mine[0].at : 1;
    var replyAt = mine.length > 1 ? mine[1].at : 1;
    var typing = mine.length > 1 && sub >= userAt && sub < replyAt;
    var listening = mine.length > 0 && sub < userAt;
    var messages = shown.map(function (m) {
      return { text: m.text, isUser: m.role === 'user', isAgent: m.role === 'agent',
        isChip: m.role === 'chip', isCard: m.role === 'card' };
    });
    var cls = {};
    for (var i = 0; i < n; i++) cls['c' + i] = i === step ? 'is-on' : '';
    var self = this;
    var steps = Component.labels().map(function (label, i) {
      return {
        label: 'Step ' + (i + 1) + ': ' + label,
        cls: i === step ? 'is-on' : '',
        go: self.goers[i],
        ref: self.markerRefs[i],
        // Marker sits 70% into its step, so a jump lands with the reply already in.
        top: 'calc(700vh * ' + ((i + (i === 0 ? 0 : 0.7)) / n).toFixed(4) + ')'
      };
    });
    var onHome = step === 0 || step === 3;
    var fit = this.state.fit;
    // Intro: the phone rises, straightens and grows to full size as you scroll into the story.
    var t = step === 0 ? Math.min(1, sub / 0.85) : 1;
    var e = 1 - Math.pow(1 - t, 3);
    // Scroll-linked sway: the phone leans one way through a step and back, alternating.
    var sway = 0;
    var lift = 'perspective(1800px) translateY(' + Math.round((1 - e) * 90) + 'px) rotateX(' +
      ((1 - e) * 16).toFixed(2) + 'deg) rotateY(' + sway.toFixed(2) + 'deg) scale(' + (0.7 + 0.3 * e).toFixed(3) + ')';
    var drillIn = step === 3 && sub >= 0.62;
    // Scene geometry, in px inside .tm-scene: the person panel, then the phone; 50px of room above for the note.
    var W = 312 * fit, H = 660 * fit;
    // Scenes: cut-out illustrations, bottom-aligned in a box beside the phone. Mouth spots are fractions of each image.
    var scenes = Component.scenes();
    var ph = H * 0.84, pw = ph * 864 / 1088;
    var top = 50 + H - ph;
    var ml = 0;
    var px = W + 88;
    var cur = scenes[step];
    var mouthX = cur.mx * pw, mouthY = cur.my * ph;
    var sx = px + mouthX - 8, sy = top + mouthY;
    var ex = W * 0.42, ey = 50 + H - 64 * fit;
    var cx = (sx + ex) / 2, cy = Math.min(sy, ey) - 110;
    var words = [];
    var said = mine.length && mine[0].role === 'user' ? mine[0] : null;
    if (said && listening) {
      var list = said.text.split(' ');
      var s0 = Math.max(0, (sub - 0.03) / (said.at - 0.03));
      var gap = 0.6 / list.length;
      words = list.map(function (text, i) {
        var p = Math.min(1, Math.max(0, (s0 - i * gap) / 0.4));
        var q = 1 - p;
        var x = q * q * sx + 2 * q * p * cx + p * p * ex;
        var y = q * q * sy + 2 * q * p * cy + p * p * ey;
        var o = p <= 0 || p >= 1 ? 0 : Math.min(1, p / 0.12, (1 - p) / 0.15);
        return { text: text, x: Math.round(x) + 'px', y: Math.round(y) + 'px', o: o.toFixed(2),
          s: (1 - 0.35 * p).toFixed(2), r: (Math.sin(p * Math.PI) * (i % 2 ? 6 : -6)).toFixed(1) + 'deg' };
      });
    }
    // Continuous position through the story drives the crossfade and drift, so scenes blend as you scroll.
    var fpos = step + sub, fw = 0.2;
    var sc = {};
    scenes.forEach(function (_, i) {
      var inn = i === 0 ? 1 : Math.min(1, Math.max(0, (fpos - i + fw / 2) / fw));
      var out = i === scenes.length - 1 ? 1 : Math.min(1, Math.max(0, (i + 1 + fw / 2 - fpos) / fw));
      var d = Math.max(-0.7, Math.min(0.7, fpos - (i + 0.5)));
      sc['o' + i] = (inn * out).toFixed(3);
      sc['x' + i] = Math.round(-d * 24) + 'px';
      sc['s' + i] = (1 - Math.abs(d) * 0.04).toFixed(3);
    });
    var propFor = ['mug', 'ramen', 'apple', 'mug', 'gym', 'photo', 'watch', 'laptop'][step];
    var backFor = ['', 'lantern', '', '', 'calendar', 'frame', 'moon', 'server'][step];
    var props = {};
    ['mug', 'ramen', 'apple', 'gym', 'photo', 'watch', 'laptop'].forEach(function (k) { props[k] = k === propFor ? 'is-on' : ''; });
    ['lantern', 'calendar', 'frame', 'moon', 'server'].forEach(function (k) { props[k] = k === backFor ? 'is-on' : ''; });
    var moments = ['walking with a takeaway coffee and waving', 'eating ramen, lifting noodles with chopsticks',
      'cooking breakfast, an omelette and avocado toast', 'relaxing with his feet up and a coffee',
      'doing a bicep curl in gym clothes', 'walking with a gift, wondering when he last saw Kenji',
      'waking up and stretching, smartwatch on', 'packing his laptop into a box beside his home server'];
    // Plans: once the reply is in, the chat drops away to show them on Upcoming.
    var upIn = step === 4 && sub >= 0.74;
    var note = Component.notes()[step] || '';
    return {
      storyRefs: this.storyRefs,
      steps: steps,
      cls: cls,
      goFirst: this.goFirst,
      introCls: step === 0 ? 'is-intro' : '',
      messages: messages,
      isEmpty: messages.length === 0,
      typing: typing,
      status: typing ? 'Typing…' : (listening ? 'Listening…' : 'Here for you'),
      composer: listening ? 'Listening…' : 'Message TinyMe',
      micCls: listening && !onHome ? 'is-rec' : '',
      chatCls: onHome || upIn ? 'is-away' : '',
      upCls: upIn ? 'is-in' : '',
      upNew1: upIn && sub >= 0.8 ? 'is-in' : '',
      upNew2: upIn && sub >= 0.86 ? 'is-in' : '',
      drillCls: drillIn ? 'is-in' : '',
      homeCls: drillIn ? 'is-back' : '',
      pressCls: step === 3 && sub >= 0.52 && sub < 0.62 ? 'is-on' : '',
      fit: fit,
      words: words,
      talkCls: said && listening ? 'is-talk' : '',
      sc: sc,
      vrefs: this.vrefs,
      waveX: Math.round(mouthX - 50) + 'px',
      waveY: Math.round(mouthY - 26) + 'px',
      props: props,
      panelW: Math.round(pw) + 'px',
      panelML: Math.round(ml) + 'px',
      panelH: Math.round(ph) + 'px',
      personLabel: 'A man ' + moments[step],
      boxW: Math.round(312 * fit) + 'px',
      boxH: Math.round(660 * fit) + 'px',
      lift: lift,
      newCardCls: step > 3 || (onHome && sub >= 0.25) ? 'is-in' : '',
      note: note,
      hasNote: note.length > 0,
      phoneLabel: upIn
        ? 'TinyMe Upcoming screen on an iPhone: this week, with the gym and lunch plans just added'
        : onHome
        ? 'TinyMe Home screen on an iPhone: Journal, Calories and Gym cards, with Day, Week, Month and Year'
        : 'TinyMe chat on an iPhone, following along as you scroll'
    };
  }
}


// Values from config.js: links, contact address and the year.
(function () {
  var c = window.TINYME_CONFIG || {};
  document.querySelectorAll('[data-config-href]').forEach(function (el) {
    var v = c[el.getAttribute('data-config-href')];
    if (v) el.setAttribute('href', v); else el.style.display = 'none';
  });
  document.querySelectorAll('[data-config-mailto]').forEach(function (el) {
    var v = c[el.getAttribute('data-config-mailto')];
    if (v) el.setAttribute('href', 'mailto:' + v); else el.style.display = 'none';
  });
  document.querySelectorAll('[data-config-text]').forEach(function (el) {
    var v = c[el.getAttribute('data-config-text')];
    if (v != null && v !== '') el.textContent = v;
  });
})();

window.__mountPage(Component, document.getElementById('page'));
