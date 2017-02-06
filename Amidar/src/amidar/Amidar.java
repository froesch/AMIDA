package amidar;


import java.awt.Graphics;
import java.awt.Graphics2D;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.NavigableMap;
import java.util.TreeMap;

import cgramodel.ContextMaskCBox;
import cgramodel.ContextMaskContextControlUnit;
import cgramodel.ContextMaskPE;
import dataContainer.ByteCode;
import dataContainer.Invokation;
import dataContainer.MethodDescriptor;
import dataContainer.SynthesizedKernelDescriptor;
import javasim.synth.HardGen;
import javasim.synth.SequenceNotSynthesizeableException;
import javasim.synth.model.CGRAIntrinsics;
import bus.Arbiter;
import scheduler.RCListSched.AliasingSpeculation;
import tracer.Trace;
import tracer.TraceManager;
import functionalunit.*;
import functionalunit.cache.Cache;
import functionalunit.cache.Memory;
import functionalunit.cgra.PE;
import functionalunit.tables.LoopProfileTableEntry;
import functionalunit.tokenmachine.Profiler;
import functionalunit.tokenmachine.TokenMachineAdla;
import amidar.axtLoader.AXTLoader;

/**
 * The AMIDAR Model
 * @author jung
 *
 */
public class Amidar {
	
	private ConfMan configManager;
	private TraceManager traceManager;
	private boolean synthesis;
	private int numberOfFus;
	
	private String[] methodNames; // Only for Debugging and Synthesis report
	
	// All Functional Units
	@SuppressWarnings("rawtypes")
	FunctionalUnit [] functionalUnits;
	TokenMachine tokenMachine;
	ObjectHeap heap;
	
	Arbiter arbiter;

	/**
	 * Creates a new Amidar processor for simulation
	 * @param configManager Config Manager which contains all parameters
	 * @param traceManager Trace Manager which handles all Traces
	 * @param synthesis decides whether synthesis is on or off
	 */
	public Amidar(ConfMan configManager, TraceManager traceManager){
		this.configManager = configManager;
		this.traceManager = traceManager;
		this.synthesis = configManager.getSynthesis();
		
		HashMap<String,String> fuConfigFiles = configManager.getFuConfigFiles();
		
		IALU ialu = new IALU(fuConfigFiles.get("IALU"), traceManager);
		FALU falu = new FALU(fuConfigFiles.get("FALU"), traceManager);
		TokenMachine tokenMachine = new TokenMachineAdla(fuConfigFiles.get("TOKENMACHINE"), traceManager);
		Memory memory = new Memory();
		ObjectHeap heap = new ObjectHeap(memory, fuConfigFiles.get("HEAP"), traceManager, synthesis);
		FrameStack frameStack = new FrameStack(fuConfigFiles.get("FRAMESTACK"), traceManager);
		
		
		if(synthesis){
			numberOfFus = 6;
			CGRA  cgra = new CGRA(fuConfigFiles.get("CGRA"), false, traceManager, memory);
			heap.setMOESICaches(cgra.getCaches());
			functionalUnits = new FunctionalUnit[numberOfFus];
			functionalUnits[5] = cgra;
			tokenMachine.setFUs(ialu, falu, heap, frameStack,cgra);
			
		} else {
			numberOfFus = 5;
			functionalUnits = new FunctionalUnit[numberOfFus];
			tokenMachine.setFUs(ialu, falu, heap, frameStack, null);
		}
		
		// TODO Order? Maybe relevant for the Bus
		this.tokenMachine = tokenMachine;
		this.heap = heap;
		functionalUnits[0] = tokenMachine;
		functionalUnits[1] = falu;
		functionalUnits[2] = ialu;
		functionalUnits[3] = heap;
		functionalUnits[4] = frameStack;
		
		
		arbiter = new Arbiter(functionalUnits);
		
	}
	
	/**
	 * Sets the application that shall be executed on AMIDAR
	 * @param application the path to the application
	 */
	public void setApplication(AXTLoader axtLoader){
		methodNames = axtLoader.getMethodNames();
		tokenMachine.initTables(axtLoader);
		tokenMachine.setMethodNames(methodNames);
		heap.initHeap(axtLoader);
	}
	
