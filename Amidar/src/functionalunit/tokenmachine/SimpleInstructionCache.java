package functionalunit.tokenmachine;

/**
 * Very simple implementation of Instruction cache. Cache always hits
 * @author jung
 *
 */
public class SimpleInstructionCache implements InstructionCache {
	
	
	
	byte[] memory;
	
	byte data;
	
	int lastRequestedAddr = -1;
	
	
	public void initMemory(byte[] memory){
		this.memory = memory;
	}
	

	public boolean requestData(int addr) {
		if(lastRequestedAddr == addr){
			data = memory[addr];
			return true;
		} else {
			lastRequestedAddr = addr;
			return false;
		}
	}

	public byte getData() {
		return data;
	}

	public boolean isReady() {
		return true;
	}

	public void invalidate() {
		// Nothing to do as this cache always hits

	}
	
	public byte[] getMemory(){
		return memory;
	}

}
