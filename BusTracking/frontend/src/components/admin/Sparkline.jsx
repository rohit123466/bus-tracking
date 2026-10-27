import { sparklinePath } from '../../utils/admin';

/** Inline-SVG trend line of `field` over `samples` ({ at, …values }), with a faint area under it. */
export default function Sparkline({ samples, field, label, width = 120, height = 36 }) {
  const d = sparklinePath(samples, field, width, height);
  if (!d) {
    return <span className="spark spark-empty small muted" style={{ width, height }}>Collecting…</span>;
  }
  const pad = 2;
  return (
    <svg className="spark" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      <path d={`${d} L${width - pad} ${height} L${pad} ${height} Z`} className="spark-area" />
      <path d={d} className="spark-line" fill="none" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
