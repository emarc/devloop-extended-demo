// A "Claude" panel for Vaadin Copilot with one button: "Your turn, Claude". It
// records developer.your-turn in the dev-loop journal and nothing else; Claude's
// `vdx watch` picks it up (or the next prompt's briefing carries it), and the
// review-dev-changes skill says what a hand-over means: review the changes, fix
// every pending annotation, report. Served by the dev-loop daemon
// (ClaudePanel.java) through the app, which adds it to the page only when the
// daemon launched it.

const base = new URL('.', import.meta.url).href;

class DevLoopClaudePanel extends HTMLElement {
  connectedCallback() {
    this.innerHTML = `
      <div style="display:flex;flex-direction:column;gap:0.5rem;padding:0.5rem 0.75rem 0.75rem;font-size:0.8125rem;max-width:18rem">
        <div>Done for now? Hand over to Claude: it reviews what you and Copilot changed and fixes
          all pending annotations.</div>
        <div data-status style="opacity:0.75"></div>
        <vaadin-button data-send theme="primary">Your turn, Claude</vaadin-button>
        <div data-result style="opacity:0.75"></div>
      </div>`;
    this.querySelector('[data-send]').addEventListener('click', () => this.handOver());
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
    const status = this.querySelector('[data-status]');
    try {
      const { pendingAnnotations } = await (await fetch(base + 'status')).json();
      status.textContent = `${pendingAnnotations} pending annotation(s), all views.`;
    } catch (e) {
      status.textContent = 'The dev-loop daemon is not reachable.';
    }
  }

  async handOver() {
    const button = this.querySelector('[data-send]');
    const result = this.querySelector('[data-result]');
    button.disabled = true;
    try {
      const response = await fetch(base + 'your-turn', { method: 'POST' });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      result.textContent = 'Handed over. Claude starts now if it is watching the dev loop, ' +
        'otherwise at your next prompt.';
    } catch (e) {
      result.textContent = `Could not hand over: ${e.message}`;
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
