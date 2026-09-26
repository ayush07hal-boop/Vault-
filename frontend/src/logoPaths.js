// Geometry of the Vault logo: three nested, tapering "V" swooshes whose tips flare outward.
// Pure data so the React <Logo/> and the static favicon are generated from the same drawing.

const CX = 60;

/** One tapering swoosh. Edges meet at the two tips and are thickest at the bottom point. */
function swoosh({ w, tipY, bottom, thick, flare }) {
  const h = bottom - tipY;
  const L = [CX - w, tipY];
  const R = [CX + w, tipY];
  const out1 = [flare, h * 0.34]; // control offsets from the tip (outer edge)
  const out2 = [w * 0.42, h * 0.1]; // control offsets from the bottom (outer edge)
  const inn1 = [flare * 1.55 + thick * 0.1, h * 0.36];
  const inn2 = [w * 0.4, h * 0.1];
  const bi = bottom - thick;
  const f = (n) => Number(n.toFixed(2));
  return [
    `M${f(L[0])} ${f(L[1])}`,
    `C${f(L[0] + out1[0])} ${f(L[1] + out1[1])} ${f(CX - out2[0])} ${f(bottom - out2[1])} ${CX} ${f(bottom)}`,
    `C${f(CX + out2[0])} ${f(bottom - out2[1])} ${f(R[0] - out1[0])} ${f(R[1] + out1[1])} ${f(R[0])} ${f(R[1])}`,
    `C${f(R[0] - inn1[0])} ${f(R[1] + inn1[1])} ${f(CX + inn2[0])} ${f(bi - inn2[1])} ${CX} ${f(bi)}`,
    `C${f(CX - inn2[0])} ${f(bi - inn2[1])} ${f(L[0] + inn1[0])} ${f(L[1] + inn1[1])} ${f(L[0])} ${f(L[1])}`,
    'Z',
  ].join(' ');
}

/** Back-to-front drawing order is outer -> inner. `tone` picks the colour family. */
export const SWOOSHES = [
  { tone: 'red', d: swoosh({ w: 56, tipY: 6, bottom: 88, thick: 10.5, flare: 16 }) },
  { tone: 'black', d: swoosh({ w: 43, tipY: 19, bottom: 72, thick: 9.5, flare: 13 }) },
  { tone: 'red', d: swoosh({ w: 30, tipY: 30, bottom: 56, thick: 8, flare: 11 }) },
];

export const VIEWBOX = '0 0 120 100';
export const DEPTH = 2.6; // extrusion offset in viewBox units

/** Gradient stops shared by the component and the favicon. */
export const PALETTE = {
  red: { top: '#ff6b6f', mid: '#e5141c', bottom: '#7d060b', side: '#4d0308' },
  black: { top: '#7385ee', mid: '#3247b8', bottom: '#141d63', side: '#0c1244' },
};
