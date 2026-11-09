// Catalog editor: what the apps show about each series (title, synopsis,
// genres, poster, season and relations) and about each episode (number,
// title, language, intro and outro). The server stores it; this only edits.
(() => {
  const $ = (id) => document.getElementById(id);
  const api = window.companion;
  const textFields = ['title', 'titleJp', 'synopsis', 'franchise', 'seasonLabel'];
  const choiceFields = ['mediaType', 'status', 'rating', 'season'];
  const numberFields = ['score', 'year', 'totalEpisodes', 'seasonNumber'];
  let list = []; let selected = null; let detail = null; let initial = null; let rows = [];

  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(props)) { if (name === 'class') node.className = value; else if (name in node) node[name] = value; else node.setAttribute(name, value); }
    node.append(...children);
    return node;
  }
  const status = (message, bad = false) => { $('saveStatus').textContent = message; $('saveStatus').className = bad ? 'error' : 'muted'; };
  const clean = (error) => String(error?.message ?? error).replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '');

  // ---- times: "1:30", "1:02:03" or plain seconds ---------------------------
  function formatTime(seconds) {
    if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '';
    const whole = Math.floor(seconds); const fraction = Math.round((seconds - whole) * 10) / 10;
    const hours = Math.floor(whole / 3600); const minutes = Math.floor((whole % 3600) / 60); const rest = whole % 60;
    const tail = fraction ? `.${String(fraction).slice(2)}` : '';
    return hours ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}${tail}` : `${minutes}:${String(rest).padStart(2, '0')}${tail}`;
  }
  function parseTime(value) {
    const textValue = String(value).trim().replace(',', '.');
    if (!textValue) return null;
    const parts = textValue.split(':').map(Number);
    if (parts.length > 3 || parts.some((part) => !Number.isFinite(part) || part < 0)) return NaN;
    return parts.reduce((total, part) => total * 60 + part, 0);
  }

  // ---- the list -----------------------------------------------------------
  function renderList() {
    const needle = $('seriesFilter').value.trim().toLowerCase();
    const shown = list.filter((item) => !needle || item.title.toLowerCase().includes(needle));
    const node = $('seriesList'); node.replaceChildren();
    for (const item of shown) {
      const row = el('li', { class: item.id === selected ? 'active' : '' },
        el('span', {}, `${item.edited ? '✎ ' : ''}${item.title}`),
        el('small', {}, `${item.episodes} ep${item.franchise ? ` · ${item.franchise}` : ''}`));
      row.addEventListener('click', () => open(item.id));
      node.append(row);
    }
    $('seriesCount').textContent = list.length ? `${shown.length} de ${list.length}` : 'Aún no hay series: añade carpetas en la pestaña Servidor.';
    const franchises = [...new Set(list.map((item) => item.franchise).filter(Boolean))].sort();
    $('franchises').replaceChildren(...franchises.map((name) => el('option', { value: name })));
  }
  async function loadList() {
    try { list = (await api.catalogList()).series; } catch (error) { list = []; status(clean(error), true); }
    renderList();
  }

  // ---- the form -----------------------------------------------------------
  const toLocalInput = (seconds) => {
    if (!seconds) return '';
    const date = new Date(seconds * 1000); const pad = (value) => String(value).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
  };
  const fromLocalInput = (value) => (value ? Math.floor(new Date(value).getTime() / 1000) : '');
  function readForm() {
    const values = {};
    for (const name of [...textFields, ...choiceFields, ...numberFields]) values[name] = $(`f-${name}`).value.trim();
    values.genres = $('f-genres').value.split(',').map((genre) => genre.trim()).filter(Boolean);
    values.dub = $('f-dub').checked; values.adult = $('f-adult').checked;
    values.nextEpisodeAt = fromLocalInput($('f-nextEpisodeAt').value);
    return values;
  }
  function fillForm(effective) {
    for (const name of [...textFields, ...choiceFields, ...numberFields]) $(`f-${name}`).value = effective[name] ?? '';
    $('f-genres').value = (effective.genres ?? []).join(', ');
    $('f-dub').checked = Boolean(effective.dub); $('f-adult').checked = Boolean(effective.adult);
    $('f-nextEpisodeAt').value = toLocalInput(effective.nextEpisodeAt);
    initial = readForm();
  }

  function timeCell(row, name, episode) {
    const set = episode[name];
    const start = el('input', { class: 'time', type: 'text', placeholder: 'inicio', value: set ? formatTime(set.start) : '' });
    const end = el('input', { class: 'time', type: 'text', placeholder: 'fin', value: set ? formatTime(set.end) : '' });
    const off = el('button', { type: 'button', title: 'No ofrecer este salto en este episodio' }, 'Sin salto');
    const note = el('span', { class: 'off' }, 'desactivado');
    const state = { off: set === false };
    const apply = () => {
      start.disabled = end.disabled = state.off; note.hidden = !state.off; off.textContent = state.off ? 'Activar' : 'Sin salto';
      if (state.off) { start.value = ''; end.value = ''; }
    };
    off.addEventListener('click', () => { state.off = !state.off; apply(); });
    apply();
    return { cell: el('td', {}, el('div', { class: 'skip' }, start, end, off, note)), start, end, state, initial: set === false ? false : set ?? null };
  }
  function renderEpisodes() {
    const body = document.querySelector('#episodes tbody'); body.replaceChildren(); rows = [];
    for (const episode of detail.episodes) {
      const number = el('input', { class: 'num', type: 'number', min: 1, max: 10000, value: episode.number });
      const title = el('input', { type: 'text', value: episode.title, maxLength: 200 });
      const dub = el('input', { type: 'checkbox', checked: Boolean(episode.dub) });
      const intro = timeCell(null, 'intro', episode); const outro = timeCell(null, 'outro', episode);
      const detect = el('button', { type: 'button', title: 'Lee los capítulos «Opening» y «Ending» del archivo' }, 'Detectar');
      detect.addEventListener('click', async () => {
        detect.disabled = true; detect.textContent = 'Leyendo…';
        try {
          const marks = await api.catalogMarks(episode.fileId);
          for (const [name, cell] of [['intro', intro], ['outro', outro]]) {
            const found = marks.detected[name];
            if (found) { cell.state.off = false; cell.start.disabled = cell.end.disabled = false; cell.start.value = formatTime(found.start); cell.end.value = formatTime(found.end); }
          }
          status(marks.detected.intro || marks.detected.outro ? 'Rangos leídos de los capítulos del archivo; guarda para aplicarlos.' : 'Ese archivo no tiene capítulos de Opening o Ending: escribe los tiempos a mano.');
        } catch (error) { status(clean(error), true); }
        detect.disabled = false; detect.textContent = 'Detectar';
      });
      body.append(el('tr', {},
        el('td', {}, number), el('td', {}, title, el('span', { class: 'file' }, episode.fileName)), el('td', {}, dub), intro.cell, outro.cell, el('td', {}, detect)));
      rows.push({ episode, number, title, dub, intro, outro, initial: { number: episode.number, title: episode.title, dub: Boolean(episode.dub) } });
    }
  }

  async function showPoster() {
    const image = $('poster'); const data = await api.catalogArt(detail.id, Date.now());
    if (data) image.src = data; else image.removeAttribute('src');
    $('posterClear').hidden = !detail.customPoster;
  }
  function show(next) {
    detail = next; selected = next.id;
    $('editor').hidden = false; $('editorEmpty').hidden = true;
    $('editorFolder').textContent = next.folder;
    fillForm(next.effective); renderEpisodes();
    $('related').textContent = next.related.length > 1
      ? `Relacionadas en la app: ${next.related.map((item) => `${item.title} (temporada ${item.seasonNumber})`).join(' · ')}`
      : 'Esta serie no tiene otras temporadas relacionadas.';
    status(''); showPoster(); renderList();
  }
  async function open(id) {
    try { show(await api.catalogDetail(id)); } catch (error) { status(clean(error), true); }
  }

  // ---- saving ---------------------------------------------------------------
  function skipChange(cell, name) {
    const startText = cell.start.value; const endText = cell.end.value;
    let value;
    if (cell.state.off) value = false;
    else if (!startText.trim() && !endText.trim()) value = null;
    else {
      const start = parseTime(startText); const end = parseTime(endText);
      if (start === null || end === null || Number.isNaN(start) || Number.isNaN(end) || end <= start) throw new Error(`El ${name} necesita un inicio y un fin válidos (m:ss), con el fin después del inicio.`);
      value = { start, end };
    }
    const same = JSON.stringify(value) === JSON.stringify(cell.initial);
    return same ? undefined : value;
  }
  function buildChanges() {
    const body = { ...detail.override }; delete body.poster;
    const now = readForm();
    for (const name of Object.keys(now)) {
      if (JSON.stringify(now[name]) !== JSON.stringify(initial[name])) body[name] = now[name];
    }
    const episodes = { ...(detail.override.episodes ?? {}) };
    for (const row of rows) {
      const change = {};
      const number = Number(row.number.value);
      if (!Number.isInteger(number) || number < 1) throw new Error('Cada episodio necesita un número entero mayor que 0.');
      if (number !== row.initial.number) change.number = number;
      if (row.title.value.trim() !== row.initial.title) change.title = row.title.value.trim();
      if (row.dub.checked !== row.initial.dub) change.dub = row.dub.checked;
      const intro = skipChange(row.intro, 'intro'); if (intro !== undefined) change.intro = intro;
      const outro = skipChange(row.outro, 'outro'); if (outro !== undefined) change.outro = outro;
      if (Object.keys(change).length) episodes[row.episode.fileId] = { ...(episodes[row.episode.fileId] ?? {}), ...change };
    }
    body.episodes = episodes;
    return body;
  }
  async function save() {
    let changes;
    try { changes = buildChanges(); } catch (error) { status(error.message, true); return; }
    $('save').disabled = true; status('Guardando…');
    try { show(await api.catalogSave(detail.id, changes)); status('Guardado. La app lo verá al abrir la serie.'); await loadList(); }
    catch (error) { status(clean(error), true); }
    $('save').disabled = false;
  }
  async function reset() {
    if (!window.confirm('¿Quitar todos los cambios de esta serie y volver a lo que dicen las carpetas y los archivos?')) return;
    try {
      let next = await api.catalogSave(detail.id, {});
      if (next.customPoster) next = await api.catalogPosterClear(detail.id);
      show(next); await loadList(); status('Restablecido.');
    } catch (error) { status(clean(error), true); }
  }

  // ---- wiring ---------------------------------------------------------------
  $('save').addEventListener('click', save);
  $('reset').addEventListener('click', reset);
  $('seriesFilter').addEventListener('input', renderList);
  $('posterPick').addEventListener('click', async () => {
    try { const next = await api.catalogPoster(detail.id); if (next) { detail = next; await showPoster(); await loadList(); status('Portada cambiada.'); } } catch (error) { status(clean(error), true); }
  });
  $('posterClear').addEventListener('click', async () => {
    try { detail = await api.catalogPosterClear(detail.id); await showPoster(); await loadList(); status('Portada propia quitada.'); } catch (error) { status(clean(error), true); }
  });
  for (const tab of document.querySelectorAll('.tab')) {
    tab.addEventListener('click', () => {
      for (const other of document.querySelectorAll('.tab')) other.classList.toggle('active', other === tab);
      $('page-server').hidden = tab.dataset.page !== 'server'; $('page-catalog').hidden = tab.dataset.page !== 'catalog';
      if (tab.dataset.page === 'catalog') loadList();
    });
  }
})();
