#!/usr/bin/env node
// Usage: node docs/showcase/sources/render-clicks-preview.cjs
const fs = require('node:fs');
const path = require('node:path');
const sharp = require('sharp');
const { execFileSync } = require('node:child_process');
const root = path.resolve(__dirname, '..');
async function renderClicksPreview() {
  const source = path.join(__dirname, 'clicks-communicator/communicator-smoke-front.png');
  const frames = path.join(root, 'frames');
  const frame = await sharp(source).extract({ left: 611, top: 384, width: 698, height: 1152 }).png().toBuffer();
  const mask = Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="698" height="1152">
    <path fill="white" fill-rule="evenodd" d="M106 51 H592 Q647 51 647 106 V659 Q647 714 592 714 H106 Q51 714 51 659 V106 Q51 51 106 51 Z M118 94 A23 23 0 1 0 72 94 A23 23 0 1 0 118 94 Z"/>
  </svg>`);
  await sharp(mask).png().toFile(path.join(frames, 'clicks-communicator-screen-mask.png'));
  await sharp(frame).composite([{ input: mask, blend: 'dest-out' }]).png()
    .toFile(path.join(frames, 'clicks-communicator-frame.png'));
  fs.writeFileSync(path.join(frames, 'clicks-communicator-geometry.json'), JSON.stringify({
    id: 'clicks-communicator', width: 698, height: 1152,
    screen: { bounds: { x: 51, y: 51, width: 596, height: 663 },
      corners: [[51,51],[647,51],[647,714],[51,714]],
      mask: 'clicks-communicator-screen-mask.png', radius: 55, camera: [95,94,23] },
    source_crop: { left: 611, top: 384, width: 698, height: 1152 },
    presentation: 'Upcoming-device preview using the same original Notes capture and complete native T2E keyboard export as the Titan 2 Elite in the device comparison.'
  }, null, 2) + '\n');
  const capture = path.join(root, 'raw/titan-2-elite/hero-full.png');
  const { width, height } = await sharp(capture).metadata();
  const paper = { r: 248, g: 247, b: 243, alpha: 1 };
  const native = await sharp(path.join(root, 'raw/titan-2-elite/r6-full-ime.png')).png().toBuffer();
  const bar = await sharp(native).metadata();
  const status = await sharp(path.join(root, 'raw/titan-2-elite/r6-full.png'))
    .extract({ left: 0, top: 0, width, height: 104 }).png().toBuffer();
  const screen = path.join(root, 'composed/clicks-communicator-screen.png');
  fs.mkdirSync(path.dirname(screen), { recursive: true });
  await sharp(capture).extract({ left: 0, top: 0, width, height: Math.floor(height / 2) })
    .extend({ top: 0, left: 0, right: 0, bottom: height - Math.floor(height / 2), background: paper })
    .composite([{ input: status, left: 0, top: 0 }, { input: native, left: 0, top: height - bar.height }])
    .png().toFile(screen);
  const output = path.join(root, 'exports/clicks-communicator-preview.png');
  // compose-screen refuses existing outputs; compose into a fresh temporary directory.
  const temp = fs.mkdtempSync(path.join(require('node:os').tmpdir(), 'plektra-clicks-'));
  try {
    const result = path.join(temp, 'preview.png');
    execFileSync(process.execPath, [path.join(__dirname, 'compose-screen.cjs'), 'clicks-communicator', screen, result],
      { env: { ...process.env, PLEKTRA_FRAMES_DIR: frames } });
    fs.copyFileSync(result, output);
  } finally { fs.rmSync(temp, { recursive: true, force: true }); }
}
module.exports = renderClicksPreview;
if (require.main === module) renderClicksPreview().catch(error => { console.error(error); process.exitCode = 1; });
