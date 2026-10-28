/** Only local embedded raster images are accepted; remote URLs and active SVG never enter the renderer. */
export function safeImageData(value) {
  return typeof value === 'string' && value.length <= 28 * 1024 * 1024 &&
    /^data:image\/(png|jpeg|jpg|gif|webp);base64,[A-Za-z0-9+/=\r\n]+$/i.test(value);
}
export function prepareScene(content) {
  if (typeof content !== 'string' || content.length > 32 * 1024 * 1024) throw new Error('Scene too large');
  const scene = JSON.parse(content);
  if (!scene || !Array.isArray(scene.elements) || scene.elements.length > 10000) throw new Error('Invalid scene');
  const files = Object.create(null);
  for (const [id, file] of Object.entries(scene.files || {})) {
    if (file && safeImageData(file.dataURL)) files[id] = { id, dataURL: file.dataURL, mimeType: file.mimeType,
      created: Number(file.created) || 0, lastRetrieved: Number(file.lastRetrieved) || 0 };
  }
  const supported = new Set(['rectangle', 'ellipse', 'diamond', 'line', 'arrow', 'freedraw', 'text', 'image', 'frame', 'magicframe']);
  const elements = scene.elements.filter(e => e && supported.has(e.type) && !e.isDeleted &&
    [e.x, e.y, e.width, e.height].every(n => typeof n === 'number' && Number.isFinite(n) && Math.abs(n) <= 1e7))
    .map(e => ({...e, link: null, customData: undefined, ...(e.type === 'image' && !files[e.fileId] ? {status: 'error'} : {})}));
  return {elements, files};
}
export function clampScale(scale) { return Math.min(16, Math.max(0.02, scale)); }
