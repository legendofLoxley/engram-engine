// Director Chat — a WebUI extension panel (see docs/EXTENSIONS.md) that talks
// directly to the engram-webui-bridge sidecar (../sidecar.py), which itself
// holds engram-engine's debug-converse bearer token server-side. This script
// never sees or sends that token — the bridge injects it before proxying to
// the real Director pipeline.
//
// This is an additive panel, not a replacement for the built-in Chat view:
// it does not touch Hermes's own conversation path.
(() => {
  const BRIDGE_ORIGIN = 'http://127.0.0.1:8090';
  const SESSION_KEY = 'directorChatSessionId';

  if (document.getElementById('director-chat-panel')) return; // guard re-injection

  const toggle = document.createElement('button');
  toggle.id = 'director-chat-toggle';
  toggle.className = 'director-chat-toggle';
  toggle.type = 'button';
  toggle.textContent = 'Director (dev)';
  document.body.appendChild(toggle);

  const panel = document.createElement('section');
  panel.id = 'director-chat-panel';
  panel.className = 'director-chat-panel';
  panel.hidden = true;
  panel.innerHTML = `
    <div class="director-chat-header">
      <div><strong>Director</strong><span class="director-chat-badge">dev slice</span></div>
      <button type="button" data-action="close" aria-label="Close">&times;</button>
    </div>
    <div class="director-chat-messages" id="director-chat-messages"></div>
    <div class="director-chat-composer">
      <div class="director-chat-controls-row">
        <button type="button" disabled title="Not available in this development slice">Attach</button>
        <button type="button" disabled title="Not available in this development slice">Tools</button>
        <button type="button" disabled title="Not available in this development slice">Approve</button>
        <span class="director-chat-new" data-action="new-conversation" role="button">New conversation</span>
      </div>
      <div class="director-chat-input-row">
        <textarea id="director-chat-input" placeholder="Message the Director…"></textarea>
        <button type="button" class="director-chat-send" id="director-chat-send">Send</button>
      </div>
      <div class="director-chat-footnote">
        Single-request replies only — no live streaming and no cancel/interrupt in this slice.
      </div>
    </div>
  `;
  document.body.appendChild(panel);

  const messagesEl = panel.querySelector('#director-chat-messages');
  const inputEl = panel.querySelector('#director-chat-input');
  const sendBtn = panel.querySelector('#director-chat-send');

  function addMessage(role, text) {
    const div = document.createElement('div');
    div.className = `director-chat-msg ${role}`;
    div.textContent = text;
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
    return div;
  }

  function getSessionId() {
    try {
      return window.localStorage.getItem(SESSION_KEY) || '';
    } catch (_) {
      return '';
    }
  }

  function setSessionId(id) {
    try {
      if (id) window.localStorage.setItem(SESSION_KEY, id);
    } catch (_) {
      /* localStorage unavailable — session just won't persist across reloads */
    }
  }

  toggle.addEventListener('click', () => {
    panel.hidden = !panel.hidden;
    if (!panel.hidden) inputEl.focus();
  });
  panel.querySelector('[data-action="close"]').addEventListener('click', () => {
    panel.hidden = true;
  });
  panel.querySelector('[data-action="new-conversation"]').addEventListener('click', () => {
    try {
      window.localStorage.removeItem(SESSION_KEY);
    } catch (_) {
      /* ignore */
    }
    messagesEl.innerHTML = '';
    addMessage('system-note', 'New conversation started. Graph memory still persists — this only resets short-term turn context.');
  });

  async function send() {
    const text = inputEl.value.trim();
    if (!text) return;

    addMessage('user', text);
    inputEl.value = '';
    sendBtn.disabled = true;
    const thinking = addMessage('assistant', 'Director is thinking…');

    try {
      const body = { message: text };
      const sessionId = getSessionId();
      if (sessionId) body.sessionId = sessionId;

      const resp = await fetch(`${BRIDGE_ORIGIN}/converse`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });

      const data = await resp.json().catch(() => null);

      if (!resp.ok || !data) {
        const detail = (data && data.error) || `HTTP ${resp.status}`;
        thinking.remove();
        addMessage('error', `Director bridge error: ${detail}`);
        return;
      }

      if (data.sessionId) setSessionId(data.sessionId);
      thinking.textContent = data.reply || '(empty reply)';
    } catch (err) {
      thinking.remove();
      addMessage(
        'error',
        `Could not reach the Director bridge at ${BRIDGE_ORIGIN} — is engram-webui-bridge/sidecar.py running? (${err && err.message ? err.message : err})`
      );
    } finally {
      sendBtn.disabled = false;
    }
  }

  sendBtn.addEventListener('click', send);
  inputEl.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });
})();
