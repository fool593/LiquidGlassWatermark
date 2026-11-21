/* In-page tooltip layer + jargon annotation.
   Tooltips live outside .glass (which clips overflow) and re-run after the
   site's zh/en switch, because the i18n pass rewrites those elements. */
(function () {
  'use strict';

  var ZH = {
    tier: {
      term: 'HIGH/LOW 双画质',
      tip: 'HIGH 档：完整光学（AGSL 着色器，折射 / 色散 / 菲涅尔全开）；LOW 档：保留折射与扭曲，性能更省，低端机更流畅。'
    },
    fresnel: {
      term: '菲涅尔边缘光与高光',
      tip: '菲涅尔边缘光：玻璃边缘随视角变亮，用来强化「厚度感」；高光是光源在玻璃表面的镜面反射。'
    },
    motion: {
      term: 'vivo 双文件 / Google XMP 双格式',
      tip: 'vivo 动态照片 = 视频 + 照片两个文件；Google 动态照片把运动数据写进 XMP 元数据。两种格式都能识别。'
    },
    low: {
      term: 'LOW 画质档保留法线折射与扭曲',
      tip: 'LOW 档不是纯白磨砂：仍保留法线折射与扭曲，只是减少着色器开销。'
    },
    lowTag: {
      term: 'LOW 档保留折射 + 扭曲',
      tip: 'LOW 档：保留法线折射与扭曲，降低着色器开销，低端机更流畅。'
    },
    agsl: {
      term: 'AGSL',
      tip: 'AGSL：Android Graphics Shading Language，用于实时计算玻璃折射的 GPU 着色语言。'
    }
  };

  var EN = {
    tier: {
      term: 'HIGH & LOW tiers',
      tip: 'HIGH: full optics (AGSL shader — refraction, dispersion, Fresnel). LOW: keeps refraction and distortion with a lower GPU cost.'
    },
    fresnel: {
      term: 'Fresnel edge & highlights',
      tip: 'Fresnel edge light brightens the rim with viewing angle, reinforcing the sense of thickness; highlights are specular reflections of the light source.'
    },
    motion: {
      term: 'vivo dual-file / Google XMP formats',
      tip: 'vivo motion photos are a video + photo file pair; Google motion photos store motion data in XMP metadata. Both are supported.'
    },
    low: {
      term: 'LOW tier keeps normal refraction & distortion',
      tip: 'The LOW tier is not a plain white frosted overlay: it keeps normal refraction and distortion while reducing shader cost.'
    },
    lowTag: {
      term: 'LOW tier keeps refraction + distortion',
      tip: 'LOW: keeps normal refraction and distortion with lower shader cost, smoother on entry-level phones.'
    },
    agsl: {
      term: 'AGSL',
      tip: 'AGSL: Android Graphics Shading Language, used for real-time glass refraction.'
    }
  };

  function esc(s) {
    return String(s).replace(/[&<>]/g, function (c) {
      return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;';
    });
  }
  function escAttr(s) {
    return esc(s).replace(/"/g, '&quot;');
  }

  function annotate(el, items) {
    if (!el) return;
    var text = el.textContent || '';
    var found = [];
    items.forEach(function (it) {
      var i = text.indexOf(it.term);
      if (i >= 0) found.push({ i: i, term: it.term, tip: it.tip });
    });
    if (!found.length) return;
    found.sort(function (a, b) { return a.i - b.i; });

    var picked = [];
    found.forEach(function (m) {
      var overlap = picked.some(function (u) {
        return m.i < u.i + u.term.length && u.i < m.i + m.term.length;
      });
      if (!overlap) picked.push(m);
    });

    var html = '';
    var cursor = 0;
    picked.forEach(function (m) {
      html += esc(text.slice(cursor, m.i));
      html += '<span class="tip" tabindex="0" role="note" data-tip="' + escAttr(m.tip) + '">' + esc(m.term) + '</span>';
      cursor = m.i + m.term.length;
    });
    html += esc(text.slice(cursor));
    el.innerHTML = html;
  }

  function run() {
    /* Frosted grain on every glass surface (above the refraction canvas,
       below the content). Idempotent. */
    document.querySelectorAll('.glass').forEach(function (g) {
      if (!g.querySelector('.glass-frost')) {
        var f = document.createElement('div');
        f.className = 'glass-frost';
        f.setAttribute('aria-hidden', 'true');
        g.appendChild(f);
      }
    });

    var en = (document.documentElement.lang || 'zh').toLowerCase().indexOf('en') === 0;
    var T = en ? EN : ZH;

    annotate(document.querySelector('[data-i18n="feat.optics.d"]'), [T.tier, T.fresnel]);
    annotate(document.querySelector('[data-i18n="feat.motion.d"]'), [T.motion]);
    annotate(document.querySelector('[data-i18n="howto.3"] strong'), [T.low]);

    document.querySelectorAll('.goal .tag').forEach(function (tag) {
      var txt = tag.textContent || '';
      if (txt.indexOf('LOW') === 0) annotate(tag, [T.lowTag]);
      else if (txt.indexOf('HIGH') === 0) annotate(tag, [T.agsl]);
    });
  }

  /* ---- Tooltip layer ---- */
  var layer = null;
  function ensureLayer() {
    if (layer) return layer;
    layer = document.createElement('div');
    layer.id = 'tip-layer';
    layer.setAttribute('role', 'tooltip');
    document.body.appendChild(layer);
    return layer;
  }
  function show(el) {
    var text = el.getAttribute('data-tip');
    if (!text) return;
    var l = ensureLayer();
    l.textContent = text;
    l.classList.add('show');

    var r = el.getBoundingClientRect();
    var w = l.offsetWidth;
    var h = l.offsetHeight;
    var left = r.left + r.width / 2 - w / 2;
    left = Math.max(12, Math.min(left, window.innerWidth - w - 12));
    var top = r.top - h - 10;
    if (top < 8) top = r.bottom + 10;
    l.style.left = left + 'px';
    l.style.top = top + 'px';
  }
  function hide() {
    if (layer) layer.classList.remove('show');
  }

  function onOver(e) {
    var t = e.target && e.target.closest ? e.target.closest('.tip') : null;
    if (t) show(t);
  }
  function onOut(e) {
    var t = e.target && e.target.closest ? e.target.closest('.tip') : null;
    if (t && !t.contains(e.relatedTarget)) hide();
  }
  function onFocus(e) {
    var t = e.target && e.target.closest ? e.target.closest('.tip') : null;
    if (t) show(t);
  }

  document.addEventListener('pointerover', onOver, true);
  document.addEventListener('pointerout', onOut, true);
  document.addEventListener('focusin', onFocus, true);
  document.addEventListener('focusout', hide, true);
  window.addEventListener('scroll', hide, { passive: true });
  window.addEventListener('resize', hide);

  function boot() {
    setTimeout(run, 0);
    setTimeout(run, 350);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot, { once: true });
  } else {
    boot();
  }
  document.addEventListener('lg:lang', function () { setTimeout(run, 0); });
})();
