import { prepareScene, clampScale } from './viewer-policy.mjs';
const viewport = document.getElementById('viewport');
const surface = document.getElementById('surface');
let transform = {x: 0, y: 0, scale: 1};
let svgWidth = 1, svgHeight = 1;
let generation = 0;
const pointers = new Map();
function apply() { surface.style.transform = `translate(${transform.x}px,${transform.y}px) scale(${transform.scale})`; }
function fit() {
  const w = viewport.clientWidth, h = viewport.clientHeight;
  transform.scale = clampScale(Math.min(Math.max(1, w - 32) / svgWidth, Math.max(1, h - 32) / svgHeight));
  transform.x = (w - svgWidth * transform.scale) / 2;
  transform.y = (h - svgHeight * transform.scale) / 2;
  apply();
}
function zoom(factor, x = viewport.clientWidth / 2, y = viewport.clientHeight / 2) {
  const previous = transform.scale;
  transform.scale = clampScale(previous * factor);
  transform.x = x - (x - transform.x) * transform.scale / previous;
  transform.y = y - (y - transform.y) * transform.scale / previous;
  apply();
}
function sanitizeSvg(svg) {
  svg.querySelectorAll('script,foreignObject,iframe,object,embed,audio,video,animate,animateMotion,animateTransform,set').forEach(e => e.remove());
  for (const element of [svg, ...svg.querySelectorAll('*')]) {
    for (const attribute of [...element.attributes]) {
      const name = attribute.name.toLowerCase(), value = attribute.value.trim();
      if (name.startsWith('on') || ((name === 'href' || name === 'xlink:href') &&
          !value.startsWith('#') && !(element.localName === 'image' && /^data:image\/(png|jpe?g|gif|webp);base64,/i.test(value)))) {
        element.removeAttribute(attribute.name);
      }
    }
  }
  svg.querySelectorAll('a').forEach(anchor => anchor.replaceWith(...anchor.childNodes));
  return svg;
}
const libraryLoads = new Map();
function loadLibrary(kind) {
  const name = kind === 'mermaid' ? 'mermaid' : 'excalidraw';
  if (!libraryLoads.has(name)) libraryLoads.set(name, new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'vendor/' + name + '.js'; script.onload = resolve; script.onerror = reject;
    document.head.appendChild(script);
  }));
  return libraryLoads.get(name);
}
async function render(options) {
  const ownGeneration = ++generation;
  window.diagram.ready = false; window.diagram.error = false;
  window.EXCALIDRAW_ASSET_PATH = new URL('vendor/',location.href).href;
  const {kind, content, theme, language} = options;
  document.documentElement.lang = language;
  for (const [key, value] of Object.entries(theme)) document.documentElement.style.setProperty('--' + key, value);
  document.documentElement.style.colorScheme = theme.dark ? 'dark' : 'light';
  const labels = language === 'en' ? {fit: 'Fit diagram', plus: 'Zoom in', minus: 'Zoom out', error: 'Could not display this diagram', empty: 'Empty drawing'} :
    {fit: 'Вписать диаграмму', plus: 'Увеличить', minus: 'Уменьшить', error: 'Не удалось показать диаграмму', empty: 'Пустой холст'};
  for (const [key, id] of [['fit','fit'],['plus','zoom-in'],['minus','zoom-out']]) document.getElementById(id).setAttribute('aria-label', labels[key]);
  surface.replaceChildren();
  try {
    await loadLibrary(kind);
    let svg;
    if (kind === 'mermaid') {
      if (content.length > 500000) throw new Error('Diagram too large');
      window.TriliumMermaid.initialize({startOnLoad: false, securityLevel: 'strict', suppressErrorRendering: true,
        maxTextSize: 500000, maxEdges: 4000, htmlLabels: false, theme: theme.dark ? 'dark' : 'default',
        flowchart: {htmlLabels: false}, fontFamily: 'sans-serif',
        secure: ['secure', 'securityLevel', 'startOnLoad', 'maxTextSize', 'maxEdges', 'suppressErrorRendering', 'theme', 'themeVariables', 'htmlLabels', 'flowchart']});
      const result = await window.TriliumMermaid.render('diagram-output-' + ownGeneration, content);
      const parsed = new DOMParser().parseFromString(result.svg, 'image/svg+xml');
      svg = document.importNode(parsed.documentElement, true);
    } else {
      const scene = prepareScene(content);
      if (!scene.elements.length) {
        surface.textContent = labels.empty;
        transform = {x: 16, y: 16, scale: 1}; apply();
        window.diagram.ready = true; window.ViewerStatus?.ready(); return;
      }
      const elements = window.TriliumExcalidraw.restoreElements(scene.elements, null);
      svg = await window.TriliumExcalidraw.exportToSvg({elements, files: scene.files,
        appState: {viewBackgroundColor: "#ffffff", exportBackground: false, exportWithDarkMode: !!theme.dark,
          exportEmbedScene: false}, exportPadding: 20, renderEmbeddables: false});
    }
    if (ownGeneration !== generation) return;
    const viewBox = svg.viewBox.baseVal;
    svgWidth = viewBox.width || parseFloat(svg.getAttribute('width')) || 1;
    svgHeight = viewBox.height || parseFloat(svg.getAttribute('height')) || 1;
    if (![svgWidth, svgHeight].every(n => Number.isFinite(n) && n > 0 && n <= 1e7)) throw new Error('Invalid bounds');
    svg.setAttribute('width', String(svgWidth)); svg.setAttribute('height', String(svgHeight));
    svg.style.maxWidth = 'none'; svg.style.display = 'block';
    surface.replaceChildren(sanitizeSvg(svg));
    await document.fonts.ready;
    fit();
    window.diagram.ready = true;
    window.ViewerStatus?.ready();
  } catch (_) {
    if (ownGeneration !== generation) return;
    surface.textContent = labels.error;
    transform = {x: 16, y: 16, scale: 1}; apply();
    window.diagram.error = true;
    window.ViewerStatus?.failed();
  }
}
viewport.addEventListener('pointerdown', event => {
  pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
  viewport.setPointerCapture(event.pointerId);
});
viewport.addEventListener('pointermove', event => {
  const previous = pointers.get(event.pointerId); if (!previous) return;
  const old = [...pointers.values()];
  pointers.set(event.pointerId, {x: event.clientX, y: event.clientY});
  const current = [...pointers.values()];
  if (current.length === 1) { transform.x += event.clientX - previous.x; transform.y += event.clientY - previous.y; apply(); }
  else if (current.length === 2) {
    const distance = points => Math.hypot(points[1].x - points[0].x, points[1].y - points[0].y);
    const rect = viewport.getBoundingClientRect();
    const oldCenter = {x: (old[0].x + old[1].x)/2 - rect.left, y: (old[0].y + old[1].y)/2 - rect.top};
    const center = {x: (current[0].x + current[1].x)/2 - rect.left, y: (current[0].y + current[1].y)/2 - rect.top};
    zoom(distance(current) / Math.max(1, distance(old)), oldCenter.x, oldCenter.y);
    transform.x += center.x - oldCenter.x; transform.y += center.y - oldCenter.y; apply();
  }
});
for (const type of ['pointerup','pointercancel']) viewport.addEventListener(type, event => pointers.delete(event.pointerId));
viewport.addEventListener('wheel', event => { event.preventDefault(); const rect = viewport.getBoundingClientRect();
  zoom(Math.exp(-event.deltaY * 0.002), event.clientX - rect.left, event.clientY - rect.top); }, {passive:false});
document.getElementById('fit').addEventListener('click', fit);
document.getElementById('zoom-in').addEventListener('click', () => zoom(1.25));
document.getElementById('zoom-out').addEventListener('click', () => zoom(0.8));
window.addEventListener('resize', fit);
window.diagram = {render, fit, zoom, ready:false, error:false, get transform() {return {...transform};}};
