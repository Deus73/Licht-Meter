'use strict';

const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const nf0 = new Intl.NumberFormat('nl-NL', {maximumFractionDigits: 0});
const nf1 = new Intl.NumberFormat('nl-NL', {minimumFractionDigits: 1, maximumFractionDigits: 1});

const state = {
  stream: null,
  timer: null,
  cameraRequest: 0,
  starting: false,
  facing: 'user',
  samples: [],
  lux: 0,
  min: 0,
  max: 0,
  deviation: 1,
  stable: false,
  quality: 'idle',
  factor: Number(localStorage.getItem('lm-factor')) || 0.015,
  source: localStorage.getItem('lm-source') || 'Volledig spectrum LED',
  scale: 1000,
  calibrated: false,
  hours: Number(localStorage.getItem('lm-hours')) || 12,
};

const video = $('#camera');
const canvas = $('#sampleCanvas');
const context = canvas.getContext('2d', {willReadFrequently: true});

function ppfd() { return Math.max(0, state.lux * state.factor); }
function dli(hours = state.hours) { return ppfd() * hours * 0.0036; }
function hasMeasurement() { return state.samples.length > 0; }
function hasAbsoluteMeasurement() { return hasMeasurement() && state.calibrated; }

function loadCameraCalibration() {
  const suffix = state.facing === 'user' ? 'user' : 'environment';
  const legacyScale = state.facing === 'environment' ? localStorage.getItem('lm-scale') : null;
  const legacyCalibrated = state.facing === 'environment' && localStorage.getItem('lm-calibrated') === 'true';
  state.scale = Number(localStorage.getItem(`lm-scale-${suffix}`) || legacyScale) || 1000;
  state.calibrated = localStorage.getItem(`lm-calibrated-${suffix}`) === 'true' || legacyCalibrated;
}

async function toggleCamera() {
  if (state.starting) return;
  if (state.stream) {
    stopCamera();
    return;
  }
  if (!navigator.mediaDevices?.getUserMedia) {
    setQuality('bad', 'Camera niet ondersteund');
    return;
  }
  const request = ++state.cameraRequest;
  let pendingStream = null;
  state.starting = true;
  $('#cameraButton').disabled = true;
  $('#cameraButton').textContent = 'Camera starten…';
  try {
    pendingStream = await navigator.mediaDevices.getUserMedia({
      video: {facingMode: {ideal: state.facing}, width: {ideal: 1280}, height: {ideal: 720}},
      audio: false,
    });
    if (request !== state.cameraRequest || document.visibilityState === 'hidden') {
      pendingStream.getTracks().forEach(track => track.stop());
      return;
    }
    video.srcObject = pendingStream;
    await video.play();
    if (request !== state.cameraRequest || document.visibilityState === 'hidden') {
      pendingStream.getTracks().forEach(track => track.stop());
      video.srcObject = null;
      return;
    }
    state.stream = pendingStream;
    video.classList.toggle('mirrored', state.facing === 'user');
    $('#cameraPlaceholder').classList.add('hidden');
    $('#cameraButton').textContent = 'Camera stoppen';
    state.samples = [];
    state.timer = setInterval(sampleFrame, 200);
    setQuality('idle', 'Stabiliseren');
  } catch (error) {
    pendingStream?.getTracks().forEach(track => track.stop());
    video.srcObject = null;
    setQuality('bad', error.name === 'NotAllowedError' ? 'Cameratoegang geweigerd' : 'Camera kon niet starten');
  } finally {
    if (request === state.cameraRequest) {
      state.starting = false;
      $('#cameraButton').disabled = false;
      if (!state.stream) $('#cameraButton').textContent = 'Camera starten';
    }
  }
}

function stopCamera() {
  state.cameraRequest++;
  state.starting = false;
  $('#cameraButton').disabled = false;
  clearInterval(state.timer);
  state.stream?.getTracks().forEach(track => track.stop());
  state.stream = null;
  video.srcObject = null;
  state.samples = [];
  state.lux = 0;
  state.min = 0;
  state.max = 0;
  state.deviation = 1;
  state.stable = false;
  $('#cameraPlaceholder').classList.remove('hidden');
  $('#cameraButton').textContent = 'Camera starten';
  setQuality('idle', 'Gereed');
  renderReadings();
}

