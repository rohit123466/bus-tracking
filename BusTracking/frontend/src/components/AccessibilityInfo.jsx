/**
 * Dedicated accessibility section for bus/route detail views - states plainly
 * that the reserved space is for wheelchair users only, and how many there are,
 * rather than relying on a small badge alone.
 */
export default function AccessibilityInfo({ accessible, spaces }) {
  if (accessible == null) return null;

  return (
    <div
      className={`accessibility-section ${accessible ? 'accessibility-yes' : 'accessibility-no'}`}
      role="note"
    >
      <span role="img" aria-hidden="true" className="accessibility-icon">
        ♿
      </span>
      <div>
        {accessible ? (
          <>
            <strong>Wheelchair accessible</strong>
            <p>
              This bus reserves {spaces} wheelchair {spaces === 1 ? 'space' : 'spaces'} for
              wheelchair users only.
            </p>
          </>
        ) : (
          <>
            <strong>Not wheelchair accessible</strong>
            <p>This bus has no reserved wheelchair space.</p>
          </>
        )}
      </div>
    </div>
  );
}
