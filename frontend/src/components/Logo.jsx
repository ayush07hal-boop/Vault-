import { useId } from 'react';
import { DEPTH, PALETTE, SWOOSHES, VIEWBOX } from '../logoPaths';

/**
 * The Vault mark: three nested swooshes (red / black / red) with a 3D treatment: a dark extruded layer
 * underneath each shape, vertical gradients for volume, a glossy highlight and a soft drop shadow.
 */
export default function Logo({ size = 32, title = 'Vault', className = '' }) {
  const uid = useId().replace(/:/g, '');
  const [vx, vy, vw, vh] = VIEWBOX.split(' ').map(Number);
  const width = Math.round((size * vw) / vh);

  return (
    <svg className={`logo ${className}`} width={width} height={size} viewBox={VIEWBOX} role="img" aria-label={title}>
      <defs>
        {Object.entries(PALETTE).map(([tone, c]) => (
          <linearGradient key={tone} id={`${uid}-${tone}`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor={c.top} />
            <stop offset=".5" stopColor={c.mid} />
            <stop offset="1" stopColor={c.bottom} />
          </linearGradient>
        ))}
        <linearGradient id={`${uid}-shine`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity=".65" />
          <stop offset=".45" stopColor="#fff" stopOpacity=".08" />
          <stop offset="1" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
        <filter id={`${uid}-shadow`} x="-20%" y="-20%" width="140%" height="150%">
          <feDropShadow dx="0" dy="3" stdDeviation="2.4" floodColor="#000" floodOpacity=".32" />
        </filter>
      </defs>

      <g filter={`url(#${uid}-shadow)`}>
        {SWOOSHES.map((s, i) => (
          <g key={i}>
            {/* extrusion: the same shape, darker, pushed down */}
            <path d={s.d} transform={`translate(0 ${DEPTH})`} fill={PALETTE[s.tone].side} />
            <path d={s.d} fill={`url(#${uid}-${s.tone})`} />
            {/* glossy highlight */}
            <path d={s.d} fill={`url(#${uid}-shine)`} />
          </g>
        ))}
      </g>
    </svg>
  );
}
