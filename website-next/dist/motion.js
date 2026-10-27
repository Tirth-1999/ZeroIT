/* Original ZeroIT vector-frill study. No copied reference implementation. */
(() => {
  'use strict';
  const stage = document.querySelector('#ribbon-stage');
  const namespace = 'http://www.w3.org/2000/svg';
  const state = { progress: 0.24, velocity: 0 };
  const node = (tag, attributes) => {
    const element = document.createElementNS(namespace, tag);
    Object.entries(attributes || {}).forEach(([name, value]) => element.setAttribute(name, value));
    return element;
  };
  let svg, edge, band, baseline, letters, creamBelow;
  let frillHeight = 320;
  const curve = progress => {
    const amplitude = 35 + Math.sin(progress * Math.PI) * 29 + state.velocity;
    const phase = -0.45 + progress * 2.5;
    const frequency = Math.PI * 2 / 1400;
    const y = x => frillHeight / 2 + Math.sin(x * frequency + phase) * amplitude;
    const slope = x => Math.cos(x * frequency + phase) * amplitude * frequency;
    const xs = [-110, 245, 600, 955, 1310];
    let path = `M ${xs[0]} ${y(xs[0]).toFixed(2)}`;
    for (let index = 1; index < xs.length; index++) {
      const a = xs[index - 1], b = xs[index], third = (b - a) / 3;
      path += ` C ${(a + third).toFixed(2)} ${(y(a) + slope(a) * third).toFixed(2)}, ${(b - third).toFixed(2)} ${(y(b) - slope(b) * third).toFixed(2)}, ${b} ${y(b).toFixed(2)}`;
    }
    return path;
  };
  function render() {
    if (!stage || !baseline) return;
    const d = curve(state.progress);
    [edge, band, baseline].forEach(path => path.setAttribute('d', d));
    creamBelow.setAttribute('d', d + ` L 1310 ${frillHeight + 1000} L -110 ${frillHeight + 1000} Z`);
    letters.setAttribute('startOffset', String(-120 - state.progress * 500));
    stage.dataset.frillProgress = state.progress.toFixed(3);
  }
  if (stage && !stage.querySelector('[data-zeroit-frill]')) {
    svg = node('svg', { viewBox: '0 0 1200 320', width: '100%', height: '100%', preserveAspectRatio: 'xMidYMid meet', 'aria-hidden': 'true', focusable: 'false', 'data-zeroit-frill': '' });
    svg.style.cssText = 'display:block;width:100%;height:100%;overflow:visible;pointer-events:none';
    const defs = node('defs');
    baseline = node('path', { id: 'zeroit-frill-baseline' }); defs.append(baseline);
    edge = node('path', { fill: 'none', stroke: '#153f30', 'stroke-width': 100, 'stroke-linecap': 'round' });
    band = node('path', { fill: 'none', stroke: '#c9ed91', 'stroke-width': 96, 'stroke-linecap': 'round' });
    const text = node('text', { fill: '#153f30', 'font-size': 29, 'font-weight': 900, 'font-family': 'inherit', 'letter-spacing': 1.8, dy: 10 });
    letters = node('textPath', { href: '#zeroit-frill-baseline' });
    letters.textContent = 'GOOD TIMES  /  ZERO DRAMA  /  EVERY CENT  /  '.repeat(9);
    creamBelow = node('path', { fill: 'var(--cream)' });
    text.append(letters); svg.append(defs, creamBelow, edge, band, text); stage.append(svg);
    const sizeFrill = () => {
      if (!stage.clientWidth) return;
      frillHeight = 1200 * stage.clientHeight / stage.clientWidth;
      svg.setAttribute('viewBox', `0 0 1200 ${frillHeight}`); render();
    };
    sizeFrill();
    window.addEventListener('resize', sizeFrill, { passive: true });
  }
  // One deterministic, reversible journey: gravity-like arcs connect measured visual landmarks.
  // Artwork is decorative; no layout, focus, scrolling or product state is driven by this path.
  function createJourney(gsap, ScrollTrigger) {
    const spark = document.querySelector('.scene-spark');
    const wordmark = document.querySelector('.closing-wordmark');
    const closingSection = document.querySelector('.closing');
    const dot = wordmark?.querySelector('.wordmark-dot');
    const textNode = wordmark && Array.from(wordmark.childNodes).find(n => n.nodeType === Node.TEXT_NODE && n.textContent.trim());
    if (!spark || !dot || !textNode || !closingSection) return () => {};
    const originalDotStyle = dot.getAttribute('style');
    const originalOverflow = closingSection.style.overflow;
    closingSection.style.overflow = 'visible';
    const word = document.createElement('span'); word.className = 'journey-wordmark-word';
    const characters = Array.from(textNode.textContent.trim(), character => {
      const letter = document.createElement('span'); letter.className = 'journey-letter'; letter.textContent = character;
      word.append(letter); return letter;
    });
    textNode.before(word); textNode.remove();
    wordmark.classList.add('journey-wordmark-active');
    const star = document.createElement('div'); star.className = 'journey-star';
    star.setAttribute('aria-hidden', 'true');
    // Opposing outline stays visible even when a pale star crosses a pale receipt.
    star.style.clipPath = 'none'; star.style.background = 'transparent';
    const starSvg = node('svg', { viewBox: '0 0 100 100', width: '100%', height: '100%', 'aria-hidden': 'true' });
    starSvg.append(node('polygon', { points: '48,3 57,35 84,14 66,43 97,50 65,57 85,85 56,67 50,97 43,66 15,86 34,58 3,50 35,43 14,14 43,35',
      fill: 'currentColor', stroke: 'var(--journey-outline)', 'stroke-width': 4, 'stroke-linejoin': 'round' }));
    star.append(starSvg); document.body.append(star);
    document.body.classList.add('journey-active');
    const driver = { scroll: window.scrollY };
    const clamp = (value, low = 0, high = 1) => Math.min(high, Math.max(low, value));
    const smooth = value => value * value * (3 - 2 * value);
    const lerp = (a, b, t) => a + (b - a) * t;
    // offset geometry deliberately ignores the independent decorative entrance transforms.
    const box = element => {
      let left = 0, top = 0, current = element;
      while (current) { left += current.offsetLeft; top += current.offsetTop; current = current.offsetParent; }
      return { left, top, width: element.offsetWidth, height: element.offsetHeight };
    };
    let points = [], metrics;
    const measure = () => {
      const width = document.documentElement.clientWidth, height = window.innerHeight;
      const origin = box(spark);
      const closingRect = closingSection.getBoundingClientRect(), markRect = wordmark.getBoundingClientRect();
      const mark = box(wordmark), wordBox = box(word), ball = box(dot);
      const radius = ball.width / 2, size = star.offsetWidth;
      const max = ScrollTrigger.maxScroll(window);
      const closingStart = finale.start;
      const ballY = box(finale.spacer).top + markRect.top - closingRect.top + dot.offsetTop + radius;
      const endX = Math.min(wordBox.width + radius * 1.4, mark.width - ball.width);
      const font = getComputedStyle(wordmark);
      const canvas = document.createElement('canvas').getContext('2d');
      canvas.font = `${font.fontWeight} ${font.fontSize} ${font.fontFamily}`;
      const glyph = canvas.measureText('T');
      // CSS line-box leading + font baseline gives punctuation the same bottom as the T glyph.
      const ascent = glyph.fontBoundingBoxAscent ?? parseFloat(font.fontSize) * 0.9;
      const descent = glyph.fontBoundingBoxDescent ?? parseFloat(font.fontSize) * 0.2;
      const baselineOffset = (parseFloat(font.lineHeight) - ascent - descent) / 2 + ascent;
      const punctuationY = baselineOffset + (glyph.actualBoundingBoxDescent || 0) - radius;
      const settleY = Math.max(0, closingSection.offsetHeight - (markRect.top - closingRect.top + baselineOffset + (glyph.actualBoundingBoxDescent || 0)));
      const endY = punctuationY - (dot.offsetTop + radius);
      metrics = { width, height, max, closingStart, closingEnd: finale.end, size, radius, endX, endY, settleY,
        ballX: markRect.left + dot.offsetLeft + radius, ballY,
        wordmarkY: box(finale.spacer).top + markRect.top - closingRect.top,
        fontSize: parseFloat(getComputedStyle(wordmark).fontSize), frillBox: stage ? box(stage) : null,
        sections: Array.from(document.querySelectorAll('main > section'), section => {
          const rect = box(section);
          let element = section, rgb;
          while (element) {
            rgb = getComputedStyle(element).backgroundColor.match(/[\d.]+/g)?.map(Number);
            if (rgb && (rgb.length < 4 || rgb[3] > 0)) break;
            element = element.parentElement;
          }
          rgb ||= [245, 240, 217];
          return { top: rect.top, bottom: rect.top + rect.height, dark: (rgb[0] * 0.21 + rgb[1] * 0.72 + rgb[2] * 0.07) < 120 };
        }),
        letterCenters: characters.map(letter => box(letter).left - ball.left + letter.offsetWidth / 2) };
      const safeX = x => clamp(x, size * 0.7, width - size * 0.7);
      points = [{ at: 0, x: origin.left + origin.width / 2, y: origin.top + origin.height / 2 }];
      const resting = Math.max(1, origin.top - height * 0.28);
      points.push({ at: resting, x: points[0].x, y: points[0].y });
      const add = (element, side, fraction = 0.15) => {
        if (!element) return;
        const rect = box(element), y = rect.top + rect.height * fraction;
        const at = Math.max(points.at(-1).at + 1, y - height * 0.5);
        if (at >= closingStart) return;
        points.push({ at, x: safeX(side === 'left' ? rect.left + size : rect.left + rect.width - size), y });
      };
      add(stage, 'left', 0.48);
      document.querySelectorAll('.moment-art').forEach((frame, index) => add(frame, index % 2 ? 'left' : 'right', 0.1));
      add(document.querySelector('.math-machine'), 'right', 0.03);
      document.querySelectorAll('.principle-list article').forEach((article, index) => add(article, index % 2 ? 'right' : 'left', 0.02));
      // Hold above the finale, then catch the rolling ball in its last portion of travel.
      points.push({ at: Math.max(points.at(-1).at + 1, closingStart), x: safeX(width * 0.9), y: metrics.wordmarkY - size * 0.9 });
    };
    const renderJourney = () => {
      if (!metrics || document.hidden) return;
      const s = driver.scroll, m = metrics;
      const p = clamp((s - m.closingStart) / Math.max(1, m.closingEnd - m.closingStart));
      const curl = clamp((p - 0.7) / 0.3);
      const roll = clamp(p / 0.7);
      const ballX = curl ? m.endX * (0.82 + 0.18 * Math.sin(curl * Math.PI / 2)) : m.endX * 0.82 * roll;
      const settleY = m.settleY * smooth(curl);
      word.style.transform = `translateY(${settleY}px)`;
      const ballY = m.endY * (1 - Math.cos(curl * Math.PI / 2)) + settleY + Math.sin(curl * Math.PI) * Math.min(m.fontSize * 0.06, 22);
      dot.style.transform = `translate3d(${ballX}px,${ballY}px,0) rotate(${p * 720}deg) scale(${1 + Math.sin(Math.PI * clamp((p - 0.88) / 0.12)) * 0.12})`;
      characters.forEach((letter, index) => {
        const distance = (ballX + m.radius - m.letterCenters[index]) / (m.fontSize * 0.36);
        const wave = Math.exp(-distance * distance * 2) * (1 - smooth(clamp((p - 0.86) / 0.14)));
        letter.style.transform = `translateY(${-wave * m.fontSize * 0.16}px) rotate(${-wave * 5}deg)`;
      });
      let x, y, squash = 0, scale = 1;
      if (s <= points.at(-1).at) {
        let index = 0;
        while (index < points.length - 2 && s > points[index + 1].at) index++;
        const a = points[index], b = points[index + 1];
        const t = clamp((s - a.at) / Math.max(1, b.at - a.at));
        x = lerp(a.x, b.x, smooth(t));
        const envelope = Math.sin(Math.PI * t);
        const personality = (Math.sin(index * 2.17 + 0.8) + 1) / 2;
        const arc = index ? Math.min(m.height * (0.09 + personality * 0.14), 145) : 0;
        const drift = index ? Math.sin(t * Math.PI * 2) * envelope * Math.min(m.width * 0.045, 65) * (index % 2 ? 1 : -1) : 0;
        x = clamp(x + drift, m.size * 0.7, m.width - m.size * 0.7);
        y = lerp(a.y, b.y, t) - arc * 4 * t * (1 - t) + Math.sin(t * Math.PI * 3) * envelope * arc * 0.2;
        squash = index ? Math.pow(1 - t, 9) * 0.16 : 0;
      } else {
        const a = points.at(-1), t = smooth(clamp(p / 0.94));
        x = lerp(a.x, m.ballX + ballX, t);
        const advance = clamp(s - m.closingStart, 0, m.closingEnd - m.closingStart);
        y = lerp(a.y + advance, m.ballY + ballY + advance, t) - Math.sin(Math.PI * t) * m.height * 0.04;
        scale = 1 - smooth(clamp((p - 0.88) / 0.12));
      }
      const section = m.sections.find(section => y >= section.top && y <= section.bottom);
      star.style.transform = `translate3d(${x}px,${y - s}px,0) translate(-50%,-50%) rotate(${s * 0.29 + Math.sin(s / 230) * 38}deg) scale(${(1 + squash) * scale},${(1 - squash) * scale})`;
      let dark = section?.dark;
      if (m.frillBox && y >= m.frillBox.top && y <= m.frillBox.top + m.frillBox.height) {
        const localX = (x - m.frillBox.left) * 1200 / m.frillBox.width;
        const amplitude = 35 + Math.sin(state.progress * Math.PI) * 29 + state.velocity;
        const topOfBand = frillHeight / 2 + Math.sin(localX * Math.PI * 2 / 1400 - 0.45 + state.progress * 2.5) * amplitude - 50;
        dark = y < m.frillBox.top + topOfBand * m.frillBox.width / 1200;
      }
      star.style.color = dark ? '#f5f0d9' : '#144d3b';
      star.style.setProperty('--journey-outline', dark ? '#144d3b' : '#f5f0d9');
      star.style.opacity = scale < 0.01 ? '0' : '1';
      star.dataset.journeyProgress = (s / Math.max(1, m.max)).toFixed(3);
      wordmark.dataset.rollProgress = p.toFixed(3);
    };
    const finale = ScrollTrigger.create({ id: 'zeroit-finale-stage', trigger: closingSection,
      start: () => 'bottom bottom-=' + Math.min(120, window.innerHeight * 0.15), end: () => '+=' + Math.min(650, Math.max(240, window.innerHeight * 0.65)),
      pin: true, pinSpacing: true, invalidateOnRefresh: true });
    measure();
    const resumeJourney = () => { if (!document.hidden) renderJourney(); };
    document.addEventListener('visibilitychange', resumeJourney);
    const tween = gsap.fromTo(driver, { scroll: 0 }, { scroll: () => ScrollTrigger.maxScroll(window), ease: 'none',
      onUpdate: renderJourney, scrollTrigger: { id: 'zeroit-star-journey', start: 0, end: 'max', scrub: 0.35,
        invalidateOnRefresh: true, onRefresh: () => { measure(); renderJourney(); } } });
    renderJourney();
    return () => {
      tween.scrollTrigger?.kill(); tween.kill(); finale.kill(true); star.remove();
      document.removeEventListener('visibilitychange', resumeJourney);
      word.replaceWith(textNode); wordmark.classList.remove('journey-wordmark-active');
      closingSection.style.overflow = originalOverflow;
      document.body.classList.remove('journey-active');
      if (originalDotStyle === null) dot.removeAttribute('style'); else dot.setAttribute('style', originalDotStyle);
      delete wordmark.dataset.rollProgress;
    };
  }
  // Static geometry above survives unavailable GSAP, reduced motion and failed vendor requests.
  if (!window.gsap || !window.ScrollTrigger) return;
  const { gsap, ScrollTrigger } = window;
  gsap.registerPlugin(ScrollTrigger);
  const media = gsap.matchMedia();
  media.add('(prefers-reduced-motion: no-preference)', () => {
    let visible = false;
    let relaxation;
    let observer;
    const drawIfVisible = () => { if (visible && !document.hidden) render(); };
    if (stage) {
      observer = new IntersectionObserver(entries => {
        visible = entries[0].isIntersecting;
        if (!visible) { relaxation?.pause(); state.velocity = 0; }
        else render();
      }, { rootMargin: '80px 0px' });
      observer.observe(stage);
      relaxation = gsap.to(state, { velocity: 0, duration: 0.5, ease: 'power2.out', paused: true, onUpdate: drawIfVisible });
      ScrollTrigger.create({
        id: 'zeroit-frill', trigger: stage, start: 'top bottom', end: 'bottom top',
        onUpdate(self) {
          state.progress = self.progress;
          if (!visible || document.hidden) return;
          state.velocity = gsap.utils.clamp(-13, 13, self.getVelocity() / 180);
          relaxation.invalidate().restart();
          render();
        },
        onRefresh(self) { state.progress = self.progress; drawIfVisible(); }
      });
    }
    // Independent layers preserve the approved layout and each illustration's own rotation.
    const restoreLayers = [];
    const layerAround = (target, className) => {
      const layer = document.createElement('div'); layer.className = className;
      target.before(layer); layer.append(target);
      restoreLayers.push(() => { layer.replaceWith(...layer.childNodes); });
      return layer;
    };
    const hero = document.querySelector('.hero');
    const receipts = document.querySelectorAll('.receipt-scene > .mini-receipt');
    if (hero) {
      receipts.forEach((card, index) => {
        const outer = layerAround(card, 'story-scene-layer');
        const entrance = layerAround(card, 'story-scene-layer');
        const direction = index ? 1 : -1;
        gsap.from(entrance, { x: () => direction * Math.min(innerWidth * 0.75, 1200), y: 70,
          rotation: direction * 9, duration: 1.25, delay: index * 0.14, ease: 'power3.out' });
        gsap.to(outer, { y: -55, rotation: direction * 3, scale: 0.96, ease: 'none',
          scrollTrigger: { id: 'story-hero-' + index, trigger: hero, start: 'top top', end: 'bottom top', scrub: 0.7, invalidateOnRefresh: true } });
      });
    }
    // Each framed scene tells a small story: the main object arrives, then its note lands.
    document.querySelectorAll('.moment-art').forEach((frame, index) => {
      const objects = Array.from(frame.children);
      const timeline = gsap.timeline({scrollTrigger: { id: 'story-moment-' + index, trigger: frame,
        start: 'top 94%', end: 'center 58%', scrub: 0.65, invalidateOnRefresh: true }});
      objects.forEach((object, item) => {
        const layer = layerAround(object, 'story-art-layer');
        const direction = (index + item) % 2 ? 1 : -1;
        timeline.fromTo(layer, { x: () => direction * (frame.clientWidth + 80), y: item ? -60 : 65,
          rotation: direction * (item ? 10 : 5), scale: item ? 0.86 : 0.95 },
          { x: 0, y: 0, rotation: 0, scale: 1, duration: 1, ease: 'power2.out' }, item * 0.18);
      });
    });
    const disposeJourney = createJourney(gsap, ScrollTrigger);
    document.querySelectorAll('[data-reveal]').forEach(target => {
      if (target.closest('.receipt-scene') || target.querySelector('a,button,input,select,textarea')) return;
      gsap.from(target, { y: 28, duration: 0.8, ease: 'power2.out', scrollTrigger: { trigger: target, start: 'top 94%', once: true } });
    });
    const visibilityChanged = () => {
      if (document.hidden) { relaxation?.pause(); state.velocity = 0; }
      else drawIfVisible();
    };
    document.addEventListener('visibilitychange', visibilityChanged);
    return () => {
      observer?.disconnect(); relaxation?.kill();
      disposeJourney();
      restoreLayers.reverse().forEach(restore => restore());
      document.removeEventListener('visibilitychange', visibilityChanged);
      state.progress = 0.24; state.velocity = 0; render();
    };
  });
  let refreshFrame;
  const refresh = () => {
    cancelAnimationFrame(refreshFrame);
    refreshFrame = requestAnimationFrame(() => ScrollTrigger.refresh());
  };
  window.addEventListener('zeroit:layout', refresh);
  window.addEventListener('load', refresh, { once: true });
  document.fonts?.ready.then(refresh);
})();
