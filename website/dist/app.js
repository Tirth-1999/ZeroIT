'use strict';
const splitForm = document.querySelector('#split-form');
const amountInput = document.querySelector('#amount');
const peopleInput = document.querySelector('#people');
const splitResult = document.querySelector('#split-result');
const splitError = document.querySelector('#split-error');
function parseCents(value) {
  const match = /^(\d{1,6})(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) return null;
  const cents = Number(match[1]) * 100 + Number((match[2] || '').padEnd(2, '0'));
  return cents > 0 && Number.isSafeInteger(cents) ? cents : null;
}
function money(cents) { return '$' + Math.floor(cents / 100).toLocaleString('en-US') + '.' + String(cents % 100).padStart(2, '0'); }
function showSplit(explicit = false) {
  const total = parseCents(amountInput.value);
  if (total === null) {
    splitResult.replaceChildren();
    splitError.textContent = 'Try an amount from $0.01 to $999,999.99, with up to two decimal places.';
    amountInput.setAttribute('aria-invalid', 'true');
    return;
  }
  splitError.textContent = '';
  amountInput.removeAttribute('aria-invalid');
  const people = Number(peopleInput.value);
  const each = Math.floor(total / people);
  const remainder = total % people;
  const names = ['You', 'Friend 1', 'Friend 2', 'Friend 3', 'Friend 4', 'Friend 5'];
  const colors = ['mint', 'peach', 'lilac'];
  splitResult.replaceChildren();
  const heading = document.createElement('h4'); heading.textContent = 'Your split is ready';
  const status = document.createElement('p'); status.className = 'calculation-status'; status.textContent = money(total) + ' split between ' + people + ' people.';
  splitResult.append(heading, status);
  for (let index = 0; index < people; index++) {
    const row = document.createElement('div'); row.className = 'split-person';
    const person = document.createElement('span');
    const avatar = document.createElement('span'); avatar.className = 'avatar ' + colors[index % colors.length]; avatar.textContent = index ? String(index) : 'Y'; avatar.setAttribute('aria-hidden', 'true');
    person.append(avatar, document.createTextNode(names[index]));
    const share = document.createElement('strong'); share.textContent = money(each + (index < remainder ? 1 : 0));
    row.append(person, share); splitResult.append(row);
  }
  const note = document.createElement('p'); note.className = 'result-note';
  note.textContent = remainder ? 'Every cent included. The first ' + remainder + (remainder === 1 ? ' person gets' : ' people get') + ' one extra cent in their share.' : 'A perfect even split. Total: ' + money(total) + '.';
  splitResult.append(note);
  if (explicit) { splitResult.tabIndex = -1; splitResult.focus({preventScroll:true}); splitResult.scrollIntoView({block:"center",behavior:matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth"}); }
}
splitForm.addEventListener('submit', event => { event.preventDefault(); showSplit(true); });
function staleSplit() { splitResult.replaceChildren(); const note = document.createElement('p'); note.textContent = 'Numbers changed. Click Do the happy math to calculate your split.'; splitResult.append(note); }
peopleInput.addEventListener('change', staleSplit);
amountInput.addEventListener('input', staleSplit);
showSplit();

const launch = document.querySelector('#chat-launch');
const panel = document.querySelector('#chat-panel');
const close = document.querySelector('#chat-close');
const chatForm = document.querySelector('#chat-form');
const chatInput = document.querySelector('#chat-input');
const messages = document.querySelector('#chat-messages');
function setChat(open) {
  panel.hidden = !open;
  launch.setAttribute('aria-expanded', String(open));
  if (open) chatInput.focus(); else launch.focus();
}
launch.addEventListener('click', () => setChat(panel.hidden));
close.addEventListener('click', () => setChat(false));
panel.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); setChat(false); } });
function answer(question) {
  const q = question.toLowerCase();
  if (/sync|invite|account|collaborat|cloud|login|log in|sign in/.test(q)) return 'The current app is local-first. Accounts, remote invitations, and cross-device sync are being designed, but they are not available yet. A group in the current app lives on your device.';
  if (/download|install|store|android|iphone|ios|available|release/.test(q)) return 'ZeroIT is being developed for Android and iOS. Public store downloads are not available yet. You can explore the project and build instructions at github.com/Tirth-1999/ZeroIT.';
  if (/offline|internet|connection/.test(q)) return 'Yes. The current mobile app keeps a local ledger, so you can add expenses and review balances without an account or internet connection.';
  if (/currency|currencies|convert|exchange/.test(q)) return 'The app supports per-expense currencies and keeps each currency balance separate. Automatic currency conversion is not available in the current app.';
  if (/split|payer|percent|share|cent|math/.test(q)) return 'The mobile app has five split modes and supports multiple payers. Money uses exact minor units. The website demo shows a simple equal split and gives any remaining cents to the first people listed.';
  if (/pay|transfer|settle|bank|venmo|money/.test(q)) return 'ZeroIT records expenses and manual settlements. Recording a settlement does not transfer money or connect to your bank. You handle the actual payment separately.';
  if (/free|price|cost|source|github|contribut/.test(q)) return 'ZeroIT is a free, open-source project. Explore the code, follow development, or contribute at github.com/Tirth-1999/ZeroIT. Public release qualification is still ahead.';
  if (/privacy|data|save|history|message|ai|human|support/.test(q)) return 'This helper uses preset answers in your browser, with no AI service or live support agent. Chat messages are not sent to a server and disappear when you reload. The current mobile app stores its ledger locally on your device.';
  if (/hello|^hi\b|hey|thanks|thank you/.test(q)) return 'Hey! Happy to help. Ask me about offline use, downloads, splits, currencies, privacy, or what is still being built.';
  return 'I only know a few ZeroIT FAQs. Try asking about downloads, offline use, sync, splits, currencies, or privacy. For project details and feedback, visit github.com/Tirth-1999/ZeroIT.';
}
function addMessage(text, kind) {
  const message = document.createElement('p'); message.className = 'message ' + kind; message.textContent = text; messages.append(message);
  while (messages.children.length > 40) messages.firstElementChild.remove();
  messages.scrollTop = messages.scrollHeight;
}
function ask(question) {
  const trimmed = question.trim().slice(0, 300); if (!trimmed) return;
  addMessage(trimmed, 'visitor'); addMessage(answer(trimmed), 'helper');
}
chatForm.addEventListener('submit', event => { event.preventDefault(); ask(chatInput.value); chatInput.value = ''; chatInput.focus(); });
document.querySelectorAll('[data-question]').forEach(button => button.addEventListener('click', () => ask(button.dataset.question)));
