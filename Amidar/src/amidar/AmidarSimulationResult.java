package amidar;

import java.io.Serializable;

import functionalunit.tokenmachine.Profiler;

public class AmidarSimulationResult implements Serializable {
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1385609964551771971L;
	private long ticks;
	private long byteCodes;
	private long tokens;
	private int executionDuration;
	private double energy;
	private Amidar core;
	private Profiler profiler;
	
	public long getTicks() {
		return ticks;
	}
	public void setTicks(int ticks) {
		this.ticks = ticks;
	}
	public long getByteCodes() {
		return byteCodes;
	}
	public void setByteCodes(int byteCodes) {
		this.byteCodes = byteCodes;
	}
	public long getTokens() {
		return tokens;
	}
	public void setTokens(int tokens) {
		this.tokens = tokens;
	}
	public int getExecutionDuration() {
		return executionDuration;
	}
	public void setExecutionDuration(int executionDuration) {
		this.executionDuration = executionDuration;
	}
	public Profiler getProfiler() {
		return profiler;
	}
	public void setProfiler(Profiler profiler) {
		this.profiler = profiler;
	}
	public double getEnergy(){
		return energy;
	}
	public void setEnergy(double energy){
		this.energy = energy;
	}
	public Amidar getAmidarCore() {
		return core;
	}
	
	public AmidarSimulationResult(long ticks, long byteCodes, long tokens, int executionDuration, Profiler profiler, Amidar core){
		this.ticks = ticks;
		this.byteCodes = byteCodes;
		this.tokens = tokens;
		this.executionDuration = executionDuration;
		this.profiler = profiler;
		this.core = core;
	}
	
	public AmidarSimulationResult(long ticks, int executionDuration, double energy, Profiler profiler, Amidar core){
		this.ticks = ticks;
		this.executionDuration = executionDuration;
		this.energy = energy;
		this.profiler = profiler;
		this.byteCodes = profiler.getGlobalBytecodeCount();
		this.core = core;
	}

	
	

}
