package functionalunit.cgra;

import cgramodel.CgraModel;
import cgramodel.ContextMaskHandleCompare;
import exceptions.AmidarSimulatorException;

public class HandleCompare {
	
	private int nrOfMemPorts;
	private int nrOfComparePorts;
	private int slotsPerPort;
	
	private int nrOfDirectComparisons;
	
	private int [][] handleMemory;
	private boolean [][] cacheAccessValidMemory;
	
	private ContextMaskHandleCompare contextMask;
	public long[] contexts;
	
	public HandleCompare(CgraModel model){
		
		contextMask = new ContextMaskHandleCompare();
		contextMask.createMask(model);
		
		nrOfMemPorts = model.getNrOfMemoryAccessPEs();
		nrOfComparePorts = ContextMaskHandleCompare.NR_OF_CONTROL_PORTS;
		slotsPerPort = ContextMaskHandleCompare.SLOTS_PER_MEM_PORT;
		
		nrOfDirectComparisons = nrOfMemPorts*(nrOfMemPorts-1)/2;
		
		handleMemory = new int[nrOfMemPorts][slotsPerPort];
		cacheAccessValidMemory = new boolean[nrOfMemPorts][slotsPerPort];
		
		contexts = new long[model.getContextMemorySize()];
	}
	
	public void checkHandles(int ccnt, boolean[] cacheAccessValid, int[] handles){
		long currentContext = contexts[ccnt];
		// Direct comparisons
		for(int j = 0; j < nrOfMemPorts; j++){
			for(int k = j+1; k < nrOfMemPorts; k++){
				int directCompareIndex = (-j*j+j*(2*nrOfMemPorts-3) + 2*k -2)/2;
				if(contextMask.readDirectCompareSel(currentContext, directCompareIndex)){
					if(cacheAccessValid[j] && cacheAccessValid[k] && handles[j] == handles[k]){
						throw new AmidarSimulatorException("Two Handles Are the SAME. The assumption that there is no aliasing was wrong. Rollback mechanism has to be invoked");
					}
				}
			}
		}
		// Mem comparisons
		for(int cmpPort = 0; cmpPort < nrOfComparePorts; cmpPort++){
			int currentMemPort  = contextMask.readInCompareMuxSel(currentContext, cmpPort);
			for(int memPort = 0; memPort < nrOfMemPorts; memPort++){
				for(int slot = 0; slot < slotsPerPort; slot++){
					if(contextMask.readMemCompareSel(currentContext, memPort*slotsPerPort + slot, cmpPort)){
//						System.out.println("compare is on: " + cacheAccessValid[currentMemPort] +" "+ cacheAccessValidMemory[memPort][slot] +" "+ handles[currentMemPort] +" "+ handleMemory[memPort][slot]);
//						System.out.println("   "+ memPort +":" + slot);
						if(cacheAccessValid[currentMemPort] && cacheAccessValidMemory[memPort][slot] && handles[currentMemPort] == handleMemory[memPort][slot]){

							throw new AmidarSimulatorException("Two Handles Are the SAME. The assumption that there is no aliasing was wrong. Rollback mechanism has to be invoked");
						}
					}
				}
			}
		}
		// Write to mem
		for(int memPort = 0; memPort < nrOfMemPorts; memPort++){
			if(contextMask.readWe(currentContext, memPort)){
				handleMemory[memPort][contextMask.readAddrIn(currentContext, memPort)] = handles[memPort];
//				System.out.println("STORING: " + handles[memPort] +  "  " + memPort+":"+contextMask.readAddrIn(currentContext, memPort));
				cacheAccessValidMemory[memPort][contextMask.readAddrIn(currentContext, memPort)] = cacheAccessValid[memPort];
			}
		}
	}

}
