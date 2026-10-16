import { Link } from 'react-router-dom';

export default function NotFoundPage() {
  return (
    <div style={{ padding: '2rem 1rem', maxWidth: 520, margin: '0 auto' }}>
      <h2>Page not found</h2>
      <p>This address doesn't match any page.</p>
      <Link to="/">Go to the live map</Link>
    </div>
  );
}