	boolean started =  false;
	
	/**
	 * Simulates execution on AMIDAR
	 * @param saveCore determines whether the core should be saved in the simulation results.
	 * @return The simulation results
	 */
	public AmidarSimulationResult simulate(boolean saveCore){
		int SYNTHESIS_INTERVAL = 1;//TODO
		
//		int[] time = new int[functionalUnits.length + 1];
		
		boolean ready = false;
		long ticks = 0;
		
		Trace ticksTracer = traceManager.getf("ticks");
		
		long  startTime = System.nanoTime();
		
		Cache [] caches = heap.getCaches();
		
		BufferedWriter [] cacheStateOutput = new  BufferedWriter[caches.length];
		
//		try{
//		for(int i = 0; i< caches.length; i++){
//			FileWriter fw = new FileWriter("log/cache"+i+".csv");
//			BufferedWriter bw = new BufferedWriter(fw);
//			cacheStateOutput[i] = bw;
//		}
//		} catch(IOException e){
//			
//		}
		
	
		
		
//		long avTime = 0;
		while(!ready){
//			long start = System.nanoTime();
			if(tokenMachine.startedActualApplication){
				if(started == false){
					if(traceManager.getf("heap").active()){
						heap.getOHTrace().appendTrace(ObjectHeap.RESET, 0, 0, 0, 0, 0, 0, 0);
					}
					for(FunctionalUnit fu : functionalUnits){
						fu.resetExecutionCounter();
					}
					
//					for(Cache ca: heap.getCaches()){
//						ca.resetStatistics();
//					}
					started = true;
				}
//				traceManager.getf("methods").activate();
//				traceManager.getf("ticks").activate();
				ticks++;
			}
			ready = true;
			if(ticksTracer.active()){
				ticksTracer.println("------------------------------------------- Cycle "+ticks+ " --------------------------------------------");
				ticksTracer.println();
			}
			long fuTime = System.nanoTime();
			for(int i = 0; i < numberOfFus; i++){
				ready &= functionalUnits[i].tick();
//				long fuTime2 = System.nanoTime();
//				time[i] += fuTime2-fuTime;
//				fuTime = System.nanoTime();
			}
			arbiter.tick();
//			time[functionalUnits.length]+= System.nanoTime()-fuTime;
			
			if(synthesis && (ticks % SYNTHESIS_INTERVAL) == 0 && tokenMachine.startedActualApplication){
//				System.err.println("STARTSYNTH");
				// Call synthesis algorithm here - normaly this is done by a sw thread invoked by the thread scheduler
				synthesize();
			}
			
			
//			for(Cache cach: caches){
//				double used = cach.used();
//				System.out.print((int)(used*100)+" ");
//			}System.out.println();
			
			
//			for(int i = 0; i < caches.length; i++){
//				int[] cacheState = caches[i].getState();
//				try {
//					cacheStateOutput[i].write(cacheState[0]);
//					for(int index = 1; index < cacheState.length; index++){
//
//						cacheStateOutput[i].write(", "+cacheState[index]);
//
//					}
//					cacheStateOutput[i].write("\n");
//				} catch (IOException e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//			}
			
			
//			long stop = System.nanoTime();
//			long tt = stop-start;
//			avTime = (long)(0.1*tt+0.9*avTime);
//			System.out.println("TT " + (avTime));
			
		}
		
//		try{
//			for(int i = 0; i< caches.length; i++){
//				cacheStateOutput[i].close();
//			}
//			} catch(IOException e){
//				
//			}
		long stoptime = System.nanoTime();
		
		long executionTime = stoptime - startTime;
		int executionTimeMillis = (int)(executionTime/1000000L);
		
//		for(int i = 0; i<time.length; i++){
//			System.out.println(i + " Time " + time[i]/1000000 + "us");
//		}
		
		double energy = 0;
		
		for(FunctionalUnit fu: functionalUnits){
//			System.out.println("Energyconsumption "+ fu+": "+ fu.getDynamicEnergy());
			energy += fu.getDynamicEnergy() + fu.getStaticEnergy()*ticks;
		}

		Trace cacheTrace = traceManager.getf("caches");
		if(cacheTrace.active()){
			cacheTrace.setPrefix("caches");
			if(synthesis) cacheTrace.printTableHeader("USED CACHES:   Heap Cache  +  "+(heap.getCacheCount()-1)+" CGRA Caches");
			else cacheTrace.printTableHeader("USED CACHES:   Heap Cache");
			heap.cacheTrace();
		}
		
		AmidarSimulationResult res;
		if(saveCore){
			res = new AmidarSimulationResult(ticks, executionTimeMillis, energy, tokenMachine.getProfiler(), this);
		} else{
			res = new AmidarSimulationResult(ticks, executionTimeMillis, energy, tokenMachine.getProfiler(), null);
		}
		return res;
	}
	
	
	int contextPointer = 0;
	byte kernelPointer = 0;
	int synthConstPointer = 0;
	
