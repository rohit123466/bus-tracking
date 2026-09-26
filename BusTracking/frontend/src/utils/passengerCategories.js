export const PASSENGER_CATEGORIES = [
  { value: 'ADULT', icon: '🧑', label: 'Adult' },
  { value: 'SENIOR_CITIZEN', icon: '🧓', label: 'Senior Citizen' },
  { value: 'DIVYANGJAN', icon: '♿', label: 'Person with Disability / Divyangjan' },
];

export const categoryIcon = (value) => PASSENGER_CATEGORIES.find((c) => c.value === value)?.icon || '🧑';
