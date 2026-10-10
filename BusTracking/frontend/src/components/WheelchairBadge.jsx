import React from 'react';
import './WheelchairBadge.css';

import { getWheelchairStatus } from '../utils/wheelchairStatus';

export default function WheelchairBadge({
  status,
  showLabel = false,
  size = 'md',
  className = '',
}) {
  const config = getWheelchairStatus(status);

  return (
    <span
      className={`wheelchair-badge wheelchair-badge--${config.status} wheelchair-badge--${size} ${className}`}
      title={config.tooltip}
      aria-label={config.tooltip}
      style={{
        '--wc-color': config.color,
        '--wc-bg': config.bg,
        '--wc-border': config.border,
      }}
    >
      <span className="wheelchair-icon" role="img" aria-hidden="true">
        ♿
      </span>
      {showLabel && <span className="wheelchair-label">{config.label}</span>}
    </span>
  );
}
