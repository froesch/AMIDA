package amidar;

import java.util.LinkedHashMap;

public class SpeedupMeasurementResult {
	
	AmidarSimulationResult baselineShort;
	AmidarSimulationResult baselineLong;
	
	boolean baselineShortAvailable = false;
	boolean baselineLongAvailable = false;
	
	LinkedHashMap<Integer, AmidarSimulationResult> ticksShort;
	LinkedHashMap<Integer, AmidarSimulationResult> ticksLong;
	

	public SpeedupMeasurementResult() {
		ticksShort = new LinkedHashMap<>();
		ticksLong = new LinkedHashMap<>();
	}
	
	public void addBaseline(AmidarSimulationResult baseline, boolean isShort){
		if(isShort){
			this.baselineShort = baseline;
			baselineShortAvailable = true;
		} else {
			this.baselineLong = baseline;
			baselineLongAvailable = true;
		}
		
	}
	
	public void addTicks(AmidarSimulationResult result, int unroll, boolean isShort){
		LinkedHashMap<Integer, AmidarSimulationResult> ticks;
		if(isShort){
			ticks = ticksShort;
		} else {
			ticks = ticksLong;
		}
		
		ticks.put(unroll, result);
		
	}
	
	
	public double getSpeedup(int unroll){
		double res = 0;
		
		long base = baselineLong.getTicks() -baselineShort.getTicks();
		
		if(ticksLong.get(unroll) == null ||ticksLong.get(unroll) == null ){
			return 1.0;
		}
		
		long ticks = ticksLong.get(unroll).getTicks() - ticksShort.get(unroll).getTicks();
		
		res = (double)base/(double)ticks;
		
		return res;
	}
	
	public boolean isBaselineShortAvailable() {
		return baselineShortAvailable;
	}

	public boolean isBaselineLongAvailable() {
		return baselineLongAvailable;
	}
	
	public boolean isBaseLineAvaliable(boolean isShort){
		if(isShort){
			return isBaselineShortAvailable();
		} else {
			return isBaselineLongAvailable();
		}
		
	}
	
	

}