function sampleFrame() {
  if (!video.videoWidth) return;
  context.drawImage(video, 0, 0, canvas.width, canvas.height);
  const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
  let total = 0;
  let count = 0;
  let clipped = 0;
  let dark = 0;
  for (let y = 24; y < 72; y += 2) {
    for (let x = 24; x < 72; x += 2) {
      const offset = (y * canvas.width + x) * 4;
      const luma = .2126 * pixels[offset] + .7152 * pixels[offset + 1] + .0722 * pixels[offset + 2];
      total += luma;
      clipped += luma >= 250 ? 1 : 0;
      dark += luma <= 5 ? 1 : 0;
      count++;
    }
  }
  const averageLuma = count ? total / count : 0;
  const relativeIndex = averageLuma / 255 * 100;
  const measuredValue = state.calibrated ? relativeIndex / 100 * state.scale : relativeIndex;
  const now = performance.now();
  state.samples.push({time: now, value: measuredValue});
  state.samples = state.samples.filter(sample => sample.time >= now - 3000);
  calculateWindow(now);
  if (clipped / count >= .10) {
    state.quality = 'bright';
    state.stable = false;
    setQuality('bad', 'Te fel');
  } else if (dark / count >= .80 || averageLuma <= 6) {
    state.quality = 'dark';
    state.stable = false;
    setQuality('bad', 'Te donker');
  } else if (state.stable) {
    state.quality = 'stable';
    setQuality('stable', 'Stabiel');
  } else if (state.samples[state.samples.length - 1].time - state.samples[0].time >= 2000) {
    state.quality = 'unstable';
    setQuality('warn', 'Houd stil');
  } else {
    state.quality = 'settling';
    setQuality('idle', 'Stabiliseren');
  }
  renderReadings();
}

function calculateWindow(now) {
  if (!state.samples.length) return;
  const values = state.samples.map(sample => sample.value).sort((a, b) => a - b);
  const trim = values.length >= 10 ? Math.max(1, Math.floor(values.length / 10)) : 0;
  const trimmed = values.slice(trim, values.length - trim || undefined);
  const average = trimmed.reduce((sum, value) => sum + value, 0) / trimmed.length;
  const variance = trimmed.reduce((sum, value) => sum + (value - average) ** 2, 0) / trimmed.length;
  state.lux = average;
  state.min = values[0];
  state.max = values[values.length - 1];
  state.deviation = average ? Math.sqrt(variance) / average : 1;
  const duration = now - state.samples[0].time;
  state.stable = values.length >= 8 && duration >= 2000 && state.deviation <= .08;
}

function setQuality(className, label) {
  const pill = $('#qualityPill');
  pill.className = `quality-pill ${className}`;
  pill.textContent = label;
}

function renderReadings() {
  const active = hasMeasurement();
  const absolute = active && state.calibrated;
  $('#luxValue').textContent = active ? nf0.format(state.lux) : '--';
  $('#luxUnit').textContent = absolute ? 'lux (schatting)' : 'index';
  $('#readingLabel').textContent = absolute ? 'GEKALIBREERDE CAMERASCHATTING' : 'RELATIEVE LICHTINDEX';
  $('#ppfdValue').textContent = absolute ? nf0.format(ppfd()) : '--';
  $('#dliValue').textContent = absolute ? nf1.format(dli()) : '--';
  $('#hoursLabel').textContent = nf1.format(state.hours).replace(',0', '');
  $('#rangeText').textContent = active
    ? `Min ${nf0.format(state.min)} · gem. ${nf0.format(state.lux)} · max ${nf0.format(state.max)}`
    : 'Min -- · gem. -- · max --';
  $('#stabilityText').textContent = !active ? 'Start de camera' : state.stable
    ? `Stabiel · spreiding ${nf1.format(state.deviation * 100)}%`
    : state.quality === 'unstable' ? `Onrustig · spreiding ${nf1.format(state.deviation * 100)}%` : $('#qualityPill').textContent;
  $('#toolsLiveLux').textContent = active
    ? absolute ? `${nf0.format(state.lux)} lux geschat · ${nf0.format(ppfd())} PPFD` : `${nf0.format(state.lux)} relatieve index`
    : '--';
  $('#toolsLiveState').textContent = state.stream ? $('#qualityPill').textContent.toLowerCase() : 'camera uit';
  const cameraLabel = state.facing === 'user' ? 'Frontcamera' : 'Achtercamera';
  $('#calibrationState').textContent = state.calibrated
    ? `${cameraLabel} gekalibreerd`
    : `Kalibratie ${cameraLabel.toLowerCase()} aanbevolen`;
}

