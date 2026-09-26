import { PASSENGER_CATEGORIES } from '../utils/passengerCategories';
import './Ticketing.css';

export default function PassengerCategoryPicker({ value, onChange, disabled }) {
  return (
    <div className="segmented" role="radiogroup" aria-label="Passenger category">
      {PASSENGER_CATEGORIES.map((c) => (
        <button
          key={c.value}
          type="button"
          role="radio"
          aria-checked={value === c.value}
          disabled={disabled}
          className={'segmented-option' + (value === c.value ? ' segmented-option--active' : '')}
          onClick={() => onChange(c.value)}
        >
          <span aria-hidden="true">{c.icon}</span> {c.label}
        </button>
      ))}
    </div>
  );
}
