package functionalunit.cache;

import exceptions.AmidarSimulatorException;

/**
 * Memory class for data and handle table
 * @author Patrick Appenheimer
 *
 */
public class Memory{	
	
	final static int MEMSIZE = 100000000;
	final static int HANDLEENTRIES = 10000000;
	
	public int[] memory;
	
	private HTEntry[] handleTable;
	private int handles = 0;
	
	public Memory(){
		memory = new int[MEMSIZE];
		handleTable = new HTEntry[HANDLEENTRIES];
	}
	
	public void initialMem(int[] init){
		System.arraycopy(init, 0, memory, 0, init.length);
	}
	
	public void registerHandle(int index, int mid, int flags, int cti, long size, long addr) {
		if(index>=HANDLEENTRIES) throw new AmidarSimulatorException("Handle Table full!");
		if(addr+size>=MEMSIZE) throw new AmidarSimulatorException("Memory full!");
		handleTable[index] = new HTEntry();
		handleTable[index].setAddr(addr);
		handleTable[index].setSize(size);
		handleTable[index].setCTI(cti);
		handleTable[index].setFlags(flags);
		handleTable[index].setMID(mid);
		handles++;
	}
	
	public void write(int addr, int data){
		memory[addr] = data;
	}
	
	public int read(int addr){
		return memory[addr];
	}
	
	public int getAddrHT(int handle){
		return handleTable[handle].getAddr();
	}
	
	public int getSizeHT(int handle){
		return handleTable[handle].getSize();
	}
	
	public int getCTIandFlagsHT(int handle){
		int cti_flags = handleTable[handle].getCTI() << 16;
		return cti_flags + handleTable[handle].getFlags();
	}
	
	public int getMidHT(int handle){
		return handleTable[handle].getMID();
	}
	
	public void setFlags(int handle, int flags){
		handleTable[handle].setFlags(flags);
	}
	
	public void setMID(int handle, int mid){
		handleTable[handle].setMID(mid);
	}
	
	public int getHandles(){
		return handles;
	}


}
