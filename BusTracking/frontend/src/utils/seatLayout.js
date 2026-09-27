// Display geometry for the reference floor plan. Keep the API's seat objects
// intact: ticketing, camera badges and selections still use their original IDs.
export function referenceSeatLayout(seats) {
  const positions = new Map();
  const regular = seats.filter((seat) => seat.seatType !== 'WHEELCHAIR')
    .sort((a, b) => a.seatNumber.localeCompare(b.seatNumber, undefined, { numeric: true }));
  const wheelchairs = seats.filter((seat) => seat.seatType === 'WHEELCHAIR');
  wheelchairs.forEach((seat, index) => {
    positions.set(seat.seatId, { row: 3 + index * 2, column: 3, rowSpan: 2, columnSpan: 2 });
  });

  const beforeRear = Math.max(0, regular.length - 5);
  let next = 0;
  let row = 0;
  const bayEnd = 3 + wheelchairs.length * 2;
  while (next < beforeRear) {
    for (const column of [0, 1, 3, 4]) {
      const doorOrEntry = column < 2 && (row < 2 || row === 4);
      const wheelchairBay = column > 2 && row >= 3 && row < bayEnd;
      if (doorOrEntry || wheelchairBay || next >= beforeRear) continue;
      positions.set(regular[next++].seatId, { row, column, rowSpan: 1, columnSpan: 1 });
    }
    row++;
  }
  const rearRow = Math.max(row, bayEnd, 5);
  regular.slice(next).forEach((seat, column) => {
    positions.set(seat.seatId, { row: rearRow, column, rowSpan: 1, columnSpan: 1 });
  });
  return { positions, rows: rearRow + 1 };
}