function selectSource(button) {
  $$('.source').forEach(source => source.classList.toggle('active', source === button));
  state.factor = Number(button.dataset.factor);
  state.source = button.dataset.source;
  localStorage.setItem('lm-factor', state.factor);
  localStorage.setItem('lm-source', state.source);
  $('#sourceFactor').textContent = `× ${state.factor.toLocaleString('nl-NL', {minimumFractionDigits: 4})}`;
  renderReadings();
}

async function selectCamera(button) {
  const facing = button.dataset.facing;
  if (facing === state.facing) return;
  const wasRunning = Boolean(state.stream) || state.starting;
  stopCamera();
  state.facing = facing;
  loadCameraCalibration();
  $$('.camera-option').forEach(option => {
    const selected = option === button;
    option.classList.toggle('active', selected);
    option.setAttribute('aria-pressed', String(selected));
  });
  if (wasRunning) await toggleCamera();
  renderReadings();
}

function switchView(view) {
  $$('.view').forEach(section => section.classList.toggle('active', section.id === `${view}View`));
  $$('.bottom-nav button').forEach(button => button.classList.toggle('active', button.dataset.view === view));
  window.scrollTo({top: 0, behavior: 'smooth'});
  renderReadings();
}

function openTool(name) {
  const dialog = $('#toolDialog');
  const body = $('#dialogBody');
  body.replaceChildren();
  const tools = {
    dli: renderDli,
    workplace: renderWorkplace,
    heatmap: renderHeatmap,
    logging: renderLogging,
    guide: renderGuide,
    calibration: renderCalibration,
  };
  tools[name]?.(body);
  dialog.showModal();
}

function title(value) { $('#dialogTitle').textContent = value; }
function result(text) {
  const element = document.createElement('div');
  element.className = 'tool-result';
  element.textContent = text;
  return element;
}
function action(label, className = 'primary-button') {
  const button = document.createElement('button');
  button.className = className;
  button.type = 'button';
  button.textContent = label;
  return button;
}

function renderDli(body) {
  title('DLI-planner');
  if (!hasAbsoluteMeasurement()) return body.append(calibrationRequired());
  body.append(result(`Huidige PPFD: ${nf0.format(ppfd())} µmol/m²/s\n\n8 uur   ${nf1.format(dli(8))} mol/m²/dag\n12 uur  ${nf1.format(dli(12))} mol/m²/dag\n16 uur  ${nf1.format(dli(16))} mol/m²/dag\n18 uur  ${nf1.format(dli(18))} mol/m²/dag\n\nProjectie bij constante lichtsterkte.`));
}

function renderWorkplace(body) {
  title('Werkplekcheck');
  if (!hasAbsoluteMeasurement()) return body.append(calibrationRequired());
  const lux = state.lux;
  const advice = lux < 100 ? 'Te laag voor langdurig werk.' : lux < 300 ? 'Geschikt voor gangen en oriëntatie.' : lux < 500 ? 'Geschikt voor eenvoudige taken; kantoorwerk vraagt meestal circa 500 lux.' : lux < 750 ? 'Gangbaar niveau voor lezen, schrijven en kantoorwerk.' : 'Hoog niveau voor gedetailleerde taken.';
  body.append(result(`${nf0.format(lux)} lux\n\n${advice}\n\nIndicatief: een formele toets controleert ook gelijkmatigheid en verblinding.`));
}

