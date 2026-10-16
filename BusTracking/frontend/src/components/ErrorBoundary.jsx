import { Component } from 'react';
import { useLocation } from 'react-router-dom';

/**
 * Catches render errors in one page so the nav bar and the rest of the app keep
 * working; without it a single crash leaves a blank screen. Also covers a lazy
 * page chunk failing to load (e.g. after a redeploy replaced the old files).
 */
class Boundary extends Component {
  state = { error: null };

  static getDerivedStateFromError(error) {
    return { error };
  }

  componentDidCatch(error, info) {
    console.error('Page crashed', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div role="alert" style={{ padding: '2rem 1rem', maxWidth: 520, margin: '0 auto' }}>
        <h2>Something went wrong on this page.</h2>
        <p>The rest of the app still works. Try reloading, or pick another page from the menu.</p>
        <button type="button" onClick={() => window.location.reload()}>Reload</button>
      </div>
    );
  }
}

// Keyed by path, so navigating to another page clears a previous crash.
export default function ErrorBoundary({ children }) {
  const { pathname } = useLocation();
  return <Boundary key={pathname}>{children}</Boundary>;
}
