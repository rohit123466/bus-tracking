import React from 'react';
import './WheelchairBadge.css';

/**
 * Normalizes wheelchair availability value.
 * Handles boolean, 1/0, string 'true'/'false', or null/undefined.
 */
export function getWheelchairStatus(val) {
  if (val === true || val === 1 || val === '1' || val === 'true') {
    return {
      status: 'available',
      label: 'Space Available',
      tooltip: 'Wheelchair space: Available',
      color: '#22C55E',
      bg: 'rgba(34, 197, 94, 0.12)',
      border: 'rgba(34, 197, 94, 0.35)',
      dotColor: '#22C55E',
    };
  }
  if (val === false || val === 0 || val === '0' || val === 'false') {
    return {
      status: 'unavailable',
      label: 'Not Available',
      tooltip: 'Wheelchair space: Not Available',
      color: '#EF4444',
      bg: 'rgba(239, 68, 68, 0.12)',
      border: 'rgba(239, 68, 68, 0.35)',
      dotColor: '#EF4444',
    };
  }
  return {
    status: 'unknown',
    label: 'Unknown',
    tooltip: 'Wheelchair space: Data not available',
    color: '#9CA3AF',
    bg: 'rgba(156, 163, 175, 0.12)',
    border: 'rgba(156, 163, 175, 0.3)',
    dotColor: '#9CA3AF',
  };
}

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