function renderHeatmap(body) {
  title('3×3-lichtkaart');
  const fragment = $('#heatmapTemplate').content.cloneNode(true);
  const grid = fragment.querySelector('.heat-grid');
  const summary = fragment.querySelector('.heat-summary');
  const values = Array(9).fill(null);
  const cells = values.map((_, index) => {
    const cell = action(`P${index + 1}\nTik`, 'heat-cell');
    cell.addEventListener('click', () => {
      if (!requireAbsolute()) return;
      values[index] = {lux: state.lux, ppfd: ppfd()};
      refreshHeatmap(cells, values, summary);
    });
    grid.append(cell);
    return cell;
  });
  fragment.querySelector('.heat-reset').addEventListener('click', () => {
    values.fill(null);
    refreshHeatmap(cells, values, summary);
  });
  fragment.querySelector('.heat-export').addEventListener('click', () => {
    const rows = values.map((value, index) => value && `${index + 1},${value.lux.toFixed(2)},${value.ppfd.toFixed(2)}`).filter(Boolean);
    if (!rows.length) return notify('Leg eerst meetpunten vast.');
    shareCsv('lichtkaart.csv', `point,lux,ppfd\n${rows.join('\n')}\n`);
  });
  body.append(fragment);
}

function refreshHeatmap(cells, values, summary) {
  const measured = values.filter(Boolean);
  const max = Math.max(...measured.map(value => value.lux), 1);
  cells.forEach((cell, index) => {
    const value = values[index];
    cell.textContent = value ? `P${index + 1}\n${nf0.format(value.lux)} lx` : `P${index + 1}\nTik`;
    cell.style.background = value ? `hsl(${10 + value.lux / max * 110} 65% 30%)` : '';
  });
  if (!measured.length) return summary.textContent = 'Nog geen meetpunten.';
  const numbers = measured.map(value => value.lux);
  const average = numbers.reduce((sum, value) => sum + value, 0) / numbers.length;
  summary.textContent = `${measured.length}/9 punten\nMin ${nf0.format(Math.min(...numbers))} · gem. ${nf0.format(average)} · max ${nf0.format(Math.max(...numbers))} lux\nGelijkmatigheid min/gem.: ${nf1.format(Math.min(...numbers) / average * 100)}%`;
}

function renderLogging(body) {
  title('Logsessie + CSV');
  const status = result('Nog geen waarden opgenomen.');
  const controls = document.createElement('div');
  controls.className = 'button-row';
  const toggle = action('Start logging');
  const exportButton = action('CSV delen', 'secondary-button');
  controls.append(toggle, exportButton);
  body.append(status, controls);
  let entries = [];
  let timer = null;
  let started = 0;
  toggle.addEventListener('click', () => {
    if (timer) {
      clearInterval(timer);
      timer = null;
      toggle.textContent = 'Start logging';
      return;
    }
    if (!requireAbsolute()) return;
    entries = [];
    started = Date.now();
    toggle.textContent = 'Stop logging';
    status.textContent = 'Actief · alleen stabiele waarden worden opgeslagen.';
    timer = setInterval(() => {
      if (!state.stable) return;
      entries.push({time: Date.now(), lux: state.lux, ppfd: ppfd()});
      const values = entries.map(entry => entry.lux);
      const average = values.reduce((sum, value) => sum + value, 0) / values.length;
      status.textContent = `${entries.length} waarden in ${Math.round((Date.now() - started) / 1000)} sec.\nMin ${nf0.format(Math.min(...values))} · gem. ${nf0.format(average)} · max ${nf0.format(Math.max(...values))} lux`;
    }, 1000);
  });
  exportButton.addEventListener('click', () => {
    if (!entries.length) return notify('Nog geen stabiele waarden opgenomen.');
    shareCsv('licht-log.csv', `timestamp,lux,ppfd\n${entries.map(entry => `${entry.time},${entry.lux.toFixed(2)},${entry.ppfd.toFixed(2)}`).join('\n')}\n`);
  });
  $('#toolDialog').addEventListener('close', () => clearInterval(timer), {once: true});
}

function renderGuide(body) {
  title('Lux-niveaugids');
  const list = document.createElement('div');
  list.className = 'guide-list';
  [['Gangen en oriëntatie', '50–150 lux'], ['Woonruimte', '100–300 lux'], ['Lezen en eenvoudig werk', '300–500 lux'], ['Kantoor en klaslokaal', 'circa 500 lux'], ['Gedetailleerd werk', '750–1.500 lux'], ['Zeer fijn werk', '1.500+ lux']].forEach(([label, value]) => {
    const row = document.createElement('div'); row.className = 'guide-row';
    const strong = document.createElement('strong'); strong.textContent = label;
    const span = document.createElement('span'); span.textContent = value;
    row.append(strong, span); list.append(row);
  });
  body.append(list, result('Voor planten zijn PPFD en DLI bruikbaarder. Lux blijft spectrumafhankelijk.'));
}