	String patch = "";
	
	/**
	 * Invokes the Synthesis algorithm - THIS NO PART OF THE HW - this will be a SW thread running on Amidar itself
	 * the best kernel found by the profliler will be synthesized
	 */
	public void synthesize(){
		int REPLACED = 10;

		
		///////////////////// PSEUDO PERIPHERY ACCESSS ///////////////////// 
		
		
		Profiler profiler = tokenMachine.getProfiler();
		
		
		LoopProfileTableEntry loop = profiler.getBestCandidate();
		if(loop == null){
			return;
		}
		
		int methodIndex = loop.get(LoopProfileTableEntry.AMTI);
		int start = loop.get(LoopProfileTableEntry.START);
		int stop = loop.get(LoopProfileTableEntry.END) -3 ;
		
		if(stop <= 0 || (start <= tokenMachine.instructionMemoryAddress && start+REPLACED>= tokenMachine.instructionMemoryAddress )){
			return;
		}
		
		int state = synthesize(methodIndex, start, stop, true);
		
		loop.setData(LoopProfileTableEntry.SYNTHESIZED, state);
		
	}
	
	/**
	 * Invokes the Synthesis algorithm - THIS NO PART OF THE HW - this is only for the simulator
	 * @param methodName
	 * @param schedule
	 */
	public void synthesize(String methodName, boolean schedule){
		
		
		Trace synthTrace = traceManager.getf("synthesis");
		
		MethodDescriptor[] methodTable = tokenMachine.getMethodTable();
		
		int methodIndex = -1, start = 0, stop = 0;
		
		
		for(int i = 0; i< methodTable.length; i++){
			if(methodTable[i].getMethodName().equals(methodName)){
				methodIndex = i;
				start = methodTable[i].getCodeRef();
				stop = start + methodTable[i].getCodeLength();
				break;
			}
		}
		if(methodIndex == -1){
			synthTrace.println("Method " + methodName + " not found");
			String className = methodName.split("\\.")[0];
			synthTrace.println("Available methods of this class are:");
			for(int i = 0; i< methodTable.length; i++){
				if(methodTable[i].getMethodName().contains(className)){
					synthTrace.println("\t- " + methodTable[i].getMethodName());
				}
			}
		}
			
		
		
		
		
		//1 Collect backward jumps
		TreeMap<Integer,Integer> backwardJumps = new TreeMap<>();
		
		byte[] code = tokenMachine.getCode();
		
		for(int i = start; i < stop; i++){
			if(code[i] >= (byte)0x99 && code[i] <= (byte)0xA7){ 				//track all jumps
				int gotoVal = (short)((short)((code[i+1]& 0xFF)<<8) | (short)(code[i+2]& 0xFF));
				if (gotoVal < 0){
					backwardJumps.put(i, gotoVal);
				}
			}
//			System.out.println(ByteCode.debug(code[i]));
			i+= ByteCode.getParamCount(code[i]);
		}
		
//		backwardJumps.
		
//		System.out.println(start + " peter " + stop);
		
		NavigableMap<Integer, Integer> bJumps = backwardJumps.descendingMap();
		
		int alreadyCovered = stop;
		 
		for(Integer i : backwardJumps.descendingKeySet()){
			if( i < alreadyCovered){
				alreadyCovered = i + backwardJumps.get(i);
				synthesize(methodIndex, alreadyCovered, i, schedule);
				synthTrace.println("\t"+methodName+"-"+(alreadyCovered-start)+"-"+(i-start));
			}
			
			
			
			
		}
		
		//2 Synthesize all outer loops
		
		
		
		
		
		
	}
	
