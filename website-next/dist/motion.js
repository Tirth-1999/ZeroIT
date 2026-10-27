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
  let svg, edge, band, baseline, letters;
  const curve = progress => {
    const amplitude = 35 + Math.sin(progress * Math.PI) * 29 + state.velocity;
    const phase = -0.45 + progress * 2.5;
    const frequency = Math.PI * 2 / 1400;
    const y = x => 158 + Math.sin(x * frequency + phase) * amplitude;
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
    text.append(letters); svg.append(defs, edge, band, text); stage.append(svg);
    render();
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
        if (!visible) { relaxation?.kill(); state.velocity = 0; }
        else render();
      }, { rootMargin: '80px 0px' });
      observer.observe(stage);
      ScrollTrigger.create({
        id: 'zeroit-frill', trigger: stage, start: 'top bottom', end: 'bottom top',
        onUpdate(self) {
          state.progress = self.progress;
          if (!visible || document.hidden) return;
          state.velocity = gsap.utils.clamp(-13, 13, self.getVelocity() / 180);
          relaxation?.kill();
          relaxation = gsap.to(state, { velocity: 0, duration: 0.5, ease: 'power2.out', onUpdate: drawIfVisible });
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
    const wordmark = document.querySelector('.closing-wordmark');
    const closing = document.querySelector('.closing');
    if (wordmark && closing) {
      const textNode = Array.from(wordmark.childNodes).find(node => node.nodeType === Node.TEXT_NODE && node.textContent.trim());
      if (textNode) {
        const textLayer = document.createElement('span'); textLayer.className = 'story-wordmark-text';
        textNode.before(textLayer); textLayer.append(textNode);
        restoreLayers.push(() => { textLayer.replaceWith(...textLayer.childNodes); });
        gsap.fromTo(textLayer, { yPercent: 110 }, { yPercent: 0, ease: 'none',
          scrollTrigger: { id: 'story-closing', trigger: closing, start: 'top 75%', end: 'bottom bottom', scrub: 0.65 } });
      }
    }
    document.querySelectorAll('[data-reveal]').forEach(target => {
      if (target.closest('.receipt-scene') || target.querySelector('a,button,input,select,textarea')) return;
      gsap.from(target, { y: 28, duration: 0.8, ease: 'power2.out', scrollTrigger: { trigger: target, start: 'top 94%', once: true } });
    });
    const visibilityChanged = () => {
      if (document.hidden) { relaxation?.kill(); state.velocity = 0; }
      else drawIfVisible();
    };
    document.addEventListener('visibilitychange', visibilityChanged);
    return () => {
      observer?.disconnect(); relaxation?.kill();
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