function renderCalibration(body) {
  title('Begeleide kalibratie');
  const explanation = document.createElement('p');
  explanation.textContent = hasMeasurement()
    ? `${state.calibrated ? 'Huidige cameraschatting' : 'Relatieve lichtindex'}: ${nf0.format(state.lux)}. Plaats een betrouwbare luxmeter in hetzelfde vlak en voer de referentiewaarde in.`
    : 'Start de camera en wacht op een stabiele meting. Plaats daarna een betrouwbare luxmeter in hetzelfde vlak.';
  const label = document.createElement('label'); label.className = 'form-field'; label.textContent = 'Referentiemeter (lux)';
  const input = document.createElement('input'); input.type = 'number'; input.min = '1'; input.inputMode = 'decimal'; label.append(input);
  const apply = action('Kalibratie toepassen');
  apply.addEventListener('click', () => {
    if (!requireStable()) return;
    const reference = Number(input.value);
    if (!(reference > 0) || !(state.lux > 0)) return notify('Voer een geldige referentiewaarde in.');
    state.scale = state.calibrated ? state.scale * reference / state.lux : reference * 100 / state.lux;
    state.calibrated = true;
    const suffix = state.facing === 'user' ? 'user' : 'environment';
    localStorage.setItem(`lm-scale-${suffix}`, state.scale);
    localStorage.setItem(`lm-calibrated-${suffix}`, 'true');
    state.samples = [];
    notify('Kalibratie opgeslagen voor deze browser.');
    $('#toolDialog').close();
  });
  body.append(explanation, label, apply);
}

function requireStable() {
  if (state.stable) return true;
  notify('Wacht eerst op een stabiele meting.');
  return false;
}

function requireAbsolute() {
  if (!requireStable()) return false;
  if (state.calibrated) return true;
  notify('Kalibreer eerst met een referentiemeter.');
  return false;
}

function calibrationRequired() {
  return result('Voor deze functie is een stabiele, gekalibreerde meting nodig. De browser toont zonder referentiemeter alleen een relatieve lichtindex.');
}

function notify(message) {
  const original = $('#qualityPill').textContent;
  setQuality('warn', message);
  setTimeout(() => setQuality(state.stable ? 'stable' : 'idle', original), 2200);
}

async function shareCsv(filename, contents) {
  const file = new File([contents], filename, {type: 'text/csv'});
  if (navigator.share && navigator.canShare?.({files: [file]})) {
    try { await navigator.share({title: filename, files: [file]}); return; } catch (error) { if (error.name === 'AbortError') return; }
  }
  const url = URL.createObjectURL(file);
  const link = document.createElement('a'); link.href = url; link.download = filename; link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

$('#cameraButton').addEventListener('click', toggleCamera);
$('#dialogClose').addEventListener('click', () => $('#toolDialog').close());
$$('.bottom-nav button').forEach(button => button.addEventListener('click', () => switchView(button.dataset.view)));
$$('.source').forEach(button => button.addEventListener('click', () => selectSource(button)));
$$('.camera-option').forEach(button => button.addEventListener('click', () => selectCamera(button)));
$$('[data-tool]').forEach(button => button.addEventListener('click', () => openTool(button.dataset.tool)));
$$('[data-open-tool]').forEach(button => button.addEventListener('click', () => openTool(button.dataset.openTool)));
$('#photoperiod').addEventListener('input', event => {
  state.hours = Math.min(24, Math.max(1, Number(event.target.value) || 12));
  localStorage.setItem('lm-hours', state.hours);
  renderReadings();
});
window.addEventListener('pagehide', stopCamera);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden' && state.stream) stopCamera();
});

$('#photoperiod').value = state.hours;
loadCameraCalibration();
const savedSource = $$('.source').find(button => button.dataset.source === state.source) || $('.source');
selectSource(savedSource);
renderReadings();

if ('serviceWorker' in navigator) window.addEventListener('load', () => navigator.serviceWorker.register('./sw.js'));
