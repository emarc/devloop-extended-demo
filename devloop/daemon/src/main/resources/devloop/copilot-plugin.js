// A "Claude" panel for Vaadin Copilot: lists the pending UI annotations and sends
// them to Claude Code. Served by the dev-loop daemon (ClaudePanel.java) and added
// to the page by the app only when the daemon launched it. "Send to Claude"
// records copilot.annotations.fix-requested in the dev-loop journal, where
// Claude's `vdx watch` picks it up (or the next prompt's briefing carries it).

const base = new URL('.', import.meta.url).href;

class DevLoopClaudePanel extends HTMLElement {
  connectedCallback() {
    this.innerHTML = `
      <div style="display:flex;flex-direction:column;gap:0.5rem;padding:0.5rem 0.75rem 0.75rem;font-size:0.8125rem">
        <div data-list></div>
        <textarea data-message rows="2" placeholder="Anything to add? (optional)"
          style="font:inherit;resize:vertical;padding:0.375rem;border-radius:0.375rem;border:1px solid rgba(128,128,128,0.4);background:transparent;color:inherit"></textarea>
        <vaadin-button data-send theme="primary">Send to Claude</vaadin-button>
        <div data-result style="opacity:0.75"></div>
      </div>`;
    this.querySelector('[data-send]').addEventListener('click', () => this.send());
    this.refresh();
    this.timer = setInterval(() => this.refresh(), 3000);
  }

  disconnectedCallback() {
    clearInterval(this.timer);
  }

  // Copilot awaits this on every panel before it shows the panel's dialog
  // (Copilot's own panels inherit it from their base class).
  requestLayoutUpdate() {
    return Promise.resolve();
  }

  async refresh() {
    const list = this.querySelector('[data-list]');
    try {
      const { pending } = await (await fetch(base + 'annotations')).json();
      this.pending = pending;
      list.replaceChildren();
      if (pending.length === 0) {
        list.textContent = 'No pending annotations. You can still send Claude a message.';
        return;
      }
      const heading = document.createElement('div');
      heading.textContent = `${pending.length} pending annotation(s):`;
      const items = document.createElement('ul');
      items.style.cssText = 'margin:0.25rem 0 0;padding-inline-start:1.25rem';
      for (const note of pending) {
        const item = document.createElement('li');
        item.textContent = `"${note.content}" on ${note.view}`;
        items.append(item);
      }
      list.append(heading, items);
    } catch (e) {
      list.textContent = 'The dev-loop daemon is not reachable.';
    }
  }

  async send() {
    const button = this.querySelector('[data-send]');
    const message = this.querySelector('[data-message]');
    const result = this.querySelector('[data-result]');
    button.disabled = true;
    try {
      const response = await fetch(base + 'fix-annotations', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: message.value, view: location.pathname }),
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      const { count } = await response.json();
      message.value = '';
      result.textContent = `Sent${count ? ` ${count} annotation(s)` : ''}. ` +
        'Claude picks it up now if it is watching the dev loop, otherwise at your next prompt.';
    } catch (e) {
      result.textContent = `Could not send: ${e.message}`;
    } finally {
      button.disabled = false;
    }
  }
}

if (!customElements.get('devloop-claude-panel')) {
  customElements.define('devloop-claude-panel', DevLoopClaudePanel);
}

const plugin = {
  init(api) {
    api.addPanel({
      header: 'Claude',
      tag: 'devloop-claude-panel',
      individual: true,
      toolbarOptions: {
        allowedModesWithOrder: { edit: 20, test: 3 },
        iconKey: 'sparkles',
      },
    });
  },
};

// Copilot's bootstrap resets window.Vaadin.copilot.plugins to [], so wait for it
// (_uiState is set in the same statement) before queueing. Once Copilot has
// loaded its own plugins it runs the queue and replaces push, so either order
// after that works. Gives up after a minute: no Copilot on this page.
let tries = 0;
const register = () => {
  const copilot = window.Vaadin?.copilot;
  if (copilot?._uiState && copilot.plugins) {
    copilot.plugins.push(plugin);
  } else if (tries++ < 300) {
    setTimeout(register, 200);
  }
};
register();