	/**
	 * Invokes the Synthesis algorithm - THIS NO PART OF THE HW - this will be a SW thread running on Amidar itself
	 * 
	 */
	public int synthesize(int methodIndex, int start, int stop, boolean schedule){
		int REPLACED = 10;
		
		int returnValue;

		Trace synthTracer = traceManager.getf("synthesis");
		
		///////////////////// PSEUDO PERIPHERY ACCESSS ///////////////////// 
		long time = System.nanoTime();
		
		
		
		HashMap<String, Object> synthesisConfig = configManager.getSynthesisConfig();
		

		MethodDescriptor[] methodTable = tokenMachine.getMethodTable();
		SynthesizedKernelDescriptor[] kernelTable = tokenMachine.getKernelTable();
		ArrayList<Invokation> invokationHistory = tokenMachine.getInvocationHistory();
		
		boolean constantFolding = (Boolean)synthesisConfig.get("CONSTANT_FOLDING");
		boolean cse = (Boolean)synthesisConfig.get("CSE");
		boolean inline = (Boolean)synthesisConfig.get("INLINE");
		int maxUnrollLength = (Integer)synthesisConfig.get("MAX_UNROLL_LENGTH");
		int unroll = ((Long)synthesisConfig.get("UNROLL")).intValue();
		AliasingSpeculation aliasing = ((AliasingSpeculation)synthesisConfig.get("ALIASING_SPECULATION"));
		byte[] code = tokenMachine.getCode();
		boolean writeContexts = false; // TODO: include in config???
		
		String cgraModel = configManager.getFuConfigFiles().get("CGRA");
		
		
		///////////////// END PSEUDO PERIPHERY ACCESSS /////////////////////
		LinkedHashMap<String,LinkedHashSet<Integer>> methodBlacklist = (LinkedHashMap<String,LinkedHashSet<Integer>>)synthesisConfig.get("BLACKLIST");
		
		
		
		
		if(synthTracer.active()){
			synthTracer.println("Synthesizing " + methodTable[methodIndex].getMethodName() + ": "  + (start - methodTable[methodIndex].getCodeRef()) + "-" + (stop - methodTable[methodIndex].getCodeRef()));
		}
		





		if(stop - 3 - start < REPLACED){
			System.err.println("NOWAY");
			return -999;
		}
		HardGen hardwareGenerator;
		CGRA cgra = (CGRA)functionalUnits[5];
		int [] tokenSet = null;
		try{
			if(methodBlacklist.containsKey(methodTable[methodIndex].getMethodName()) ){
				
				if(methodBlacklist.get(methodTable[methodIndex].getMethodName()).contains(start-methodTable[methodIndex].getCodeRef()))
				
				throw new SequenceNotSynthesizeableException("Method is on Blacklist");
			}
			
			if(kernelPointer >= kernelTable.length){
				throw new SequenceNotSynthesizeableException("Not enough entries in the KernelTable (Currently 32 are available)");
			}
			
			
			hardwareGenerator = new HardGen(methodTable, kernelTable, invokationHistory, methodIndex, start, stop, cse, inline, maxUnrollLength, unroll, code, cgra.getModel(), aliasing, constantFolding);
			if(schedule){
				hardwareGenerator.generate();
				
				if(synthTracer.active()){
					hardwareGenerator.printGraphs();
				}
				
				
				long[][] contextsPE = hardwareGenerator.getContextsPE();
				long[] contextsCBox = hardwareGenerator.getContextsCBox();
				long[] contextsControlUnit = hardwareGenerator.getContextsControlUnit();
				long[] contextsHandleCompare = hardwareGenerator.getContextsHandleCompare();
				
				stop = hardwareGenerator.getEndOfSequence();
				
				
				
			
				
				if(contextPointer + contextsCBox.length >= cgra.contextcbox.memory_length){
					throw new SequenceNotSynthesizeableException("Context memory is too small. Needed " + (contextPointer + contextsCBox.length) + " contexts. Only " + cgra.contextcbox.memory_length + " available");
				}
				if(synthTracer.active()){
					synthTracer.println("Synthesized " + contextsCBox.length);
				}
				
				///////////////////// PSEUDO PERIPHERY ACCESSS /////////////////////
				
				try{

					// PE Contexts
					for(int i = 0; i < contextsPE.length; i++){
						for( int j = 0; j < contextsPE[0].length; j++){
							cgra.context[i].memory[j+contextPointer] = contextsPE[i][j];
						}
					}
					// CBox Contexts
					for(int i = 0; i < contextsCBox.length; i++){
						cgra.contextcbox.memory[i+contextPointer] = contextsCBox[i];
					}
					// Control Unit contexts
					for(int i = 0; i < contextsControlUnit.length; i ++){
						cgra.controlunit.memory[i+contextPointer] = contextsControlUnit[i];
					}
					// Handlecompare contexts
//					for(int i = 0; i < contextsHandleCompare.length; i ++){
//						cgra.handleCompare.contexts[i+contextPointer] = contextsHandleCompare[i];
//					}
				} catch( ArrayIndexOutOfBoundsException e){
					System.err.println("Contexts to small: " + e.getMessage());
				}
				
				// Patch Bytecode
				byte[] replacedBytes = new byte [REPLACED];
				
				for(int i = 0; i<REPLACED; i++){
					replacedBytes[i] = code[i+start]; 
				}

				code[start] = ByteCode.SYNTH_INIT; 
				code[start+1] = kernelPointer;
				code[start+2] = (byte)hardwareGenerator.getNrLocalVarReceive();
				code[start+3] = (byte)hardwareGenerator.getNrIndirectConst();
				code[start+4] = (byte)hardwareGenerator.getNrDirectConst();
				code[start+5] = ByteCode.SYNTH_START;
				code[start+6] = (byte)hardwareGenerator.getNrLocalVarSend();
				code[start+7] = (byte)0;
				int jump = stop-start-2;
				code[start+8] = (byte)((jump>>8)&0xFF);
				code[start+9] = (byte)((jump)&0xFF);
				
				
				if(writeContexts){
				try {
					FileWriter fw;
					fw = new FileWriter("axt.patch");
					BufferedWriter bw = new BufferedWriter(fw);
					for(int i = 0; i<10; i++){
						patch = patch+code[start+i]+" ";
//						bw.write(code[start+i]+" ");
					}
//					bw.write(methodTable[methodIndex].getMethodName()+" "+((start - methodTable[methodIndex].getCodeRef())));
					patch = patch + methodTable[methodIndex].getMethodName()+" "+((start - methodTable[methodIndex].getCodeRef())) + "\n";
					bw.write(patch);
					bw.flush();
					bw.close();
				
				} catch (IOException e) {
				}
				}
				
				
//				System.out.println("METHOD OFF " +  (start - methodTable[methodIndex].getCodeRef()));
//				System.out.println("start " + start);
//				for(int i = 0; i<10; i++){
//					System.out.println("replaced: "+code[start+i]);
//				}
				
				
				// Store TokenSet		
				ArrayList<Integer> currentTokenSet = hardwareGenerator.getTokenSet();
				
				 tokenSet = tokenMachine.getTokenMemory();
				
				for(int i = 0; i < currentTokenSet.size(); i++){
					tokenSet[i+synthConstPointer] = currentTokenSet.get(i);
				}
				
				// Write Kernel Descriptor
				SynthesizedKernelDescriptor kernel = new SynthesizedKernelDescriptor();
				
				kernel.setReplacedBytes(replacedBytes);
				kernel.setContextPointer(contextPointer);
				kernel.setSynthConstPointer(synthConstPointer);
				
				kernelTable[kernelPointer] = kernel;
				
				kernelPointer += 1;
				synthConstPointer += currentTokenSet.size();
				contextPointer += contextsCBox.length;
				for(int i = 0; i<cgra.getModel().getNrOfMemoryAccessPEs() ; i++){
					cgra.InputCacheValid[i] = true;
				}
			} else {
				hardwareGenerator.generateCDFG();
				hardwareGenerator.exportCDFG();
//				hardwareGenerator.getCDFG();//TODO
			}
			
			
		} catch(SequenceNotSynthesizeableException e){
			if(synthTracer.active()){
				synthTracer.println("Not able to synthesize: " + e.getMessage());
			}
//			loop.setData(LoopProfileTableEntry.SYNTHESIZED, -1);
			return -1;
		}

		
		
		time = System.nanoTime() - time;
		
//		loop.setData(LoopProfileTableEntry.SYNTHESIZED, (int)(time/1000000));
		
		returnValue = (int)(time/1000000);
		
		
		
		
		
		/// WRITE INIT FOR HW IMPLEMENTATION
		String buf = "00000";
		if(writeContexts){
		try {
			FileWriter fw;
			fw = new FileWriter("cbox.dat");
			BufferedWriter bw = new BufferedWriter(fw);
			ContextMaskCBox cboxMask = cgra.getModel().getContextmaskcbox();
			
			for(int addr = 0; addr < contextPointer; addr++){
				String ret = cboxMask.getBitString(cgra.contextcbox.memory[addr]);
				bw.write(ret);
				bw.write("\n");
			}
			bw.close();
			
			fw = new FileWriter("ctrlunit.dat");
			bw = new BufferedWriter(fw);
			ContextMaskContextControlUnit ccuMask = cgra.getModel().getContextmaskccu();
			
			for(int addr = 0; addr < cgra.getMemorySizehOfContext(); addr++){
				String ret = ccuMask.getBitString(cgra.controlunit.memory[addr]);
				bw.write(ret);
				bw.write("\n");
			}
			bw.close();
			
			
			for( int pe = 0; pe < cgra.context.length; pe++){
				fw = new FileWriter("pe"+pe+".dat");
				bw = new BufferedWriter(fw);
				PE currentPE = cgra.getPEs()[pe];
				ContextMaskPE peMask = currentPE.getContextmask();
				
				for(int addr = 0; addr < contextPointer; addr++){
					String ret = peMask.getBitString(cgra.context[pe].memory[addr]);
					bw.write(ret);
					bw.write("\n");
				}
				bw.close();
				
			}
			
			fw = new FileWriter("kernel.dat");
			bw = new BufferedWriter(fw);
			
			for(int i = 0; i < kernelPointer; i++){
				String ret = "";
				String buff = "0000000000";
				String b;
				byte[] bytes = kernelTable[i].getReplacedBytes();
//				if(bytes == null){
					bytes = new byte[10];
//				}
				for(int j = 0; j<10; j++){
					b = Integer.toBinaryString(bytes[j]&0xFF);
					int l = b.length();
					int expL = 8;
					if(l<expL){
						b = buff.substring(0, expL-l) +b;
					}
					ret = ret+b;
				}

				b = Integer.toBinaryString(kernelTable[i].getSynthConstPointer());
							
				int l = b.length();
				int expL = 10;
				if(l<expL){
					b = buff.substring(0, expL-l) +b;
				}
				
				ret = ret+b;
				
				b = Integer.toBinaryString(kernelTable[i].getContextPointer());
				
				l = b.length();
				expL = 10;
				if(l<expL){
					b = buff.substring(0, expL-l) +b;
				}
				ret = ret+b;
				
				
				bw.write(ret);
				bw.write("\n");				
			}
			bw.close();
			
			fw = new FileWriter("liveinout.dat");
			bw = new BufferedWriter(fw);
			
			for(int i = 0; i<synthConstPointer; i++){
				String buff = "00000000000000000000000000000000";
				String ret = Integer.toBinaryString(tokenSet[i]);
				int l = ret.length();
				int expL = 32;
				if(l<expL){
					ret = buff.substring(0, expL-l) +ret;
				}
				
				
				bw.write(ret);
				bw.write("\n");
			}
			
			bw.close();
		
		} catch (IOException e) {
		}
		}
		
		///////////////// END PSEUDO PERIPHERY ACCESSS /////////////////////
		return returnValue;
	}
	


}
