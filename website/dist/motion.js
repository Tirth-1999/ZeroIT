/* ZeroIT progressive scroll choreography. Content stays visible without GSAP. */
(() => {
  'use strict';
  if (!window.gsap || !window.ScrollTrigger) return;
  const { gsap, ScrollTrigger } = window;
  gsap.registerPlugin(ScrollTrigger);
  const media = gsap.matchMedia();
  media.add('(prefers-reduced-motion: no-preference)', () => {
    const track = document.querySelector('.ribbon > div');
    const ribbon = document.querySelector('.ribbon');
    if (track && ribbon) {
      gsap.to(track, {
        x: () => -Math.min(850, Math.max(0, track.scrollWidth - ribbon.clientWidth)),
        ease: 'none',
        scrollTrigger: { id: 'zeroit-ribbon', trigger: document.documentElement, start: 0, end: 'max', scrub: 0.45, invalidateOnRefresh: true }
      });
    }
    const decorations = [
      ['.sun-sticker', -18, 0], ['.coral-note', 16, -12],
      ['.tiny-star', -16, 80], ['.bill-tag', -10, -8],
      ['.travel-spark', -16, 65], ['.home-label', -10, 6],
      ['.zero-art img', -12, -8]
    ];
    decorations.forEach(([selector, drift, rotation]) => {
      const target = document.querySelector(selector);
      if (!target) return;
      gsap.to(target, { y: drift, rotation: '+=' + rotation, ease: 'none',
        scrollTrigger: { trigger: target.parentElement, start: 'clamp(top bottom)', end: 'clamp(bottom top)', scrub: 0.7 }
      });
    });
    document.querySelectorAll('.section-heading, .situation, .split-copy, .demo-board, .principles > h2, .principle-list article, .next-note, .zero-art, .site-footer').forEach(target => {
      // Transform-only reveals keep keyboard navigation and content visibility intact.
      gsap.from(target, { y: 22, duration: 0.75, ease: 'power2.out',
        scrollTrigger: { trigger: target, start: 'top 94%', once: true }
      });
    });
    return () => {};
  });
  // Calculator and FAQ may alter layout; refresh only after completed layout changes.
  let refreshFrame;
  const requestRefresh = () => {
    cancelAnimationFrame(refreshFrame);
    refreshFrame = requestAnimationFrame(() => ScrollTrigger.refresh());
  };
  const result = document.querySelector('#split-result');
  if (result) new MutationObserver(requestRefresh).observe(result, { childList: true, subtree: true });
  window.addEventListener('load', requestRefresh, { once: true });
  document.fonts?.ready.then(requestRefresh);
})();
