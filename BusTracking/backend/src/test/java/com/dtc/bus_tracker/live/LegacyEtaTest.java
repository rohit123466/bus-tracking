package com.dtc.bus_tracker.live;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.dtc.bus_tracker.util.EtaCalculator;
class LegacyEtaTest {
 @Test void missingOrStationarySpeedNeverInventsAnAverage(){
  assertNull(EtaCalculator.estimateMinutes(1000,null));assertNull(EtaCalculator.estimateMinutes(1000,0.0));assertNull(EtaCalculator.estimateMinutes(1000,Double.NaN));
  assertEquals(3,EtaCalculator.estimateMinutes(1000,20.0));
 }
}
