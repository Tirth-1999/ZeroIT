/* Browser-local product illustration. No account, AI, payment or network service. */
(() => {
  'use strict';
  const $ = selector => document.querySelector(selector);
  const form = $('#split-form'), amount = $('#amount'), people = $('#people');
  const result = $('#split-result'), error = $('#split-error');
  const format = cents => '$' + Math.floor(cents / 100).toLocaleString('en-US') + '.' + String(cents % 100).padStart(2, '0');
  function parseCents(value) {
    const match = /^(\d{1,6})(?:\.(\d{1,2}))?$/.exec(value.trim());
    if (!match) return null;
    const cents = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
    return cents > 0 && Number.isSafeInteger(cents) ? cents : null;
  }
  const layout = () => window.dispatchEvent(new Event('zeroit:layout'));
  function calculate(explicit = false) {
    if (!result || !amount || !people) return;
    result.replaceChildren();
    const total = parseCents(amount.value), count = Number(people.value);
    if (total === null || !Number.isInteger(count) || count < 2 || count > 6) {
      if (error) error.textContent = 'Enter $0.01 to $999,999.99, using up to two decimal places.';
      amount.setAttribute('aria-invalid', 'true'); layout(); return;
    }
    if (error) error.textContent = '';
    amount.removeAttribute('aria-invalid');
    const title = document.createElement('h3'); title.textContent = 'Your split is ready.';
    const summary = document.createElement('p'); summary.className = 'split-summary'; summary.textContent = `${format(total)} shared by ${count} people. Every cent accounted for.`;
    result.append(title, summary);
    const base = Math.floor(total / count), extra = total % count;
    for (let index = 0; index < count; index++) {
      const row = document.createElement('div'); row.className = 'split-person';
      const name = document.createElement('span'); name.textContent = index ? `Friend ${index}` : 'You';
      const share = document.createElement('strong'); share.textContent = format(base + (index < extra ? 1 : 0));
      row.append(name, share); result.append(row);
    }
    const note = document.createElement('p'); note.className = 'split-note';
    note.textContent = extra ? `The first ${extra} ${extra === 1 ? 'person gets' : 'people get'} one additional cent. No money moves in this demo.` : 'An even split. No money moves in this demo.';
    result.append(note); layout();
    if (explicit) {
      result.tabIndex = -1; result.focus({ preventScroll: true });
      result.scrollIntoView({ block: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
    }
  }
  function stale() {
    if (!result) return;
    result.replaceChildren(); const note = document.createElement('p'); note.textContent = 'New numbers. Tap “Do the happy math” to see the result.'; result.append(note); layout();
  }
  form?.addEventListener('submit', event => { event.preventDefault(); calculate(true); });
  amount?.addEventListener('input', stale); people?.addEventListener('change', stale); calculate();

  const toggle = $('#faq-toggle'), panel = $('#faq-panel'), close = $('#faq-close');
  const faqForm = $('#faq-form'), input = $('#faq-input'), messages = $('#faq-messages');
  function setOpen(open) {
    if (!panel || !toggle) return;
    panel.hidden = !open; toggle.setAttribute('aria-expanded', String(open));
    if (open) input?.focus(); else toggle.focus();
  }
  function answer(question) {
    const q = question.toLowerCase();
    if (/sync|invite|account|cloud|login|log in/.test(q)) return 'ZeroIT currently keeps its ledger on your device. Accounts, remote invitations and cross-device sync are being designed; they are not available yet.';
    if (/download|install|store|android|iphone|ios|release/.test(q)) return 'ZeroIT is being developed for Android and iOS. Public store downloads are not available yet. Explore the source and build instructions at github.com/Tirth-1999/ZeroIT.';
    if (/offline|internet|connection/.test(q)) return 'Yes. The current mobile app works with a local ledger, so adding expenses and reviewing balances does not require an account or internet connection.';
    if (/currency|currencies|convert|exchange/.test(q)) return 'Expenses can use different currencies. Their balances stay separate; the current app does not perform automatic currency conversion.';
    if (/pay|settle|transfer|bank/.test(q)) return 'ZeroIT records expenses and manual settlements. It does not transfer money or connect to your bank; actual payments happen separately.';
    if (/split|cent|percent|payer|math/.test(q)) return 'The mobile app offers five split modes and multiple payers. This website demonstrates equal splits using exact integer cents, including any remainder.';
    if (/privacy|data|ai|human|message|support/.test(q)) return 'This is a local FAQ using preset answers, not AI or a live agent. Your messages stay in this browser tab and disappear on reload.';
    if (/free|source|github|price|cost/.test(q)) return 'ZeroIT is a free open-source project. See github.com/Tirth-1999/ZeroIT for code and development progress.';
    return 'Try asking about offline use, downloads, sync, splits, currencies or privacy. This helper only answers a small set of ZeroIT FAQs.';
  }
  function ask(question) {
    const text = question.trim().slice(0, 300); if (!text || !messages) return;
    for (const [content, kind] of [[text, 'visitor'], [answer(text), 'helper']]) {
      const message = document.createElement('p'); message.className = 'message ' + kind; message.textContent = content; messages.append(message);
    }
    while (messages.children.length > 32) messages.firstElementChild.remove();
    messages.scrollTop = messages.scrollHeight;
  }
  toggle?.addEventListener('click', () => setOpen(panel?.hidden ?? false));
  close?.addEventListener('click', () => setOpen(false));
  panel?.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); setOpen(false); } });
  faqForm?.addEventListener('submit', event => { event.preventDefault(); ask(input?.value || ''); if (input) { input.value = ''; input.focus(); } });
  document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => ask(button.dataset.question || button.textContent)));
})();
