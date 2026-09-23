// Window/DOM handles are resources. onChange publishes presentation facts to
// the app's DataScript connection; closing or re-evaluating never forks a model.
export function createPresentation(element, options = {}) {
  const home = element.ownerDocument, opener = home.defaultView;
  const placeholder = home.createElement('div');
  placeholder.className = 'viewer-placeholder'; placeholder.hidden = true;
  element.before(placeholder);
  const note = home.createElement('span'); placeholder.append(note);
  let floating, pip = false, disposed = false, pending = false, maximized = false;
  const mode = () => floating ? (pip ? 'pip' : 'window') :
    (home.fullscreenElement === element || maximized ? 'fullscreen' : 'inline');
  const publish = (message = '') => options.onChange?.({mode: mode(), message});
  const moved = () => options.onMove?.();
  function restore() {
    const previous = floating; floating = null; pip = false;
    if (placeholder.parentNode) placeholder.after(element);
    placeholder.hidden = true; element.classList.remove('viewer-detached');
    previous?.removeEventListener('pagehide', restore);
    if (!disposed) { moved(); publish(); }
  }
  function dock() { const win = floating; restore(); if (win && !win.closed) win.close(); }
  function button(label, title, click) {
    const b = home.createElement('button'); b.textContent = label; b.title = title;
    b.addEventListener('click', click); placeholder.append(b);
  }
  button('Return to journal', 'Return this live viewer to its code panel · Ctrl+Alt+O', dock);
  button('Focus window', 'Focus the detached model viewer', () => floating?.focus());
  function copyStyles(doc) {
    for (const source of home.querySelectorAll('style,link[rel="stylesheet"]')) {
      const copy = source.cloneNode(true); if (source.href) copy.href = source.href;
      doc.head.append(copy);
    }
  }
  async function popOut() {
    if (disposed || pending) return;
    if (floating) { dock(); return; }
    pending = true;
    let win;
    try {
      if (home.fullscreenElement) await home.exitFullscreen();
      maximized = false; element.classList.remove('viewer-maximized');
      const api = opener.documentPictureInPicture; pip = !!api?.requestWindow;
      // Ordinary windows cannot request always-on-top. Never simulate it by
      // repeatedly stealing focus; label the fallback honestly.
      win = pip ? await api.requestWindow({width: 800, height: 640}) :
        opener.open('', '_blank', 'popup,width=800,height=640');
      if (!win) throw Error('Pop-up blocked. Allow pop-ups for this journal and try again.');
      if (disposed) { win.close(); return; }
      floating = win;
      const doc = win.document; doc.title = options.title || 'Modeling Journal · Model';
      copyStyles(doc); doc.body.classList.add('viewer-window');
      const banner = doc.createElement('div'); banner.className = 'viewer-window-status';
      banner.textContent = pip ? 'Always on top · live model' :
        'Live model · this browser cannot pin windows on top. Use your window manager’s “Always on top” command.';
      doc.body.append(banner, element); element.classList.add('viewer-detached');
      note.textContent = pip ? 'Model is in an always-on-top window.' : 'Model is in a separate window.';
      placeholder.hidden = false;
      win.addEventListener('pagehide', restore, {once: true});
      doc.addEventListener('keydown', event => {
        if (event.ctrlKey && event.altKey) {
          const command = {f:'fit',x:'wireframe',p:'pause',d:'download',o:'popout'}[event.key.toLowerCase()];
          const action = command && element.querySelector(`[data-viewer-command="${command}"]`);
          if (action) { event.preventDefault(); action.click(); }
        }
      });
      moved(); publish();
    } catch (e) {
      if (floating) dock(); else win?.close();
      pip = false; publish(e.message || 'Could not open a model window.');
    } finally { pending = false; }
  }
  function fullscreenChanged(event) {
    // A document event is seen by every panel; only relocate its own renderer.
    if (event && event.target !== element) return;
    if (!disposed) { moved(); publish(); }
  }
  async function fullscreen() {
    if (disposed) return;
    if (floating) { publish('Return to the journal before entering fullscreen.'); return; }
    try {
      if (home.fullscreenElement === element) await home.exitFullscreen();
      else if (element.requestFullscreen) await element.requestFullscreen();
      else { maximized = !maximized; element.classList.toggle('viewer-maximized', maximized); fullscreenChanged(); }
    } catch (e) { publish(e.message || 'Fullscreen is not available.'); }
  }
  function escape(event) {
    if (event.key === 'Escape' && maximized) {
      maximized = false; element.classList.remove('viewer-maximized'); fullscreenChanged();
    }
  }
  const leaving = () => floating?.close();
  home.addEventListener('fullscreenchange', fullscreenChanged);
  home.addEventListener('keydown', escape); opener.addEventListener('pagehide', leaving);
  return {popOut, fullscreen, dock,
    dispose() {
      disposed = true; dock(); placeholder.remove();
      if (home.fullscreenElement === element) home.exitFullscreen().catch(() => {});
      element.classList.remove('viewer-maximized');
      home.removeEventListener('fullscreenchange', fullscreenChanged);
      home.removeEventListener('keydown', escape); opener.removeEventListener('pagehide', leaving);
    }};
}
