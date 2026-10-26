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
    let cleanupJourney = () => {};
    if (track && ribbon) {
      const companion = document.createElement('div');
      companion.className = 'journey-ribbon';
      companion.setAttribute('aria-hidden', 'true');
      companion.inert = true;
      const shell = document.createElement('div'); shell.className = 'journey-shell';
      const journeyTrack = track.cloneNode(true); journeyTrack.className = 'journey-track';
      const progress = document.createElement('div'); progress.className = 'journey-progress';
      shell.append(journeyTrack, progress); companion.append(shell); document.body.append(companion);
      gsap.set(companion, {autoAlpha: 0});
      let inJourney = false;
      let shown = false;
      let alive = true;
      let focusFrame;
      const chat = document.querySelector('#chat-panel');
      const updateVisibility = () => {
        if (!alive) return;
        const editing = document.activeElement?.matches('input,select,textarea,[contenteditable="true"]');
        const next = inJourney && !editing && (!chat || chat.hidden);
        if (next === shown) return;
        shown = next;
        gsap.to(companion, {autoAlpha: next ? 1 : 0, duration: 0.25, overwrite: true});
      };
      const focusChanged = () => { cancelAnimationFrame(focusFrame); focusFrame = requestAnimationFrame(updateVisibility); };
      document.addEventListener('focusin', focusChanged);
      document.addEventListener('focusout', focusChanged);
      const chatObserver = new MutationObserver(updateVisibility);
      if (chat) chatObserver.observe(chat, {attributes: true, attributeFilter: ['hidden']});
      ScrollTrigger.create({
        id: 'zeroit-journey', trigger: ribbon, start: 'bottom top+=16', end: 'max',
        onUpdate: self => { inJourney = self.scroll() >= self.start; updateVisibility(); },
        onRefresh: self => { inJourney = self.scroll() >= self.start; updateVisibility(); }
      });
      gsap.timeline({scrollTrigger: {trigger: document.documentElement, start: 0, end: 'max', scrub: 0.65}})
        .to(shell, {y: 5, rotation: 0.65, duration: 1, ease: 'sine.inOut'})
        .to(shell, {y: 0, rotation: -0.65, duration: 1, ease: 'sine.inOut'})
        .to(shell, {y: 5, rotation: 0.65, duration: 1, ease: 'sine.inOut'})
        .to(shell, {y: 0, rotation: 0, duration: 1, ease: 'sine.inOut'});
      gsap.fromTo(progress, {scaleX: 0}, {scaleX: 1, transformOrigin: 'left center', ease: 'none',
        scrollTrigger: {trigger: ribbon, start: 'bottom top+=16', end: 'max', scrub: true}});
      gsap.to(journeyTrack, {x: () => -Math.min(850, Math.max(0, journeyTrack.scrollWidth - shell.clientWidth)), ease: 'none',
        scrollTrigger: {trigger: document.documentElement, start: 0, end: 'max', scrub: 0.45, invalidateOnRefresh: true}});
      cleanupJourney = () => {
        alive = false; cancelAnimationFrame(focusFrame); chatObserver.disconnect();
        document.removeEventListener('focusin', focusChanged); document.removeEventListener('focusout', focusChanged);
        gsap.killTweensOf(companion); companion.remove();
      };
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
    return cleanupJourney;
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
